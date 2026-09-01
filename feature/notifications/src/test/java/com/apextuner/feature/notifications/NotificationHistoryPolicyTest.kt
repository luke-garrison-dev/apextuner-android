package com.apextuner.feature.notifications

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationHistoryPolicyTest {
    @Test
    fun normalizeRetentionDays_acceptsOnlySupportedWindows() {
        NotificationHistoryPolicy.allowedRetentionDays.forEach { days ->
            assertEquals(days, NotificationHistoryPolicy.normalizeRetentionDays(days))
        }
        assertEquals(
            NotificationHistoryPolicy.DEFAULT_RETENTION_DAYS,
            NotificationHistoryPolicy.normalizeRetentionDays(0),
        )
        assertEquals(
            NotificationHistoryPolicy.DEFAULT_RETENTION_DAYS,
            NotificationHistoryPolicy.normalizeRetentionDays(365),
        )
    }

    @Test
    fun retentionCutoff_usesNormalizedWindowAndFloorsAtEpoch() {
        val now = TimeUnit.DAYS.toMillis(20)
        assertEquals(
            TimeUnit.DAYS.toMillis(13),
            NotificationHistoryPolicy.retentionCutoffEpochMillis(now, 7),
        )
        assertEquals(
            0L,
            NotificationHistoryPolicy.retentionCutoffEpochMillis(
                TimeUnit.HOURS.toMillis(12),
                1,
            ),
        )
        assertEquals(
            TimeUnit.DAYS.toMillis(13),
            NotificationHistoryPolicy.retentionCutoffEpochMillis(now, 999),
        )
    }

    @Test
    fun sanitizePackageName_rejectsUnsafeOrUnusableValues() {
        assertEquals("com.example.app", NotificationHistoryPolicy.sanitizePackageName(" com.example.app "))
        assertNull(NotificationHistoryPolicy.sanitizePackageName(""))
        assertEquals("android", NotificationHistoryPolicy.sanitizePackageName("android"))
        assertNull(NotificationHistoryPolicy.sanitizePackageName("com.example bad"))
        assertNull(NotificationHistoryPolicy.sanitizePackageName("com/example/app"))
        assertNull(NotificationHistoryPolicy.sanitizePackageName("singleSegment"))
        assertNull(NotificationHistoryPolicy.sanitizePackageName("com..example"))
        assertNull(NotificationHistoryPolicy.sanitizePackageName("1com.example"))
        assertNull(
            NotificationHistoryPolicy.sanitizePackageName(
                "a.".padEnd(NotificationHistoryPolicy.MAX_PACKAGE_NAME_CHARS + 1, 'x'),
            ),
        )
    }

    @Test
    fun sanitizeText_removesNulTrimsAndBoundsSensitivePayloads() {
        assertEquals("hello world", NotificationHistoryPolicy.sanitizeTitle("  hello\u0000world  "))
        assertEquals(
            NotificationHistoryPolicy.MAX_TITLE_CHARS,
            NotificationHistoryPolicy.sanitizeTitle(
                "x".repeat(NotificationHistoryPolicy.MAX_TITLE_CHARS + 500),
            ).length,
        )
        assertEquals(
            NotificationHistoryPolicy.MAX_TEXT_CHARS,
            NotificationHistoryPolicy.sanitizeBody(
                "y".repeat(NotificationHistoryPolicy.MAX_TEXT_CHARS + 500),
            ).length,
        )
    }



    @Test
    fun sanitizeText_boundsCharSequenceBeforeStringMaterialization() {
        val large = BoundedProbeCharSequence(NotificationHistoryPolicy.MAX_TEXT_CHARS * 100)

        val sanitized = NotificationHistoryPolicy.sanitizeBody(large)

        assertEquals(NotificationHistoryPolicy.MAX_TEXT_CHARS, sanitized.length)
        assertEquals(NotificationHistoryPolicy.MAX_TEXT_CHARS, large.maxRequestedEnd)
        assertEquals(0, large.toStringCalls)
    }

    @Test
    fun sanitizeMutedPackages_removesOwnInvalidAndCapsSet() {
        val own = "com.apextuner.app"
        val input = buildSet {
            add(own)
            add("invalid")
            repeat(2_500) { index -> add("com.example.app$index") }
        }
        val sanitized = NotificationHistoryPolicy.sanitizeMutedPackages(input, own)

        assertFalse(own in sanitized)
        assertFalse("invalid" in sanitized)
        assertEquals(2_000, sanitized.size)
    }

    @Test
    fun collectionAllowed_allBooleanCombinationsRequireEveryGate() {
        var allowedStates = 0

        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { premium ->
                listOf(false, true).forEach { accessGranted ->
                    val actual = NotificationHistoryPolicy.collectionAllowed(
                        settings = NotificationHistorySettings(enabled = enabled),
                        premium = premium,
                        notificationAccessGranted = accessGranted,
                    )
                    val expected = enabled && premium && accessGranted
                    assertEquals(expected, actual)
                    if (actual) allowedStates += 1
                }
            }
        }

        assertEquals(1, allowedStates)
    }


    private class BoundedProbeCharSequence(
        override val length: Int,
    ) : CharSequence {
        var maxRequestedEnd: Int = 0
        var toStringCalls: Int = 0

        override fun get(index: Int): Char = 'x'

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            maxRequestedEnd = maxOf(maxRequestedEnd, endIndex)
            return "x".repeat(endIndex - startIndex)
        }

        override fun toString(): String {
            toStringCalls += 1
            error("The full CharSequence must not be materialized.")
        }
    }
}
