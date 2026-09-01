package com.apextuner.feature.network.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkQualityStatisticsTest {
    @Test
    fun summarizeComputesStableLatencyStatistics() {
        val summary = NetworkQualityStatistics.summarize(listOf(10, 20, 30, 40, 50))
        assertEquals(10L, summary.minimum)
        assertEquals(30L, summary.median)
        assertEquals(30.0, summary.average!!, 0.001)
        assertEquals(50L, summary.p95)
        assertEquals(10.0, summary.jitter!!, 0.001)
    }

    @Test
    fun summarizeHandlesNoSuccessfulSamples() {
        val summary = NetworkQualityStatistics.summarize(emptyList())
        assertNull(summary.minimum)
        assertNull(summary.median)
        assertNull(summary.average)
        assertNull(summary.p95)
        assertNull(summary.jitter)
    }

    @Test
    fun dualStackPlanKeepsTotalBudgetAndExercisesBothFamilies() {
        val plan = NetworkQualityProbePlanner.plan(
            totalAttempts = 7,
            resolverPreferred = NetworkQualityProbePlanner.Family.IPv6,
            hasIpv4 = true,
            hasIpv6 = true,
        )
        assertEquals(7, plan.size)
        assertEquals(NetworkQualityProbePlanner.Family.IPv6, plan.first())
        assertEquals(4, plan.count { it == NetworkQualityProbePlanner.Family.IPv6 })
        assertEquals(3, plan.count { it == NetworkQualityProbePlanner.Family.IPv4 })
    }

    @Test
    fun singleStackPlanUsesOnlyAvailableFamily() {
        val plan = NetworkQualityProbePlanner.plan(
            totalAttempts = 8,
            resolverPreferred = null,
            hasIpv4 = true,
            hasIpv6 = false,
        )
        assertEquals(8, plan.size)
        assertTrue(plan.all { it == NetworkQualityProbePlanner.Family.IPv4 })
    }

    @Test
    fun preferredFamilyFallsBackToWorkingRouteThenLatency() {
        assertEquals(
            NetworkQualityProbePlanner.Family.IPv4,
            NetworkQualityProbePlanner.preferred(4, 4, 30, 4, 0, null, NetworkQualityProbePlanner.Family.IPv6),
        )
        assertEquals(
            NetworkQualityProbePlanner.Family.IPv6,
            NetworkQualityProbePlanner.preferred(4, 4, 40, 4, 4, 20, NetworkQualityProbePlanner.Family.IPv4),
        )
        assertEquals(
            NetworkQualityProbePlanner.Family.IPv4,
            NetworkQualityProbePlanner.preferred(4, 4, 50, 4, 1, 10, NetworkQualityProbePlanner.Family.IPv6),
        )
    }
}
