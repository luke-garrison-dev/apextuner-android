package com.apextuner.feature.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSimilarityTest {
    @Test
    fun samePhoneProducesHighConfidenceDespiteNameVariation() {
        val a = record(1, "José Alvarez", phone = "+39 333 123 4567")
        val b = record(2, "Jose Alvares", phone = "3331234567")
        val (score, reason) = ContactSimilarity.score(a, b)
        assertTrue(score >= 0.90)
        assertTrue(reason.contains("phone", ignoreCase = true))
    }

    @Test
    fun sameEmailProducesHighConfidence() {
        val a = record(1, "Alice Example", email = "Alice@Example.com")
        val b = record(2, "A. Example", email = " alice@example.com ")
        val (score, _) = ContactSimilarity.score(a, b)
        assertTrue(score >= 0.97)
    }

    @Test
    fun unrelatedPeopleStayBelowThreshold() {
        val a = record(1, "Alice Example", phone = "1111111111")
        val b = record(2, "Zbigniew Kowalski", phone = "9999999999")
        val (score, _) = ContactSimilarity.score(a, b)
        assertTrue(score < ContactSimilarity.DEFAULT_THRESHOLD)
    }

    @Test
    fun candidateFinderDeduplicatesPairsAcrossBlockingKeys() {
        val a = record(1, "Mario Rossi", phone = "3331234567", email = "mario@example.com")
        val b = record(2, "Mario Rossi", phone = "3331234567", email = "mario@example.com")
        assertEquals(1, ContactSimilarity.findCandidates(listOf(a, b)).size)
    }

    @Test
    fun shortPrefixNameVariationIsIncludedForManualReview() {
        val a = record(1, "Al")
        val b = record(2, "Ali")

        val candidates = ContactSimilarity.findCandidates(listOf(a, b))

        assertEquals(1, candidates.size)
        assertEquals("Short-name variation", candidates.single().reason)
    }

    @Test
    fun unrelatedShortNamesRemainExcluded() {
        val a = record(1, "Al")
        val b = record(2, "Bo")

        assertTrue(ContactSimilarity.findCandidates(listOf(a, b)).isEmpty())
    }

    private fun record(id: Long, name: String, phone: String? = null, email: String? = null) =
        ContactRecord(
            contactId = id,
            displayName = name,
            phones = setOfNotNull(phone),
            emails = setOfNotNull(email),
            rawContactIds = setOf(id * 10),
        )
}
