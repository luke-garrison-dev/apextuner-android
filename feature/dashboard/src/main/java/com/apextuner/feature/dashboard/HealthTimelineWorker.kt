package com.apextuner.feature.dashboard

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apextuner.core.database.DeviceHealthSampleDao
import com.apextuner.core.database.DeviceHealthSampleEntity
import com.apextuner.core.repository.DeviceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@HiltWorker
class HealthTimelineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val deviceRepository: DeviceRepository,
    private val dao: DeviceHealthSampleDao,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val snapshot = deviceRepository.snapshot()
        dao.insert(
            DeviceHealthSampleEntity(
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                cpuUsagePercent = snapshot.cpu.totalUsagePercent,
                memoryUsedPercent = snapshot.memory.usedFraction * 100.0,
                internalStorageAvailableBytes = snapshot.storage.internal.availableBytes,
                internalStorageTotalBytes = snapshot.storage.internal.totalBytes,
                batteryLevelPercent = snapshot.battery.levelPercent,
                batteryTemperatureCelsius = snapshot.battery.temperatureCelsius,
                batteryCurrentMicroamps = snapshot.battery.currentMicroamps,
                batteryCharging = snapshot.battery.charging,
                thermalStatus = snapshot.thermalStatus.name,
                networkMetered = snapshot.network.metered,
                networkValidated = snapshot.network.activeNetworkValidated,
                totalRxBytes = snapshot.network.totalRxBytes,
                totalTxBytes = snapshot.network.totalTxBytes,
            ),
        )
        dao.deleteBefore(System.currentTimeMillis() - RETENTION_MILLIS)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        Result.retry()
    }

    private companion object {
        const val RETENTION_MILLIS = 100L * 24L * 60L * 60L * 1_000L
    }
}

@Singleton
class HealthTimelineScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<HealthTimelineWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "apextuner.health_timeline"
        const val TAG = "apextuner.health"
    }
}
