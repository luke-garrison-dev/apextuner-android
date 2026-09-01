package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import com.apextuner.feature.cleaner.model.MediaReencodeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReencodeEstimatorTest {

    @Test
    fun `balanced jpeg estimate preserves aspect ratio and predicts savings`() {
        val item = item(
            category = CleanerCategory.Image,
            mimeType = "image/jpeg",
            displayName = "camera.jpg",
            sizeBytes = 20L * 1024L * 1024L,
            width = 4_000,
            height = 3_000,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Balanced)

        assertTrue(estimate.supported)
        assertEquals(1_920, estimate.targetWidth)
        assertEquals(1_440, estimate.targetHeight)
        assertEquals("image/jpeg", estimate.outputMimeType)
        assertEquals("jpg", estimate.outputExtension)
        assertTrue(estimate.estimatedOutputBytes in 1 until item.sizeBytes)
        assertTrue(estimate.estimatedSavingsBytes > 0L)
        assertTrue(estimate.estimatedSavingsPercent in 1..100)
        assertTrue(estimate.replaceAvailable)
    }

    @Test
    fun `png estimate downscales but does not claim lossy quality savings`() {
        val item = item(
            category = CleanerCategory.Image,
            mimeType = "image/png",
            displayName = "diagram.png",
            sizeBytes = 12_000_000L,
            width = 2_560,
            height = 1_440,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Compact)

        assertEquals(1_280, estimate.targetWidth)
        assertEquals(720, estimate.targetHeight)
        assertEquals("image/png", estimate.outputMimeType)
        assertEquals(2_880_000L, estimate.estimatedOutputBytes)
    }

    @Test
    fun `compact video estimate uses bounded bitrate and duration`() {
        val item = item(
            category = CleanerCategory.Video,
            mimeType = "video/mp4",
            displayName = "clip.mp4",
            sizeBytes = 80_000_000L,
            width = 3_840,
            height = 2_160,
            durationMillis = 60_000L,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Compact)

        assertTrue(estimate.supported)
        assertEquals(1_280, estimate.targetWidth)
        assertEquals(720, estimate.targetHeight)
        assertEquals(12_364_800L, estimate.estimatedOutputBytes)
        assertEquals("video/mp4", estimate.outputMimeType)
        assertTrue(estimate.replaceAvailable)
    }

    @Test
    fun `non mp4 video can save copy but cannot replace container in place`() {
        val item = item(
            category = CleanerCategory.Video,
            mimeType = "video/webm",
            displayName = "clip.webm",
            sizeBytes = 50_000_000L,
            width = 1_920,
            height = 1_080,
            durationMillis = 30_000L,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Balanced)

        assertTrue(estimate.supported)
        assertTrue(estimate.copyAvailable)
        assertFalse(estimate.replaceAvailable)
        assertTrue(estimate.replaceUnavailableReason.orEmpty().contains("container"))
    }

    @Test
    fun `animated or unsupported image format is rejected instead of flattened`() {
        val item = item(
            category = CleanerCategory.Image,
            mimeType = "image/gif",
            displayName = "animation.gif",
            sizeBytes = 15_000_000L,
            width = 1_200,
            height = 900,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Balanced)

        assertFalse(estimate.supported)
        assertFalse(estimate.copyAvailable)
        assertFalse(estimate.replaceAvailable)
        assertEquals(0L, estimate.estimatedSavingsBytes)
    }

    @Test
    fun `video without duration is unavailable instead of fabricating a size`() {
        val item = item(
            category = CleanerCategory.Video,
            mimeType = "video/mp4",
            displayName = "unknown.mp4",
            sizeBytes = 50_000_000L,
            width = 1_920,
            height = 1_080,
            durationMillis = null,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Balanced)

        assertFalse(estimate.supported)
        assertTrue(estimate.unavailableReason.orEmpty().contains("duration"))
        assertEquals(0L, estimate.estimatedOutputBytes)
    }

    @Test
    fun `small already efficient video reports zero estimated reduction`() {
        val item = item(
            category = CleanerCategory.Video,
            mimeType = "video/mp4",
            displayName = "efficient.mp4",
            sizeBytes = 1_000_000L,
            width = 1_280,
            height = 720,
            durationMillis = 60_000L,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Compact)

        assertTrue(estimate.estimatedOutputBytes > item.sizeBytes)
        assertEquals(0L, estimate.estimatedSavingsBytes)
        assertEquals(0, estimate.estimatedSavingsPercent)
    }

    @Test
    fun `fit within never exceeds requested long edge and returns even dimensions`() {
        val (width, height) = MediaReencodeEstimator.fitWithin(
            width = 4_033,
            height = 3_025,
            maxLongEdge = 1_920,
        )

        assertTrue(width <= 1_920)
        assertTrue(height <= 1_920)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertTrue(width > height)
    }

    @Test
    fun `rollback snapshot remains cancellable but destructive commit does not`() {
        assertTrue(
            MediaReencodeProgress(
                MediaReencodeProgress.Phase.SnapshottingOriginal,
                0.5f,
            ).cancellable,
        )
        assertFalse(
            MediaReencodeProgress(
                MediaReencodeProgress.Phase.ReplacingOriginal,
                0f,
            ).cancellable,
        )
        assertFalse(
            MediaReencodeProgress(
                MediaReencodeProgress.Phase.Verifying,
                null,
            ).cancellable,
        )
    }

    @Test
    fun `huge source values are handled without long overflow`() {
        val item = item(
            category = CleanerCategory.Image,
            mimeType = "image/jpeg",
            displayName = "huge.jpg",
            sizeBytes = Long.MAX_VALUE,
            width = Int.MAX_VALUE,
            height = Int.MAX_VALUE,
        )

        val estimate = MediaReencodeEstimator.estimate(item, MediaReencodePreset.Compact)

        assertTrue(estimate.supported)
        assertTrue(estimate.estimatedOutputBytes in 1..Long.MAX_VALUE)
        assertTrue(estimate.estimatedSavingsBytes >= 0L)
    }

    private fun item(
        category: CleanerCategory,
        mimeType: String?,
        displayName: String,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
        durationMillis: Long? = null,
    ): CleanableItem = CleanableItem(
        key = "test:$displayName",
        uri = "content://test/$displayName",
        origin = CleanerOrigin.MediaStore,
        category = category,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = null,
        width = width,
        height = height,
        durationMillis = durationMillis,
        canDelete = true,
    )
}
