package com.apextuner.feature.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactAggregationPlannerTest {
    @Test
    fun plansEveryPairBeyondOneProviderBatch() {
        val first = (1L..9L).toSet()
        val second = (101L..108L).toSet()

        val pairs = ContactAggregationPlanner.plan(first, second)

        assertEquals(72, pairs.size)
        assertTrue(1L to 101L in pairs)
        assertTrue(9L to 108L in pairs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedMergeInsteadOfSilentlyTruncatingIt() {
        ContactAggregationPlanner.plan(
            firstRawIds = (1L..23L).toSet(),
            secondRawIds = (101L..123L).toSet(),
        )
    }
}
