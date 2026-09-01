package com.apextuner.feature.settings.automation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.apextuner.core.model.AppPreferences
import com.apextuner.core.model.MaintenanceCadence
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class AutomationScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun sync(preferences: AppPreferences, premium: Boolean, hasEnabledSmartRules: Boolean) {
        syncSmartAutomation(premium, hasEnabledSmartRules)

        if (premium && preferences.scheduledMaintenanceEnabled) {
            scheduleMaintenance(preferences.maintenanceCadence)
        } else {
            workManager.cancelUniqueWork(WORK_MAINTENANCE)
        }

        if (premium && preferences.nightBatteryProfileEnabled) {
            scheduleNightBattery()
            scheduleMorningRestore()
        } else {
            workManager.cancelUniqueWork(WORK_NIGHT_BATTERY)
            if (preferences.nightBatteryProfileAppliedByAutomation) scheduleMorningRestore()
            else workManager.cancelUniqueWork(WORK_MORNING_RESTORE)
        }

        if (premium && preferences.scheduledBackupEnabled && !preferences.scheduledBackupTreeUri.isNullOrBlank()) {
            scheduleBackup(preferences.scheduledBackupCadence)
        } else {
            workManager.cancelUniqueWork(WORK_SCHEDULED_BACKUP)
        }
    }

    fun syncSmartAutomation(premium: Boolean, hasEnabledSmartRules: Boolean) {
        if (SmartAutomationSchedulePolicy.shouldSchedule(premium, hasEnabledSmartRules)) scheduleSmartAutomation()
        else workManager.cancelUniqueWork(WORK_SMART_AUTOMATION)
    }

    suspend fun realignMaintenance(workId: UUID, cadence: MaintenanceCadence) {
        val days = if (cadence == MaintenanceCadence.Daily) 1 else 7
        updateWorkSafely(maintenanceRequest(cadence, workId, nextAfterDays(hour = 3, days = days)))
    }

    suspend fun realignNightBattery(workId: UUID) {
        updateWorkSafely(nightBatteryRequest(workId, nextAfterDays(hour = 22, days = 1)))
    }

    suspend fun realignMorningRestore(workId: UUID) {
        updateWorkSafely(morningRestoreRequest(workId, nextAfterDays(hour = 7, days = 1)))
    }

    suspend fun realignScheduledBackup(workId: UUID, cadence: MaintenanceCadence) {
        val days = if (cadence == MaintenanceCadence.Daily) 1 else 7
        updateWorkSafely(scheduledBackupRequest(cadence, workId, nextAfterDays(hour = 4, days = days)))
    }

    private fun scheduleMaintenance(cadence: MaintenanceCadence) {
        workManager.enqueueUniquePeriodicWork(
            WORK_MAINTENANCE,
            ExistingPeriodicWorkPolicy.UPDATE,
            maintenanceRequest(cadence, scheduleOverride = next(hour = 3)),
        )
    }

    private fun scheduleNightBattery() {
        workManager.enqueueUniquePeriodicWork(
            WORK_NIGHT_BATTERY,
            ExistingPeriodicWorkPolicy.UPDATE,
            nightBatteryRequest(scheduleOverride = next(hour = 22)),
        )
    }

    private fun scheduleMorningRestore() {
        workManager.enqueueUniquePeriodicWork(
            WORK_MORNING_RESTORE,
            ExistingPeriodicWorkPolicy.UPDATE,
            morningRestoreRequest(scheduleOverride = next(hour = 7)),
        )
    }

    private fun scheduleBackup(cadence: MaintenanceCadence) {
        workManager.enqueueUniquePeriodicWork(
            WORK_SCHEDULED_BACKUP,
            ExistingPeriodicWorkPolicy.UPDATE,
            scheduledBackupRequest(cadence, scheduleOverride = next(hour = 4)),
        )
    }

    private fun scheduleSmartAutomation() {
        val request = PeriodicWorkRequestBuilder<SmartAutomationWorker>(
            15,
            TimeUnit.MINUTES,
            5,
            TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_AUTOMATION)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_SMART_AUTOMATION,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun maintenanceRequest(
        cadence: MaintenanceCadence,
        workId: UUID? = null,
        scheduleOverride: Long,
    ): PeriodicWorkRequest {
        val intervalDays = if (cadence == MaintenanceCadence.Daily) 1L else 7L
        val builder = PeriodicWorkRequestBuilder<MaintenanceWorker>(
            intervalDays,
            TimeUnit.DAYS,
            2,
            TimeUnit.HOURS,
        )
            .setNextScheduleTimeOverride(scheduleOverride)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(TAG_AUTOMATION)
        workId?.let(builder::setId)
        return builder.build()
    }

    private fun nightBatteryRequest(
        workId: UUID? = null,
        scheduleOverride: Long,
    ): PeriodicWorkRequest {
        val builder = PeriodicWorkRequestBuilder<NightBatteryProfileWorker>(
            24,
            TimeUnit.HOURS,
            60,
            TimeUnit.MINUTES,
        )
            .setNextScheduleTimeOverride(scheduleOverride)
            .addTag(TAG_AUTOMATION)
        workId?.let(builder::setId)
        return builder.build()
    }

    private fun morningRestoreRequest(
        workId: UUID? = null,
        scheduleOverride: Long,
    ): PeriodicWorkRequest {
        val builder = PeriodicWorkRequestBuilder<MorningProfileRestoreWorker>(
            24,
            TimeUnit.HOURS,
            60,
            TimeUnit.MINUTES,
        )
            .setNextScheduleTimeOverride(scheduleOverride)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_AUTOMATION)
        workId?.let(builder::setId)
        return builder.build()
    }

    private fun scheduledBackupRequest(
        cadence: MaintenanceCadence,
        workId: UUID? = null,
        scheduleOverride: Long,
    ): PeriodicWorkRequest {
        val intervalDays = if (cadence == MaintenanceCadence.Daily) 1L else 7L
        val builder = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            intervalDays,
            TimeUnit.DAYS,
            2,
            TimeUnit.HOURS,
        )
            .setNextScheduleTimeOverride(scheduleOverride)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(TAG_AUTOMATION)
        workId?.let(builder::setId)
        return builder.build()
    }

    private suspend fun updateWorkSafely(request: PeriodicWorkRequest) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val future = workManager.updateWork(request)
            future.addListener(
                {
                    if (!continuation.isActive) return@addListener
                    runCatching { future.get() }
                    continuation.resume(Unit)
                },
                DIRECT_EXECUTOR,
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }

    private fun next(hour: Int): Long =
        AutomationTiming.nextLocalEpochMillis(ZonedDateTime.now(), hour)

    private fun nextAfterDays(hour: Int, days: Int): Long =
        AutomationTiming.nextLocalEpochMillisAfterDays(ZonedDateTime.now(), hour, days)

    companion object {
        const val TAG_AUTOMATION = "apextuner.automation"
        const val WORK_MAINTENANCE = "apextuner.maintenance"
        const val WORK_NIGHT_BATTERY = "apextuner.night_battery"
        const val WORK_MORNING_RESTORE = "apextuner.morning_restore"
        const val WORK_SCHEDULED_BACKUP = "apextuner.scheduled_backup"
        const val WORK_SMART_AUTOMATION = "apextuner.smart_automation"
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
