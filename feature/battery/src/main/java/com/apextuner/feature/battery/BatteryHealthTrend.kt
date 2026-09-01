package com.apextuner.feature.battery

import com.apextuner.core.database.BatteryHealthSnapshotEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

data class BatteryHealthPoint(
    val epochDay: Long,
    val capturedAtEpochMillis: Long,
    val cycleCount: Int?,
    val estimatedFullChargeCapacityMah: Double?,
)

sealed interface BatteryHealthTrend {
    data class TelemetryUnavailable(
        val daysObserved: Int,
    ) : BatteryHealthTrend

    data class InsufficientHistory(
        val daysAvailable: Int,
        val minimumDays: Int,
    ) : BatteryHealthTrend

    data class Ready(
        val points: List<BatteryHealthPoint>,
        val capacityChangePercent: Double?,
    ) : BatteryHealthTrend
}

object BatteryHealthSnapshotPolicy {
    const val MIN_TREND_DAYS = 7
    const val MAX_HISTORY_DAYS = 400L
    private const val MIN_ESTIMATE_LEVEL_PERCENT = 20
    private const val MIN_CAPACITY_UAH = 100_000L
    private const val MAX_CAPACITY_UAH = 30_000_000L

    fun epochDay(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toEpochDay()

    fun estimateFullChargeCapacityMicroampHours(
        chargeCounterMicroampHours: Long?,
        levelPercent: Int?,
    ): Long? {
        val charge = chargeCounterMicroampHours ?: return null
        val level = levelPercent ?: return null
        if (charge <= 0L || level !in MIN_ESTIMATE_LEVEL_PERCENT..100) return null
        val estimate = (charge.toDouble() * 100.0 / level.toDouble()).roundToLong()
        return estimate.takeIf { it in MIN_CAPACITY_UAH..MAX_CAPACITY_UAH }
    }

    fun toTrend(rows: List<BatteryHealthSnapshotEntity>): BatteryHealthTrend {
        val points = rows
            .distinctBy { it.epochDay }
            .sortedBy { it.epochDay }
            .map {
                BatteryHealthPoint(
                    epochDay = it.epochDay,
                    capturedAtEpochMillis = it.capturedAtEpochMillis,
                    cycleCount = it.cycleCount,
                    estimatedFullChargeCapacityMah = it.estimatedFullChargeCapacityMicroampHours?.div(1000.0),
                )
            }
        val usablePoints = points.filter { it.cycleCount != null || it.estimatedFullChargeCapacityMah != null }
        if (points.isNotEmpty() && usablePoints.isEmpty()) {
            return BatteryHealthTrend.TelemetryUnavailable(points.size)
        }
        if (usablePoints.size < MIN_TREND_DAYS) {
            return BatteryHealthTrend.InsufficientHistory(usablePoints.size, MIN_TREND_DAYS)
        }
        val capacities = usablePoints.mapNotNull { it.estimatedFullChargeCapacityMah }
        val change = if (capacities.size >= 2 && capacities.first() > 0.0) {
            ((capacities.last() - capacities.first()) / capacities.first()) * 100.0
        } else null
        return BatteryHealthTrend.Ready(usablePoints, change)
    }

    fun minimumRetainedEpochDay(today: LocalDate = LocalDate.now()): Long =
        today.minusDays(MAX_HISTORY_DAYS).toEpochDay()
}
