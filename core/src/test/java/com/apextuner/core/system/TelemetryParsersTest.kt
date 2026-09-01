package com.apextuner.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryParsersTest {

    @Test
    fun parsesLinuxCpuAggregateWithoutDoubleCountingGuestFields() {
        val parsed = TelemetryParsers.parseCpuCounters(
            "cpu  100 20 50 700 30 5 10 5 40 10",
        )

        assertEquals(CpuCounters(idle = 730L, total = 920L), parsed)
    }

    @Test
    fun rejectsMalformedCpuData() {
        assertNull(TelemetryParsers.parseCpuCounters("cpu 10 nope 30 40"))
        assertNull(TelemetryParsers.parseCpuCounters("cpu0 10 20 30 40"))
    }

    @Test
    fun rejectsOverflowingCpuCounters() {
        assertNull(
            TelemetryParsers.parseCpuCounters(
                "cpu  1 1 1 ${Long.MAX_VALUE} 1 1 1 1",
            ),
        )
    }

    @Test
    fun computesCpuUsageFromCounterDeltas() {
        val previous = CpuCounters(idle = 600L, total = 1_000L)
        val current = CpuCounters(idle = 650L, total = 1_200L)

        assertEquals(75.0, TelemetryParsers.cpuUsagePercent(previous, current)!!, 0.0001)
    }

    @Test
    fun rejectsCounterRegression() {
        assertNull(
            TelemetryParsers.cpuUsagePercent(
                previous = CpuCounters(idle = 600L, total = 1_000L),
                current = CpuCounters(idle = 590L, total = 1_200L),
            ),
        )
    }

    @Test
    fun parsesCommonGpuFormatsAndRejectsInvalidPercentages() {
        assertEquals(25.0, TelemetryParsers.parseGpuUtilizationPercent("gpubusy", "25 100")!!, 0.0001)
        assertEquals(73.0, TelemetryParsers.parseGpuUtilizationPercent("load", "73@600000000Hz")!!, 0.0001)
        assertNull(TelemetryParsers.parseGpuUtilizationPercent("load", "150"))
        assertNull(TelemetryParsers.parseGpuUtilizationPercent("gpubusy", "10 0"))
    }
}
