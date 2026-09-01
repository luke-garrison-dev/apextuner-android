package com.apextuner.feature.network.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkThroughputPolicyTest {
    @Test
    fun boundedPayloadBudgetCannotExceedFiveMebibytes() {
        assertEquals(4L * 1024L * 1024L, NetworkThroughputPolicy.DOWNLOAD_BYTES)
        assertEquals(1L * 1024L * 1024L, NetworkThroughputPolicy.UPLOAD_BYTES)
        assertEquals(5L * 1024L * 1024L, NetworkThroughputPolicy.MAX_TRANSFER_BYTES)
    }

    @Test
    fun throughputUsesElapsedNanosecondsAndSiMegabits() {
        val result = NetworkThroughputPolicy.megabitsPerSecond(4L * 1024L * 1024L, 1_000_000_000L)

        assertEquals(33.554432, result!!, 0.000001)
        assertNull(NetworkThroughputPolicy.megabitsPerSecond(0L, 1L))
        assertNull(NetworkThroughputPolicy.megabitsPerSecond(1L, 0L))
    }

    @Test
    fun assessmentNeverPretendsThroughputIsAvailableWhenBothDirectionsFail() {
        assertTrue(NetworkThroughputPolicy.assessment(null, null).contains("could not be measured"))
    }
}
