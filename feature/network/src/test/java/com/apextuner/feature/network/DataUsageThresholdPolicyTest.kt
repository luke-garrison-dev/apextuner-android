package com.apextuner.feature.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataUsageThresholdPolicyTest {
    @Test
    fun crossingFiresOnlyWhenMovingFromBelowToAtOrAboveThreshold() {
        val threshold = 100L
        assertTrue(DataUsageThresholdPolicy.crossed(threshold, 100L, DataUsageObservation("2026-08", 99L), "2026-08"))
        assertFalse(DataUsageThresholdPolicy.crossed(threshold, 150L, DataUsageObservation("2026-08", 120L), "2026-08"))
        assertFalse(DataUsageThresholdPolicy.crossed(threshold, 99L, DataUsageObservation("2026-08", 80L), "2026-08"))
    }

    @Test
    fun newMonthCanCrossAgain() {
        assertTrue(
            DataUsageThresholdPolicy.crossed(
                thresholdBytes = 1_000L,
                currentBytes = 1_200L,
                previous = DataUsageObservation("2026-07", 5_000L),
                currentPeriodKey = "2026-08",
            ),
        )
    }

    @Test
    fun codecDropsMalformedEntries() {
        val decoded = DataUsageCapCodec.decodeCaps(setOf("com.example.app|1048576", "bad|x", "../bad|5"))
        assertTrue(decoded["com.example.app"] == 1_048_576L)
        assertFalse(decoded.containsKey("../bad"))
    }
}
