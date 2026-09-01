package com.apextuner.feature.battery

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.apextuner.core.database.BatteryHealthSnapshotDao
import com.apextuner.core.database.BatteryHealthSnapshotEntity
import com.apextuner.core.system.BatteryTelemetryReader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class BatteryHealthSnapshotWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val telemetryReader: BatteryTelemetryReader,
    private val dao: BatteryHealthSnapshotDao,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val battery = telemetryReader.read()
            val capacity = BatteryHealthSnapshotPolicy.estimateFullChargeCapacityMicroampHours(
                battery.chargeCounterMicroampHours,
                battery.levelPercent,
            )
            // Persist one bounded daily observation even when this OEM exposes neither cycle
            // count nor a usable charge counter. That lets the UI distinguish unsupported
            // telemetry from a scheduler that never ran.
            dao.upsert(
                BatteryHealthSnapshotEntity(
                    epochDay = BatteryHealthSnapshotPolicy.epochDay(now),
                    capturedAtEpochMillis = now,
                    cycleCount = battery.cycleCount,
                    estimatedFullChargeCapacityMicroampHours = capacity,
                    sourceLevelPercent = battery.levelPercent,
                ),
            )
            dao.deleteBefore(BatteryHealthSnapshotPolicy.minimumRetainedEpochDay())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
