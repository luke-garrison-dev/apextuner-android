package com.apextuner.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.feature.battery.BatteryHealthTrendScheduler
import com.apextuner.feature.battery.ChargingSessionScheduler
import com.apextuner.feature.dashboard.HealthTimelineScheduler
import com.apextuner.feature.network.DataUsageAlertScheduler
import com.apextuner.feature.network.DataUsageCapPreferences
import com.apextuner.feature.notifications.NotificationHistoryPreferences
import com.apextuner.feature.notifications.NotificationHistoryScheduler
import com.apextuner.feature.settings.automation.AutomationScheduler
import com.apextuner.feature.settings.automation.SmartAutomationRepository
import com.apextuner.feature.settings.automation.SmartAutomationRecovery
import com.apextuner.feature.tools.game.GameSessionController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Lightweight repair entry point for system lifecycle events that can invalidate or delay
 * scheduled work. WorkManager already persists periodic work across reboot; this receiver does
 * not replace that behavior. It only asks WorkManager to reconcile ApexTuner's desired schedule
 * after an app update, reboot, or timezone change, without opening Billing or doing telemetry in
 * the broadcast callback.
 */
class AppLifecycleRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val request = OneTimeWorkRequestBuilder<AppLifecycleRecoveryWorker>()
            .setInputData(workDataOf(AppLifecycleRecoveryWorker.KEY_REASON to action))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "apextuner.lifecycle_recovery"
        const val TAG = "apextuner.recovery"
        val SUPPORTED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

@HiltWorker
class AppLifecycleRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferencesRepository: PreferencesRepository,
    private val entitlementRepository: EntitlementRepository,
    private val batteryHealthTrendScheduler: BatteryHealthTrendScheduler,
    private val chargingSessionScheduler: ChargingSessionScheduler,
    private val healthTimelineScheduler: HealthTimelineScheduler,
    private val dataUsageAlertScheduler: DataUsageAlertScheduler,
    private val dataUsageCapPreferences: DataUsageCapPreferences,
    private val notificationHistoryPreferences: NotificationHistoryPreferences,
    private val notificationHistoryScheduler: NotificationHistoryScheduler,
    private val automationScheduler: AutomationScheduler,
    private val smartAutomationRepository: SmartAutomationRepository,
    private val smartAutomationRecovery: SmartAutomationRecovery,
    private val tuningController: SafeSystemTuningController,
    private val gameSessionController: GameSessionController,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val preferences = preferencesRepository.preferences.first()
            val premium = entitlementRepository.entitlement.value.isPremium
            val hasEnabledSmartRules = smartAutomationRepository.hasEnabledRules()

            batteryHealthTrendScheduler.ensureScheduled()
            chargingSessionScheduler.ensureScheduled()
            healthTimelineScheduler.ensureScheduled()
            automationScheduler.sync(preferences, premium, hasEnabledSmartRules)
            dataUsageAlertScheduler.sync(dataUsageCapPreferences.caps.first().isNotEmpty())

            if (notificationHistoryPreferences.settings.first().enabled) {
                notificationHistoryScheduler.ensureScheduled()
            }

            // These operations are ownership-safe and intentionally do not contact Google Play.
            runBestEffortCancellable { tuningController.reconcileLegacyState() }
            val reason = inputData.getString(KEY_REASON)
            if (reason == Intent.ACTION_BOOT_COMPLETED || reason == Intent.ACTION_MY_PACKAGE_REPLACED) {
                // An external game cannot retain a trustworthy ApexTuner session lease across a
                // reboot/package replacement. Restore reversible state immediately.
                runBestEffortCancellable { gameSessionController.stop("system_recovery") }
            } else {
                runBestEffortCancellable { gameSessionController.recoverStaleSession() }
            }
            runBestEffortCancellable { smartAutomationRecovery.reconcileOwnedProfile(forceRestore = !premium) }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private suspend fun runBestEffortCancellable(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Unit
        }
    }

    companion object {
        const val KEY_REASON = "recovery_reason"
    }
}
