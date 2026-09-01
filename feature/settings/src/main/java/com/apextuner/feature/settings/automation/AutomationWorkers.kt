package com.apextuner.feature.settings.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.apextuner.core.backup.BackupRestoreManager
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.core.tuning.TemporaryProfileOverrideCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val entitlementRepository: EntitlementRepository,
    private val preferencesRepository: PreferencesRepository,
    private val deviceRepository: DeviceRepository,
    private val automationScheduler: AutomationScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.scheduledMaintenanceEnabled) return Result.success()

        try {
            entitlementRepository.refresh("scheduled_maintenance")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        if (!entitlementRepository.entitlement.value.isPremium) {
            automationScheduler.realignMaintenance(id, prefs.maintenanceCadence)
            return Result.success()
        }
        val snapshot = try {
            deviceRepository.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        val internal = snapshot.storage.internal
        val freeFraction = if (internal.totalBytes > 0L) internal.availableBytes.toDouble() / internal.totalBytes else 1.0
        if (freeFraction < LOW_STORAGE_FRACTION) {
            AutomationNotifications.notify(
                applicationContext,
                NOTIFICATION_LOW_STORAGE,
                applicationContext.getString(com.apextuner.feature.settings.R.string.automation_low_storage_title),
                applicationContext.getString(com.apextuner.feature.settings.R.string.automation_low_storage_body),
            )
        }
        // Scheduled work is deliberately advisory: never delete user files without an
        // interactive review/confirmation surface.
        automationScheduler.realignMaintenance(id, prefs.maintenanceCadence)
        return Result.success()
    }

    private companion object {
        const val LOW_STORAGE_FRACTION = 0.15
        const val NOTIFICATION_LOW_STORAGE = 7101
    }
}

@HiltWorker
class NightBatteryProfileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val entitlementRepository: EntitlementRepository,
    private val preferencesRepository: PreferencesRepository,
    private val tuningController: SafeSystemTuningController,
    private val automationScheduler: AutomationScheduler,
    private val temporaryProfileOverride: TemporaryProfileOverrideCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.nightBatteryProfileEnabled) return Result.success()
        if (prefs.nightBatteryProfileAppliedByAutomation) return successAligned()

        try {
            entitlementRepository.refresh("night_battery_profile")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        if (!entitlementRepository.entitlement.value.isPremium) return successAligned()
        if (temporaryProfileOverride.isActive()) return successAligned()
        if (tuningController.activeProfile() != SystemProfile.Balanced) return successAligned()

        return when (tuningController.apply(SystemProfile.Battery)) {
            is ProfileApplyResult.Applied -> {
                preferencesRepository.setNightBatteryProfileAppliedByAutomation(true)
                successAligned()
            }
            is ProfileApplyResult.PermissionRequired -> {
                AutomationNotifications.notify(
                    applicationContext,
                    7102,
                    applicationContext.getString(com.apextuner.feature.settings.R.string.automation_battery_access_title),
                    applicationContext.getString(com.apextuner.feature.settings.R.string.automation_battery_access_body),
                )
                successAligned()
            }
            is ProfileApplyResult.Superseded -> successAligned()
            is ProfileApplyResult.Failed -> Result.retry()
        }
    }

    private suspend fun successAligned(): Result {
        automationScheduler.realignNightBattery(id)
        return Result.success()
    }
}

@HiltWorker
class ScheduledBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val entitlementRepository: EntitlementRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupRestoreManager: BackupRestoreManager,
    private val automationScheduler: AutomationScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.scheduledBackupEnabled) return Result.success()
        val treeUri = prefs.scheduledBackupTreeUri?.let(Uri::parse) ?: run {
            preferencesRepository.setScheduledBackupEnabled(false)
            return Result.success()
        }

        try {
            entitlementRepository.refresh("scheduled_backup")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        if (!entitlementRepository.entitlement.value.isPremium) return successAligned(prefs.scheduledBackupCadence)

        return try {
            backupRestoreManager.writeScheduled(
                treeUri = treeUri,
                retentionCount = prefs.scheduledBackupRetentionCount,
            )
            successAligned(prefs.scheduledBackupCadence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            preferencesRepository.setScheduledBackupEnabled(false)
            AutomationNotifications.notify(
                applicationContext,
                7103,
                applicationContext.getString(com.apextuner.feature.settings.R.string.automation_backup_access_title),
                applicationContext.getString(com.apextuner.feature.settings.R.string.automation_backup_access_body),
            )
            Result.success()
        } catch (_: IllegalArgumentException) {
            preferencesRepository.setScheduledBackupEnabled(false)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private suspend fun successAligned(cadence: com.apextuner.core.model.MaintenanceCadence): Result {
        automationScheduler.realignScheduledBackup(id, cadence)
        return Result.success()
    }
}


@HiltWorker
class MorningProfileRestoreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferencesRepository: PreferencesRepository,
    private val tuningController: SafeSystemTuningController,
    private val automationScheduler: AutomationScheduler,
    private val temporaryProfileOverride: TemporaryProfileOverrideCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.nightBatteryProfileAppliedByAutomation) {
            if (prefs.nightBatteryProfileEnabled) automationScheduler.realignMorningRestore(id)
            return Result.success()
        }
        // Restoration is intentionally not Premium-gated: if the entitlement expires
        // overnight, ApexTuner must still undo any system mutation it applied. A temporary
        // Game Booster profile is allowed to finish first so independent features never fight.
        if (temporaryProfileOverride.isActive()) return Result.retry()
        if (tuningController.activeProfile() != SystemProfile.Battery) {
            // A newer manual profile supersedes the old night automation. Respect it and release
            // only the night-automation marker; do not overwrite the user's newer selection.
            preferencesRepository.setNightBatteryProfileAppliedByAutomation(false)
            if (prefs.nightBatteryProfileEnabled) automationScheduler.realignMorningRestore(id)
            return Result.success()
        }
        return when (tuningController.restoreBalanced()) {
            is ProfileApplyResult.Applied -> {
                preferencesRepository.setNightBatteryProfileAppliedByAutomation(false)
                if (prefs.nightBatteryProfileEnabled) automationScheduler.realignMorningRestore(id)
                Result.success()
            }
            is ProfileApplyResult.PermissionRequired -> Result.retry()
            is ProfileApplyResult.Superseded -> Result.retry()
            is ProfileApplyResult.Failed -> Result.retry()
        }
    }
}


internal object AutomationNotifications {
    private const val CHANNEL_ID = "apextuner_automation"

    fun notify(context: Context, id: Int, title: String, body: String): Boolean {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                id,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(com.apextuner.feature.settings.R.string.automation_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(com.apextuner.feature.settings.R.string.automation_channel_description)
            },
        )
    }
}
