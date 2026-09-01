package com.apextuner.core.system

import com.apextuner.core.model.CpuUsageAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuUsageTrackerTest {

    @Test
    fun restrictedPlatformIsReportedWithoutFabricatingUsage() {
        val tracker = CpuUsageTracker()

        val result = tracker.update(CpuCounterRead.RestrictedByPlatform)

        assertNull(result.percent)
        assertEquals(CpuUsageAvailability.RestrictedByPlatform, result.availability)
    }

    @Test
    fun firstReadableCounterSampleWarmsUpBeforeReportingUsage() {
        val tracker = CpuUsageTracker()

        val result = tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))

        assertNull(result.percent)
        assertEquals(CpuUsageAvailability.Sampling, result.availability)
    }

    @Test
    fun readableCounterDeltaProducesSystemCpuUsage() {
        val tracker = CpuUsageTracker()
        tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))

        val result = tracker.update(success(uptimeMillis = 2_000L, idle = 650L, total = 1_200L))

        assertEquals(75.0, result.percent!!, 0.0001)
        assertEquals(CpuUsageAvailability.Available, result.availability)
    }

    @Test
    fun rapidSecondConsumerReusesFreshUsageInsteadOfFlickeringUnavailable() {
        val tracker = CpuUsageTracker()
        tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))
        tracker.update(success(uptimeMillis = 2_000L, idle = 650L, total = 1_200L))

        val rapidResult = tracker.update(success(uptimeMillis = 2_100L, idle = 655L, total = 1_220L))
        val nextResult = tracker.update(success(uptimeMillis = 3_000L, idle = 700L, total = 1_400L))

        assertEquals(75.0, rapidResult.percent!!, 0.0001)
        assertEquals(CpuUsageAvailability.Available, rapidResult.availability)
        assertEquals(75.0, nextResult.percent!!, 0.0001)
        assertEquals(CpuUsageAvailability.Available, nextResult.availability)
    }

    @Test
    fun longSamplingGapResetsBaselineInsteadOfReportingStaleUsage() {
        val tracker = CpuUsageTracker()
        tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))
        tracker.update(success(uptimeMillis = 2_000L, idle = 650L, total = 1_200L))

        val stale = tracker.update(success(uptimeMillis = 20_000L, idle = 1_000L, total = 2_000L))
        val recovered = tracker.update(success(uptimeMillis = 21_000L, idle = 1_050L, total = 2_200L))

        assertNull(stale.percent)
        assertEquals(CpuUsageAvailability.Sampling, stale.availability)
        assertEquals(75.0, recovered.percent!!, 0.0001)
        assertEquals(CpuUsageAvailability.Available, recovered.availability)
    }

    @Test
    fun monotonicClockRegressionDropsCachedUsageAndRestartsSampling() {
        val tracker = CpuUsageTracker()
        tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))
        tracker.update(success(uptimeMillis = 2_000L, idle = 650L, total = 1_200L))

        val regressed = tracker.update(success(uptimeMillis = 1_500L, idle = 670L, total = 1_260L))
        val rapid = tracker.update(success(uptimeMillis = 1_600L, idle = 675L, total = 1_280L))

        assertNull(regressed.percent)
        assertEquals(CpuUsageAvailability.Sampling, regressed.availability)
        assertNull(rapid.percent)
        assertEquals(CpuUsageAvailability.Sampling, rapid.availability)
    }

    @Test
    fun unavailableReadResetsSamplingBaseline() {
        val tracker = CpuUsageTracker()
        tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))

        val unavailable = tracker.update(CpuCounterRead.Unavailable)
        val afterRecovery = tracker.update(success(uptimeMillis = 2_000L, idle = 650L, total = 1_200L))

        assertNull(unavailable.percent)
        assertEquals(CpuUsageAvailability.Unavailable, unavailable.availability)
        assertNull(afterRecovery.percent)
        assertEquals(CpuUsageAvailability.Sampling, afterRecovery.availability)
    }

    @Test
    fun malformedCounterDeltaNeverProducesAUsagePercentage() {
        val tracker = CpuUsageTracker()
        tracker.update(success(uptimeMillis = 1_000L, idle = 600L, total = 1_000L))

        val result = tracker.update(success(uptimeMillis = 2_000L, idle = 590L, total = 1_200L))

        assertNull(result.percent)
        assertEquals(CpuUsageAvailability.Unavailable, result.availability)
    }

    private fun success(
        uptimeMillis: Long,
        idle: Long,
        total: Long,
    ) = CpuCounterRead.Success(
        counters = CpuCounters(idle = idle, total = total),
        uptimeMillis = uptimeMillis,
    )
}
