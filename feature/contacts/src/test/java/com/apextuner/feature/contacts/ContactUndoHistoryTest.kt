package com.apextuner.feature.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactUndoHistoryTest {
    private fun snapshot(name: String) = ContactMergeUndo(
        firstDisplayName = name,
        secondDisplayName = "$name-2",
        rules = listOf(AggregationRuleSnapshot(name.hashCode().toLong(), name.hashCode().toLong() + 1L, null)),
    )

    @Test
    fun failedTopCanBeDiscardedWithoutLosingOlderUndo() {
        val history = ContactUndoHistory()
        val older = snapshot("older")
        val failing = snapshot("failing")
        history.push(older)
        history.push(failing)
        history.markFailed(failing)

        assertTrue(history.topFailed)
        assertEquals(failing, history.discardFailedTop())
        assertTrue(history.hasUndo)
        assertFalse(history.topFailed)
        assertEquals(older, history.peek())
    }

    @Test
    fun onlyFailedTopCanBeDiscarded() {
        val history = ContactUndoHistory()
        val item = snapshot("normal")
        history.push(item)

        assertNull(history.discardFailedTop())
        assertEquals(item, history.peek())
    }

    @Test
    fun newerUndoCanCompleteAbovePreviouslyFailedUndo() {
        val history = ContactUndoHistory()
        val failed = snapshot("failed")
        val newer = snapshot("newer")
        history.push(failed)
        history.markFailed(failed)
        history.push(newer)

        assertFalse(history.topFailed)
        assertTrue(history.complete(newer))
        assertTrue(history.topFailed)
        assertEquals(failed, history.peek())
    }
}
