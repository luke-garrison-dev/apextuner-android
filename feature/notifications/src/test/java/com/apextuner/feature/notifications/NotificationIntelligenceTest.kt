package com.apextuner.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationIntelligenceTest {
    @Test
    fun emptyHistoryProducesAnEmptyInsightWithoutFabrication() {
        val result = analyzeNotificationHistory(emptyList())

        assertEquals(0, result.sampleCount)
        assertEquals(0, result.uniqueApps)
        assertEquals(emptyList<NotificationAppCount>(), result.topApps)
        assertNull(result.busiestHour)
        assertNull(result.increasingPackage)
        assertFalse(result.sampleLimitReached)
    }

    @Test
    fun rankingUsesOnlyLocallyCapturedHistoryCounts() {
        val items = buildList {
            repeat(20) { index -> add(item(index.toLong(), "com.example.alpha")) }
            repeat(10) { index -> add(item((20 + index).toLong(), "com.example.beta")) }
        }

        val result = analyzeNotificationHistory(items)

        assertEquals(30, result.sampleCount)
        assertEquals(2, result.uniqueApps)
        assertEquals(NotificationAppCount("com.example.alpha", 20), result.topApps.first())
    }

    @Test
    fun sampleLimitFlagExplainsBoundedVisibleDataset() {
        val items = List(NotificationHistoryPolicy.MAX_VISIBLE_ITEMS) { index ->
            item(index.toLong(), "com.example.app")
        }

        val result = analyzeNotificationHistory(items)

        assertEquals(true, result.sampleLimitReached)
    }

    private fun item(id: Long, packageName: String) = NotificationHistoryItem(
        id = id,
        packageName = packageName,
        title = "Title",
        text = "Text",
        postedAtEpochMillis = BASE_TIME + id * 60_000L,
    )

    private companion object {
        const val BASE_TIME = 1_800_000_000_000L
    }
}
