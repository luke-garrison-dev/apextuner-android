package com.apextuner.feature.tools.game

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.database.GameSessionRecordDao
import com.apextuner.core.database.GameSessionRecordEntity
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.core.tuning.TemporaryProfileOverrideCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class GameSessionController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: GameSessionStore,
    private val tuning: SafeSystemTuningController,
    private val deviceRepository: DeviceRepository,
    private val records: GameSessionRecordDao,
    private val profileStore: GameProfileStore,
    private val temporaryProfileOverride: TemporaryProfileOverrideCoordinator,
) {
    private val mutex = Mutex()
    val state: Flow<GameSessionState> = store.state

    fun savedOptionsFor(packageName: String): GameSessionOptions? = profileStore.optionsFor(packageName)

    suspend fun recentSessions(limit: Int = 10): List<GameSessionInsight> = records.recent(limit.coerceIn(1, 50)).map { it.toInsight() }

    fun visibleGames(): List<GameApp> {
        val launcher = context.getSystemService(LauncherApps::class.java)
        return runCatching {
            launcher.getActivityList(null, Process.myUserHandle())
                .asSequence()
                .filter { it.applicationInfo.packageName != context.packageName }
                .map { GameApp(it.applicationInfo.packageName, it.label.toString().take(120), it.name) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .take(2_000)
                .toList()
        }.getOrDefault(emptyList())
    }

    suspend fun start(app: GameApp, options: GameSessionOptions): GameSessionResult = mutex.withLock {
        val existing = store.state.first()
        if (existing.active) return GameSessionResult.Failed("End the current game session before starting another.")
        if (!PACKAGE_PATTERN.matches(app.packageName) || app.activityClassName.isBlank()) return GameSessionResult.Failed("The selected app is not a valid launch target.")

        val warnings = mutableListOf<String>()
        val previousProfile = tuning.activeProfile()
        var profileChanged = false
        var dndChanged = false
        var previousFilter = 0
        var sessionPersisted = false
        var temporaryProfileLeaseClaimed = false
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val startSnapshot = optionalSnapshotCancellable()

        try {
            if (options.useGamingProfile) {
                temporaryProfileOverride.begin(
                    TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION,
                    System.currentTimeMillis() + PROFILE_OVERRIDE_LEASE_MILLIS,
                )
                temporaryProfileLeaseClaimed = true
                when (val result = tuning.apply(SystemProfile.Gaming)) {
                    is ProfileApplyResult.Applied -> profileChanged = true
                    is ProfileApplyResult.PermissionRequired -> {
                        temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
                        temporaryProfileLeaseClaimed = false
                        return GameSessionResult.Failed("Grant Modify system settings or disable the Gaming profile option.")
                    }
                    is ProfileApplyResult.Failed -> {
                        temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
                        temporaryProfileLeaseClaimed = false
                        return GameSessionResult.Failed(result.reason)
                    }
                    is ProfileApplyResult.Superseded -> {
                        temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
                        temporaryProfileLeaseClaimed = false
                        return GameSessionResult.Failed("A newer system-profile request superseded this session start.")
                    }
                }
            }

            if (options.silenceInterruptions) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    previousFilter = notificationManager.currentInterruptionFilter
                    runCatching { notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
                        .onSuccess { dndChanged = true }
                        .onFailure { warnings += "Android did not allow ApexTuner to change Do Not Disturb; the game still launched." }
                } else warnings += "Do Not Disturb access is not granted; calls/notifications remain under the user's current Android policy."
            }

            val state = GameSessionState(
                active = true,
                packageName = app.packageName,
                appLabel = app.label,
                startedAtEpochMillis = System.currentTimeMillis(),
                previousProfile = previousProfile,
                profileChangedByApexTuner = profileChanged,
                dndChangedByApexTuner = dndChanged,
                previousInterruptionFilter = previousFilter,
                startBatteryLevelPercent = startSnapshot?.battery?.levelPercent,
                peakBatteryTemperatureCelsius = startSnapshot?.battery?.temperatureCelsius,
                startRxBytes = startSnapshot?.network?.totalRxBytes,
                startTxBytes = startSnapshot?.network?.totalTxBytes,
                thermalWarningCelsius = options.thermalWarningCelsius.coerceIn(GameProfileStore.MIN_THERMAL_WARNING, GameProfileStore.MAX_THERMAL_WARNING),
            )
            store.save(state)
            sessionPersisted = true
            scheduleSafetyRestore()
            scheduleSessionMonitor()
            showNotification(state)

            val launcher = context.getSystemService(LauncherApps::class.java)
            runCatching {
                launcher.startMainActivity(android.content.ComponentName(app.packageName, app.activityClassName), Process.myUserHandle(), null, android.os.Bundle.EMPTY)
            }.getOrElse {
                // Roll back everything if the game cannot actually be launched.
                stopLocked("launch_failed")
                return GameSessionResult.Failed("Android could not launch the selected app.")
            }
            profileStore.save(app.packageName, options)
            GameSessionResult.Started(warnings)
        } catch (cancelled: CancellationException) {
            if (profileChanged || dndChanged || temporaryProfileLeaseClaimed) {
                withContext(NonCancellable) {
                    if (sessionPersisted) runCatching { stopLocked("cancelled") }
                    else {
                        rollbackUnpersistedStart(previousProfile, profileChanged, dndChanged, previousFilter)
                        temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
                    }
                }
            }
            throw cancelled
        } catch (_: Throwable) {
            if (profileChanged || dndChanged || temporaryProfileLeaseClaimed) {
                withContext(NonCancellable) {
                    if (sessionPersisted) runCatching { stopLocked("failure") }
                    else {
                        rollbackUnpersistedStart(previousProfile, profileChanged, dndChanged, previousFilter)
                        temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
                    }
                }
            }
            GameSessionResult.Failed("The game session could not be started safely.")
        }
    }

    suspend fun stop(reason: String = "user"): GameSessionResult = mutex.withLock { stopLocked(reason) }

    suspend fun recoverStaleSession(maxAgeMillis: Long = MAX_SESSION_MILLIS): GameSessionResult? = mutex.withLock {
        val session = store.state.first()
        if (!session.active) return null
        val age = (System.currentTimeMillis() - session.startedAtEpochMillis).coerceAtLeast(0L)
        if (age < maxAgeMillis) return null
        stopLocked("stale_timeout")
    }

    private suspend fun stopLocked(reason: String): GameSessionResult {
        val session = store.state.first()
        if (!session.active) {
            temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
            return GameSessionResult.Stopped(emptyList())
        }
        val warnings = mutableListOf<String>()
        var dndReleased = !session.dndChangedByApexTuner
        var profileReleased = !session.profileChangedByApexTuner

        if (session.dndChangedByApexTuner) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                warnings += "Do Not Disturb access was revoked; ApexTuner kept the session recovery record so restoration can be retried."
            } else if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALARMS
            ) {
                // Before Android 15 this API changes the global interruption filter. If the user
                // changed DND after the game session started, their newer choice must win.
                dndReleased = true
                warnings += "Do Not Disturb changed after the game session started, so ApexTuner left the newer user setting untouched."
            } else {
                runCatching { releaseDnd(notificationManager, session.previousInterruptionFilter) }
                    .onSuccess { dndReleased = true }
                    .onFailure { warnings += "Android did not release ApexTuner's Do Not Disturb session rule; restoration can be retried." }
            }
        }

        if (session.profileChangedByApexTuner) {
            val currentProfile = tuning.activeProfile()
            if (currentProfile != SystemProfile.Gaming) {
                // A profile selected after Game Booster owns the current state. Restoring the
                // older profile here would make two optimization features interfere.
                profileReleased = true
                warnings += "The system profile changed after the game session started, so ApexTuner kept the newer profile."
            } else {
                when (val restore = restoreProfile(session.previousProfile)) {
                    is ProfileApplyResult.PermissionRequired -> warnings += "Modify system settings access was revoked; ApexTuner kept the session recovery record so restoration can be retried."
                    is ProfileApplyResult.Failed -> warnings += restore.reason
                    is ProfileApplyResult.Superseded -> {
                        profileReleased = true
                        warnings += "A newer profile request superseded the old game-session profile restore."
                    }
                    is ProfileApplyResult.Applied -> profileReleased = true
                }
            }
        }

        if (!dndReleased || !profileReleased) {
            // Persist per-component progress. A later retry must not re-apply an already-restored
            // component and overwrite a newer user choice made in the meantime.
            store.save(
                session.copy(
                    dndChangedByApexTuner = !dndReleased,
                    profileChangedByApexTuner = !profileReleased,
                ),
            )
            scheduleSafetyRestore()
            return GameSessionResult.Failed(
                warnings.joinToString(" ").ifBlank { "ApexTuner could not finish restoring the game session yet. Retry End & restore." },
            )
        }

        val endSnapshot = runCatching { deviceRepository.snapshot() }.getOrNull()
        runCatching {
            val endedAt = System.currentTimeMillis()
            records.insert(
                GameSessionRecordEntity(
                    packageName = session.packageName ?: "unknown",
                    appLabel = session.appLabel ?: session.packageName ?: "Game",
                    startedAtEpochMillis = session.startedAtEpochMillis,
                    endedAtEpochMillis = endedAt,
                    startBatteryLevelPercent = session.startBatteryLevelPercent,
                    endBatteryLevelPercent = endSnapshot?.battery?.levelPercent,
                    peakBatteryTemperatureCelsius = listOfNotNull(session.peakBatteryTemperatureCelsius, endSnapshot?.battery?.temperatureCelsius).maxOrNull(),
                    startRxBytes = session.startRxBytes,
                    endRxBytes = endSnapshot?.network?.totalRxBytes,
                    startTxBytes = session.startTxBytes,
                    endTxBytes = endSnapshot?.network?.totalTxBytes,
                    gamingProfileUsed = session.profileChangedByApexTuner,
                    dndUsed = session.dndChangedByApexTuner,
                ),
            )
            records.deleteBefore(endedAt - SESSION_HISTORY_RETENTION_MILLIS)
        }.onFailure { warnings += "Session analytics could not be stored; reversible settings were still restored." }

        store.clear()
        temporaryProfileOverride.end(TemporaryProfileOverrideCoordinator.OWNER_GAME_SESSION)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(MONITOR_WORK_NAME)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        val completedWarnings = warnings + if (reason == "stale_timeout") {
            listOf("ApexTuner ended an old game session automatically to restore reversible settings.")
        } else emptyList()
        return GameSessionResult.Stopped(completedWarnings)
    }

    private suspend fun rollbackUnpersistedStart(
        previousProfile: SystemProfile,
        profileChanged: Boolean,
        dndChanged: Boolean,
        previousFilter: Int,
    ) {
        if (dndChanged) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.isNotificationPolicyAccessGranted) runCatching { releaseDnd(manager, previousFilter) }
        }
        if (profileChanged) runCatching { restoreProfile(previousProfile) }
    }

    private fun releaseDnd(notificationManager: NotificationManager, previousFilter: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Target-35+ calls operate ApexTuner's implicit AutomaticZenRule. ALL deactivates
            // that app-owned rule so other user/system DND rules keep their own authority.
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            val filter = previousFilter.takeIf { it in VALID_FILTERS } ?: NotificationManager.INTERRUPTION_FILTER_ALL
            notificationManager.setInterruptionFilter(filter)
        }
    }

    private suspend fun restoreProfile(previousProfile: SystemProfile): ProfileApplyResult =
        if (previousProfile == SystemProfile.Balanced) tuning.restoreBalanced() else tuning.apply(previousProfile)

    private suspend fun optionalSnapshotCancellable() = try {
        deviceRepository.snapshot()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun scheduleSafetyRestore() {
        val request = OneTimeWorkRequestBuilder<GameSessionTimeoutWorker>()
            .setInitialDelay(MAX_SESSION_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal suspend fun sampleActiveSession() {
        mutex.withLock {
            val session = store.state.first()
            if (!session.active) return
            val snapshot = optionalSnapshotCancellable() ?: return
            val temperature = snapshot.battery.temperatureCelsius
            val peak = listOfNotNull(session.peakBatteryTemperatureCelsius, temperature).maxOrNull()
            val warningNow = !session.thermalWarningIssued && temperature != null && temperature >= session.thermalWarningCelsius
            if (peak != session.peakBatteryTemperatureCelsius || warningNow) {
                store.save(session.copy(peakBatteryTemperatureCelsius = peak, thermalWarningIssued = session.thermalWarningIssued || warningNow))
            }
            if (warningNow) showThermalWarning(session, temperature)
        }
    }

    private fun scheduleSessionMonitor() {
        val request = PeriodicWorkRequestBuilder<GameSessionMonitorWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(MONITOR_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun showThermalWarning(state: GameSessionState, temperature: Double) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(com.apextuner.feature.tools.R.string.game_session_channel_name), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(com.apextuner.feature.tools.R.string.game_thermal_warning_title))
            .setContentText(context.getString(com.apextuner.feature.tools.R.string.game_thermal_warning_body, state.appLabel ?: "Game", temperature))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(THERMAL_NOTIFICATION_ID, notification) }
    }

    private fun GameSessionRecordEntity.toInsight(): GameSessionInsight = GameSessionInsight(
        packageName = packageName,
        appLabel = appLabel,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        startBatteryLevelPercent = startBatteryLevelPercent,
        endBatteryLevelPercent = endBatteryLevelPercent,
        peakBatteryTemperatureCelsius = peakBatteryTemperatureCelsius,
        receivedBytes = byteDelta(startRxBytes, endRxBytes),
        transmittedBytes = byteDelta(startTxBytes, endTxBytes),
        gamingProfileUsed = gamingProfileUsed,
        dndUsed = dndUsed,
    )

    private fun byteDelta(start: Long?, end: Long?): Long? = if (start != null && end != null && end >= start) end - start else null

    private fun showNotification(state: GameSessionState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(com.apextuner.feature.tools.R.string.game_session_channel_name), NotificationManager.IMPORTANCE_LOW))
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = launchIntent?.let { PendingIntent.getActivity(context, 4101, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val stop = PendingIntent.getBroadcast(
            context,
            4102,
            Intent(context, GameSessionReceiver::class.java).setAction(GameSessionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sessionMode = when {
            state.profileChangedByApexTuner && state.dndChangedByApexTuner -> "Gaming profile + Do Not Disturb"
            state.profileChangedByApexTuner -> "Gaming profile active"
            state.dndChangedByApexTuner -> "Do Not Disturb active"
            else -> "session active; no system settings changed"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(com.apextuner.feature.tools.R.string.game_session_notification_title))
            .setContentText(context.getString(com.apextuner.feature.tools.R.string.game_session_notification_body, state.appLabel ?: context.getString(com.apextuner.feature.tools.R.string.game_session_default_app), sessionMode))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .addAction(0, context.getString(com.apextuner.feature.tools.R.string.game_session_end_restore), stop)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "apextuner_game_session"
        const val NOTIFICATION_ID = 4100
        const val THERMAL_NOTIFICATION_ID = 4103
        const val WORK_NAME = "apextuner_game_session_safety_restore"
        const val MONITOR_WORK_NAME = "apextuner_game_session_monitor"
        const val MAX_SESSION_MILLIS = 6L * 60L * 60L * 1_000L
        const val PROFILE_OVERRIDE_LEASE_MILLIS = MAX_SESSION_MILLIS + 60L * 60L * 1_000L
        const val SESSION_HISTORY_RETENTION_MILLIS = 180L * 24L * 60L * 60L * 1_000L
        val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        val VALID_FILTERS = setOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
        )
    }
}
