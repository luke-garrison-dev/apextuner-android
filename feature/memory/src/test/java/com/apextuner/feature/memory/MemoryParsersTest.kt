package com.apextuner.feature.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryParsersTest {
    @Test fun parsesSwap() {
        val parsed = MemoryParsers.parseSwap("SwapTotal: 1024 kB\nSwapFree: 256 kB\n")!!
        assertEquals(1024L * 1024L, parsed.totalBytes)
        assertEquals(256L * 1024L, parsed.freeBytes)
    }

    @Test fun rejectsImpossibleSwap() {
        assertNull(MemoryParsers.parseSwap("SwapTotal: 10 kB\nSwapFree: 20 kB\n"))
    }

    @Test fun parsesPsi() {
        assertEquals(2.5, MemoryParsers.parsePressureSomeAvg10("some avg10=2.50 avg60=1.0 avg300=0.5 total=12\n")!!, 0.0001)
    }
}
