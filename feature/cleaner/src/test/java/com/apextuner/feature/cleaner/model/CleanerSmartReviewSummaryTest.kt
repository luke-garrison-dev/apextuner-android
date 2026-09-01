package com.apextuner.feature.cleaner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanerSmartReviewSummaryTest {
    @Test
    fun aliasRepresentationsAreNotDoubleCounted() {
        val media = item("media", CleanerOrigin.MediaStore, physicalKey = "same", bytes = 1_000L)
        val safAlias = item("saf", CleanerOrigin.SafDocument, physicalKey = "same", bytes = 1_000L)
        val scan = scan(
            largeFiles = listOf(media, safAlias),
            suspected = listOf(media, safAlias),
        )

        val summary = scan.smartReviewSummary()

        assertEquals(1, summary.largeFiles)
        assertEquals(1_000L, summary.largeFileBytes)
        assertEquals(1, summary.suspectedCandidates)
        assertEquals(1_000L, summary.suspectedCandidateBytes)
        assertEquals(1, summary.compressibleMediaCandidates)
        assertEquals(1_000L, summary.compressibleMediaSourceBytes)
    }

    @Test
    fun byteTotalsSaturateInsteadOfOverflowingNegative() {
        val first = item("first", CleanerOrigin.MediaStore, physicalKey = "first", bytes = Long.MAX_VALUE)
        val second = item("second", CleanerOrigin.MediaStore, physicalKey = "second", bytes = 100L)

        val summary = scan(largeFiles = listOf(first, second)).smartReviewSummary()

        assertEquals(Long.MAX_VALUE, summary.largeFileBytes)
        assertEquals(Long.MAX_VALUE, summary.compressibleMediaSourceBytes)
    }

    @Test
    fun unfinishedDuplicateAnalysisIsReportedAsNotAnalyzed() {
        val summary = scan().smartReviewSummary()

        assertNull(summary.exactDuplicateBytes)
        assertNull(summary.photoReviewCandidateBytes)
    }

    private fun scan(
        largeFiles: List<CleanableItem> = emptyList(),
        suspected: List<CleanableItem> = emptyList(),
    ) = CleanerScanResult(
        items = (largeFiles + suspected).distinctBy { it.identityKey },
        duplicateGroups = emptyList(),
        duplicateAnalysisCompleted = false,
        largeFiles = largeFiles,
        suspectedJunk = suspected,
        categoryUsage = emptyList(),
        totalAccessibleBytes = 0L,
        potentialReclaimBytes = 0L,
        skippedItems = 0,
        truncated = false,
        cacheInsight = CacheInsight(bytes = null, available = false),
    )

    private fun item(
        key: String,
        origin: CleanerOrigin,
        physicalKey: String,
        bytes: Long,
    ) = CleanableItem(
        key = key,
        uri = "content://$key",
        origin = origin,
        category = CleanerCategory.Video,
        displayName = "$key.mp4",
        mimeType = "video/mp4",
        sizeBytes = bytes,
        modifiedAtEpochMillis = null,
        canDelete = true,
        canWrite = true,
        physicalKey = physicalKey,
    )
}
