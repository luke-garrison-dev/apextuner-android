package com.apextuner.feature.battery

import com.apextuner.core.database.BatteryHealthSnapshotEntity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHealthSnapshotPolicyTest {
    @Test
    fun sameLocalDayProducesSamePrimaryKey() {
        val zone = ZoneId.of("Europe/Rome")
        val first = java.time.ZonedDateTime.of(2026, 8, 29, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        val second = java.time.ZonedDateTime.of(2026, 8, 29, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(
            BatteryHealthSnapshotPolicy.epochDay(first, zone),
            BatteryHealthSnapshotPolicy.epochDay(second, zone),
        )
    }

    @Test
    fun fullChargeEstimateUsesExistingChargeCounterWithoutFabricatingMissingSignal() {
        assertEquals(
            4_000_000L,
            BatteryHealthSnapshotPolicy.estimateFullChargeCapacityMicroampHours(2_000_000L, 50),
        )
        assertEquals(null, BatteryHealthSnapshotPolicy.estimateFullChargeCapacityMicroampHours(null, 50))
        assertEquals(null, BatteryHealthSnapshotPolicy.estimateFullChargeCapacityMicroampHours(2_000_000L, 5))
    }

    @Test
    fun trendRemainsInsufficientBelowMinimumDays() {
        val rows = (1L..6L).map {
            BatteryHealthSnapshotEntity(it, it * 86_400_000L, null, 4_000_000L, 80)
        }
        val trend = BatteryHealthSnapshotPolicy.toTrend(rows)
        assertTrue(trend is BatteryHealthTrend.InsufficientHistory)
    }

    @Test
    fun duplicateEpochDaysAreDeduplicatedBeforeTrend() {
        val rows = listOf(
            BatteryHealthSnapshotEntity(1, 100, null, 4_000_000, 80),
            BatteryHealthSnapshotEntity(1, 200, null, 3_900_000, 80),
            BatteryHealthSnapshotEntity(2, 300, null, 3_800_000, 80),
        )
        val trend = BatteryHealthSnapshotPolicy.toTrend(rows) as BatteryHealthTrend.InsufficientHistory
        assertEquals(2, trend.daysAvailable)
    }
    @Test
    fun unsupportedTelemetryIsExplicitInsteadOfLookingLikeMissingHistory() {
        val rows = (1L..8L).map {
            BatteryHealthSnapshotEntity(it, it * 86_400_000L, null, null, 80)
        }
        val trend = BatteryHealthSnapshotPolicy.toTrend(rows)
        assertTrue(trend is BatteryHealthTrend.TelemetryUnavailable)
        assertEquals(8, (trend as BatteryHealthTrend.TelemetryUnavailable).daysObserved)
    }

}
