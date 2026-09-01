package com.apextuner.feature.contacts

import java.text.Normalizer
import kotlin.math.max

object ContactSimilarity {
    const val DEFAULT_THRESHOLD = 0.82
    private const val MAX_CONTACTS = 5_000
    private const val MAX_CANDIDATES = 5_000
    private const val MAX_COMPARISONS = 250_000

    fun normalizeName(value: String): String =
        Normalizer.normalize(value.lowercase().trim(), Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .replace(NON_ALNUM, " ")
            .replace(WHITESPACE, " ")
            .trim()

    fun normalizePhone(value: String): String {
        val trimmed = value.trim()
        val prefix = if (trimmed.startsWith("+")) "+" else ""
        return prefix + trimmed.filter(Char::isDigit)
    }

    fun normalizeEmail(value: String): String = value.trim().lowercase()

    fun score(a: ContactRecord, b: ContactRecord): Pair<Double, String> {
        val normalizedNameA = normalizeName(a.displayName)
        val normalizedNameB = normalizeName(b.displayName)
        val editSimilarity = normalizedLevenshtein(normalizedNameA, normalizedNameB)
        val shortNameVariation = isShortPrefixVariation(normalizedNameA, normalizedNameB)
        val names = if (shortNameVariation) max(DEFAULT_THRESHOLD, editSimilarity) else editSimilarity
        val phonesA = a.phones.map(::normalizePhone).filter { it.length >= 7 }.toSet()
        val phonesB = b.phones.map(::normalizePhone).filter { it.length >= 7 }.toSet()
        val phoneExact = phonesA.intersect(phonesB).isNotEmpty()
        val phoneSuffix = phonesA.any { left ->
            phonesB.any { right -> left.takeLast(7) == right.takeLast(7) }
        }
        val emailsA = a.emails.map(::normalizeEmail).filter { it.contains('@') }.toSet()
        val emailsB = b.emails.map(::normalizeEmail).filter { it.contains('@') }.toSet()
        val emailExact = emailsA.intersect(emailsB).isNotEmpty()

        return when {
            phoneExact && emailExact -> 1.0 to "Same phone and email"
            phoneExact -> max(0.98, names) to "Same normalized phone"
            emailExact -> max(0.97, names) to "Same email"
            phoneSuffix -> max(0.90, names) to "Matching phone suffix"
            shortNameVariation -> names to "Short-name variation"
            else -> names to "Similar name"
        }
    }

    fun findCandidates(
        contacts: List<ContactRecord>,
        threshold: Double = DEFAULT_THRESHOLD,
    ): List<ContactDuplicateCandidate> {
        require(threshold in 0.0..1.0)
        val bounded = contacts.take(MAX_CONTACTS)
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        bounded.forEachIndexed { index, contact ->
            blockingKeys(contact).forEach { key -> buckets.getOrPut(key) { ArrayList() }.add(index) }
        }
        val compared = HashSet<Long>()
        val results = ArrayList<ContactDuplicateCandidate>()
        for (indices in buckets.values) {
            for (i in 0 until indices.size) {
                for (j in i + 1 until indices.size) {
                    val firstIndex = indices[i]
                    val secondIndex = indices[j]
                    val low = minOf(firstIndex, secondIndex)
                    val high = maxOf(firstIndex, secondIndex)
                    val pairKey = (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
                    if (!compared.add(pairKey)) continue
                    if (compared.size > MAX_COMPARISONS) {
                        return sortCandidates(results)
                    }
                    val first = bounded[firstIndex]
                    val second = bounded[secondIndex]
                    val (score, reason) = score(first, second)
                    if (score >= threshold) {
                        results += ContactDuplicateCandidate(first, second, score, reason)
                        if (results.size >= MAX_CANDIDATES) {
                            return sortCandidates(results)
                        }
                    }
                }
            }
        }
        return sortCandidates(results)
    }

    fun normalizedLevenshtein(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
            }
            current.copyInto(previous)
        }
        val distance = previous[b.length]
        return 1.0 - distance.toDouble() / max(a.length, b.length).toDouble()
    }

    private fun sortCandidates(results: List<ContactDuplicateCandidate>): List<ContactDuplicateCandidate> =
        results.sortedWith(
            compareByDescending<ContactDuplicateCandidate> { it.score }
                .thenBy { it.first.displayName.lowercase() }
                .thenBy { it.second.displayName.lowercase() },
        )

    private fun blockingKeys(contact: ContactRecord): Set<String> {
        val keys = LinkedHashSet<String>()
        val name = normalizeName(contact.displayName).replace(" ", "")
        if (name.isNotEmpty()) {
            keys += "n:${name.take(3)}"
            if (name.length <= SHORT_NAME_MAX_LENGTH) {
                for (prefixLength in MIN_SHORT_NAME_LENGTH..name.length) {
                    keys += "sn:${name.take(prefixLength)}"
                }
            }
        }
        contact.phones.map(::normalizePhone).filter { it.length >= 7 }.forEach { keys += "p:${it.takeLast(7)}" }
        contact.emails.map(::normalizeEmail).filter { it.contains('@') }.forEach { keys += "e:$it" }
        return keys
    }

    private fun isShortPrefixVariation(first: String, second: String): Boolean {
        val compactFirst = first.replace(" ", "")
        val compactSecond = second.replace(" ", "")
        if (compactFirst == compactSecond) return false
        val shorter = if (compactFirst.length <= compactSecond.length) compactFirst else compactSecond
        val longer = if (compactFirst.length > compactSecond.length) compactFirst else compactSecond
        return shorter.length >= MIN_SHORT_NAME_LENGTH &&
            longer.length <= SHORT_NAME_MAX_LENGTH &&
            longer.length - shorter.length == 1 &&
            longer.startsWith(shorter)
    }

    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_SHORT_NAME_LENGTH = 2
    private const val SHORT_NAME_MAX_LENGTH = 4
}
