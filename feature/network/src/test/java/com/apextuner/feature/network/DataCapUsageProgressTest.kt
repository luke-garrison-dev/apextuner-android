package com.apextuner.feature.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataCapUsageProgressTest {
    @Test fun reportsUsageAgainstCapIncludingOverage() {
        assertEquals(50.0, dataCapUsagePercent(500L, 1_000L)!!, 0.0001)
        assertEquals(125.0, dataCapUsagePercent(1_250L, 1_000L)!!, 0.0001)
    }

    @Test fun rejectsUnavailableOrInvalidCap() {
        assertNull(dataCapUsagePercent(null, 1_000L))
        assertNull(dataCapUsagePercent(10L, null))
        assertNull(dataCapUsagePercent(10L, 0L))
    }

    @Test fun negativeUsageCannotProduceNegativeProgress() {
        assertEquals(0.0, dataCapUsagePercent(-1L, 1_000L)!!, 0.0001)
    }
}
