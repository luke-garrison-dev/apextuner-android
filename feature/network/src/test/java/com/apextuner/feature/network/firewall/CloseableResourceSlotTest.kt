package com.apextuner.feature.network.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class CloseableResourceSlotTest {
    private class FakeCloseable : Closeable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test
    fun obsoleteCloseDoesNotTouchReplacement() {
        val slot = CloseableResourceSlot<FakeCloseable>()
        val first = FakeCloseable()
        val second = FakeCloseable()
        slot.replace(first)
        slot.replace(second)
        assertEquals(1, first.closes.get())
        assertSame(second, slot.currentOrNull())

        slot.close(first)
        assertSame(second, slot.currentOrNull())
        assertEquals(1, first.closes.get())
        assertEquals(0, second.closes.get())

        slot.close(second)
        assertNull(slot.currentOrNull())
        assertEquals(1, second.closes.get())
    }
}
