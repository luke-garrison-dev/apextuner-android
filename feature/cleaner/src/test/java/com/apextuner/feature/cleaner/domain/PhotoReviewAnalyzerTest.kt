package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.PhotoReviewGroupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoReviewAnalyzerTest {
    @Test
    fun screenshotAndRepeatedDownloadGroupsIncludeOriginals() {
        val day = 1_800_000_000_000L
        val items = listOf(
            image("Screenshot_001.jpg", "Pictures/Screenshots", day),
            image("Screenshot_002.jpg", "Pictures/Screenshots", day + 1_000L),
            image("holiday.jpg", "Download", day),
            image("holiday (1).jpg", "Download", day + 2_000L),
        )
        val groups = PhotoReviewAnalyzer.build(items, emptyList())
        assertTrue(groups.any { it.type == PhotoReviewGroupType.ScreenshotBatch && it.items.size == 2 })
        val download = groups.single { it.type == PhotoReviewGroupType.RepeatedDownload }
        assertEquals(setOf("holiday.jpg", "holiday (1).jpg"), download.items.map { it.displayName }.toSet())
    }

    @Test
    fun singletonDownloadsAreNotSurfaced() {
        val groups = PhotoReviewAnalyzer.build(listOf(image("unique.jpg", "Download", 10L)), emptyList())
        assertTrue(groups.none { it.type == PhotoReviewGroupType.RepeatedDownload })
    }

    private fun image(name: String, location: String, modified: Long) = CleanableItem(
        key = name,
        uri = "content://test/$name",
        origin = CleanerOrigin.MediaStore,
        category = CleanerCategory.Image,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = 1_000_000L,
        modifiedAtEpochMillis = modified,
        width = 1_920,
        height = 1_080,
        relativeLocation = location,
        canDelete = true,
    )
}
