package com.apextuner.feature.settings.widget

import com.apextuner.core.database.BatteryHealthSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPoliciesTest {
    @Test fun storageFractionIsBoundedAndHandlesInvalidTotals() {
        assertEquals(0.0, StorageWidgetMath.usedFraction(0L, 0L), 0.0)
        assertEquals(0.75, StorageWidgetMath.usedFraction(100L, 25L), 0.0)
        assertEquals(1.0, StorageWidgetMath.usedFraction(100L, -1L), 0.0)
        assertEquals(0.0, StorageWidgetMath.usedFraction(100L, 200L), 0.0)
    }

    @Test fun batteryTrendRequiresSevenDistinctDays() {
        val six = (1L..6L).map { snapshot(it, 4_000_000L) }.reversed()
        assertTrue(BatteryWidgetTrendPolicy.evaluate(six) is BatteryWidgetTrend.Insufficient)
    }

    @Test fun batteryTrendUsesNewestAndOldestValidCapacity() {
        val snapshots = (1L..7L).map { day ->
            snapshot(day, 4_000_000L + day * 10_000L)
        }.reversed()
        val result = BatteryWidgetTrendPolicy.evaluate(snapshots) as BatteryWidgetTrend.Ready
        assertEquals(4_070_000L, result.latestCapacityMicroampHours)
        assertTrue(result.percentChange > 0.0)
    }

    private fun snapshot(day: Long, capacity: Long) = BatteryHealthSnapshotEntity(
        epochDay = day,
        capturedAtEpochMillis = day * 86_400_000L,
        cycleCount = null,
        estimatedFullChargeCapacityMicroampHours = capacity,
        sourceLevelPercent = 80,
    )
}
