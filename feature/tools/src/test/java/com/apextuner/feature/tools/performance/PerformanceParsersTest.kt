package com.apextuner.feature.tools.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceParsersTest {
    @Test fun parsesSelectedScheduler() {
        assertEquals("mq-deadline", PerformanceParsers.selectedScheduler("none [mq-deadline] kyber bfq"))
    }

    @Test fun rejectsUnsafeKernelToken() {
        assertNull(PerformanceParsers.safeKernelToken("cubic\nmalicious value"))
        assertEquals("bbr", PerformanceParsers.safeKernelToken("bbr\n"))
    }
}
