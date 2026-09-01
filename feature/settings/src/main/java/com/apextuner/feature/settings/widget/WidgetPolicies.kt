package com.apextuner.feature.settings.widget

import com.apextuner.core.database.BatteryHealthSnapshotEntity

internal object StorageWidgetMath {
    fun usedFraction(totalBytes: Long, freeBytes: Long): Double {
        if (totalBytes <= 0L) return 0.0
        val boundedFree = freeBytes.coerceIn(0L, totalBytes)
        return (totalBytes - boundedFree).toDouble() / totalBytes.toDouble()
    }
}

internal sealed interface BatteryWidgetTrend {
    data object Unavailable : BatteryWidgetTrend
    data class Insufficient(val latestCapacityMicroampHours: Long?) : BatteryWidgetTrend
    data class Ready(val latestCapacityMicroampHours: Long, val percentChange: Double) : BatteryWidgetTrend
}

internal object BatteryWidgetTrendPolicy {
    const val MIN_DAYS = 7

    fun evaluate(snapshots: List<BatteryHealthSnapshotEntity>): BatteryWidgetTrend {
        val distinctDays = snapshots.map { it.epochDay }.distinct().size
        val valid = snapshots.filter { (it.estimatedFullChargeCapacityMicroampHours ?: 0L) > 0L }
        if (snapshots.isNotEmpty() && valid.isEmpty()) return BatteryWidgetTrend.Unavailable
        if (distinctDays < MIN_DAYS || valid.size < 2) {
            return BatteryWidgetTrend.Insufficient(valid.firstOrNull()?.estimatedFullChargeCapacityMicroampHours)
        }
        val newest = valid.first().estimatedFullChargeCapacityMicroampHours
            ?: return BatteryWidgetTrend.Insufficient(null)
        val oldest = valid.last().estimatedFullChargeCapacityMicroampHours
            ?: return BatteryWidgetTrend.Insufficient(newest)
        val change = (newest - oldest).toDouble() / oldest.toDouble() * 100.0
        return BatteryWidgetTrend.Ready(newest, change)
    }
}
