package com.apextuner.feature.settings.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorRateCalculatorTest {
    @Test fun derivesRatesAndRejectsCounterReset() {
        val first = MonitorRateCalculator.rates(null, 1_000L, 100L, 200L)
        assertNull(first.second.rxBytesPerSecond)
        val second = MonitorRateCalculator.rates(first.first, 3_000L, 2_100L, 1_200L)
        assertEquals(1_000L, second.second.rxBytesPerSecond)
        assertEquals(500L, second.second.txBytesPerSecond)
        val reset = MonitorRateCalculator.rates(second.first, 4_000L, 10L, 10L)
        assertNull(reset.second.rxBytesPerSecond)
        assertNull(reset.second.txBytesPerSecond)
    }


    @Test fun telemetryRetryBackoffIsBounded() {
        assertEquals(1_000L, MonitorRetryPolicy.delayMillis(0))
        assertEquals(2_000L, MonitorRetryPolicy.delayMillis(1))
        assertEquals(16_000L, MonitorRetryPolicy.delayMillis(4))
        assertEquals(30_000L, MonitorRetryPolicy.delayMillis(5))
        assertEquals(30_000L, MonitorRetryPolicy.delayMillis(100))
    }
}
