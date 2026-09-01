package com.apextuner.feature.battery

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apextuner.core.database.ChargingSessionDao
import com.apextuner.core.database.ChargingSessionEntity
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.system.BatteryTelemetryReader
import com.apextuner.feature.battery.model.ChargingSessionInsight
import com.apextuner.feature.battery.model.EstimateConfidence
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


internal object ChargingSessionMaintenancePolicy {
    const val PRUNE_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L

    fun shouldPrune(previousPruneEpochMillis: Long?, nowEpochMillis: Long): Boolean =
        previousPruneEpochMillis == null ||
            nowEpochMillis < previousPruneEpochMillis ||
            nowEpochMillis - previousPruneEpochMillis >= PRUNE_INTERVAL_MILLIS
}

@Singleton
class ChargingSessionTracker @Inject constructor(
    private val dao: ChargingSessionDao,
) {
    private val observationMutex = Mutex()
    private var lastPruneEpochMillis: Long? = null

    suspend fun observe(battery: BatterySnapshot, now: Long = System.currentTimeMillis()) = observationMutex.withLock {
        val active = dao.active()
        if (battery.charging) {
            if (active == null) {
                val temperature = battery.temperatureCelsius?.takeIf { it.isFinite() }
                dao.upsert(
                    ChargingSessionEntity(
                        startedAtEpochMillis = now,
                        endedAtEpochMillis = null,
                        startLevelPercent = battery.levelPercent,
                        endLevelPercent = battery.levelPercent,
                        startChargeCounterMicroampHours = battery.chargeCounterMicroampHours,
                        endChargeCounterMicroampHours = battery.chargeCounterMicroampHours,
                        startCycleCount = battery.cycleCount,
                        endCycleCount = battery.cycleCount,
                        maxTemperatureCelsius = temperature,
                        temperatureSumCelsius = temperature ?: 0.0,
                        temperatureSampleCount = if (temperature != null) 1 else 0,
                        maxAbsCurrentMicroamps = battery.currentMicroamps?.safeAbs(),
                        sampleCount = 1,
                    ),
                )
            } else {
                dao.upsert(active.withSample(battery))
            }
        } else if (active != null) {
            // The first observation after unplugging belongs to the terminal boundary, not the
            // charging interval. Keep end-of-session counters/level, but do not contaminate
            // charging-only thermal/current/sample statistics with a discharging observation.
            dao.upsert(active.withTerminalBatterySample(battery).copy(endedAtEpochMillis = now))
        }
        val previousPrune = lastPruneEpochMillis
        if (ChargingSessionMaintenancePolicy.shouldPrune(previousPrune, now)) {
            dao.deleteCompletedBefore(now - RETENTION_MILLIS)
            lastPruneEpochMillis = now
        }
    }

    suspend fun recent(limit: Int = 12): List<ChargingSessionInsight> =
        dao.recentCompleted(limit).map(::toInsight)

    private fun ChargingSessionEntity.withSample(battery: BatterySnapshot): ChargingSessionEntity {
        val temp = battery.temperatureCelsius?.takeIf { it.isFinite() }
        val currentAbs = battery.currentMicroamps?.safeAbs()
        return copy(
            endLevelPercent = battery.levelPercent ?: endLevelPercent,
            endChargeCounterMicroampHours = battery.chargeCounterMicroampHours ?: endChargeCounterMicroampHours,
            endCycleCount = battery.cycleCount ?: endCycleCount,
            maxTemperatureCelsius = listOfNotNull(maxTemperatureCelsius, temp).maxOrNull(),
            temperatureSumCelsius = temperatureSumCelsius + (temp ?: 0.0),
            temperatureSampleCount = temperatureSampleCount + if (temp != null) 1 else 0,
            maxAbsCurrentMicroamps = listOfNotNull(maxAbsCurrentMicroamps, currentAbs).maxOrNull(),
            sampleCount = sampleCount + 1,
        )
    }

    private fun toInsight(row: ChargingSessionEntity): ChargingSessionInsight {
        val ended = row.endedAtEpochMillis ?: row.startedAtEpochMillis
        val startChargeCounter = row.startChargeCounterMicroampHours
        val endChargeCounter = row.endChargeCounterMicroampHours
        val chargeDelta = if (startChargeCounter != null && endChargeCounter != null) {
            (endChargeCounter - startChargeCounter).takeIf { it >= 0L }
        } else null
        val startCycleCount = row.startCycleCount
        val endCycleCount = row.endCycleCount
        val confidence = when {
            row.sampleCount >= 8 && chargeDelta != null -> EstimateConfidence.High
            row.sampleCount >= 3 -> EstimateConfidence.Medium
            else -> EstimateConfidence.Low
        }
        return ChargingSessionInsight(
            startedAtEpochMillis = row.startedAtEpochMillis,
            endedAtEpochMillis = ended,
            durationMillis = (ended - row.startedAtEpochMillis).coerceAtLeast(0L),
            startLevelPercent = row.startLevelPercent,
            endLevelPercent = row.endLevelPercent,
            estimatedAddedMah = chargeDelta?.div(1000.0),
            averageTemperatureCelsius = if (row.temperatureSampleCount > 0) row.temperatureSumCelsius / row.temperatureSampleCount else null,
            peakTemperatureCelsius = row.maxTemperatureCelsius,
            maximumCurrentMicroamps = row.maxAbsCurrentMicroamps,
            cycleDelta = if (startCycleCount != null && endCycleCount != null) (endCycleCount - startCycleCount).coerceAtLeast(0) else null,
            sampleCount = row.sampleCount,
            confidence = confidence,
        )
    }

    private fun Long.safeAbs(): Long = if (this == Long.MIN_VALUE) Long.MAX_VALUE else abs(this)

    private companion object {
        const val RETENTION_MILLIS = 180L * 24L * 60L * 60L * 1_000L
    }
}

@HiltWorker
class ChargingSessionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val telemetryReader: BatteryTelemetryReader,
    private val tracker: ChargingSessionTracker,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        tracker.observe(telemetryReader.read())
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        Result.retry()
    }
}

@Singleton
class ChargingSessionScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<ChargingSessionWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "apextuner.charging_session_sampling"
        const val TAG = "apextuner.battery_intelligence"
    }
}
