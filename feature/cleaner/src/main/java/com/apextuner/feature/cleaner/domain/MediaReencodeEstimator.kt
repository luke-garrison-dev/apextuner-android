package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.MediaReencodeEstimate
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object MediaReencodeEstimator {
    private const val VIDEO_FRAME_RATE = 30.0
    private const val ASSUMED_AUDIO_BITRATE = 128_000L
    private const val MIN_VIDEO_BITRATE = 350_000L
    private const val MAX_VIDEO_BITRATE = 12_000_000L

    fun estimate(
        item: CleanableItem,
        preset: MediaReencodePreset,
    ): MediaReencodeEstimate {
        if (item.sizeBytes <= 0L) {
            return unavailable(item, "The source size is unavailable.")
        }
        val width = item.width?.takeIf { it > 0 }
        val height = item.height?.takeIf { it > 0 }
        if (width == null || height == null) {
            return unavailable(item, "The source dimensions are unavailable.")
        }

        return when (item.category) {
            CleanerCategory.Image -> estimateImage(item, preset, width, height)
            CleanerCategory.Video -> estimateVideo(item, preset, width, height)
            else -> unavailable(item, "Only images and videos can be re-encoded.")
        }
    }

    internal fun fitWithin(
        width: Int,
        height: Int,
        maxLongEdge: Int,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Dimensions must be positive." }
        require(maxLongEdge >= 2) { "Maximum edge must be at least 2 pixels." }

        val longest = maxOf(width, height)
        if (longest <= maxLongEdge) return evenDimension(width) to evenDimension(height)

        val scale = maxLongEdge.toDouble() / longest.toDouble()
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(2)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(2)
        return evenDimension(targetWidth) to evenDimension(targetHeight)
    }

    internal fun targetVideoBitrate(
        width: Int,
        height: Int,
        preset: MediaReencodePreset,
    ): Long {
        val pixelsPerSecond = width.toDouble() * height.toDouble() * VIDEO_FRAME_RATE
        return (pixelsPerSecond * preset.videoBitsPerPixel)
            .roundToLong()
            .coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
    }

    private fun estimateImage(
        item: CleanableItem,
        preset: MediaReencodePreset,
        width: Int,
        height: Int,
    ): MediaReencodeEstimate {
        val normalizedMime = normalizedImageMime(item.mimeType, item.displayName)
            ?: return unavailable(item, "This image format cannot be safely re-encoded on every supported Android version.")
        val (targetWidth, targetHeight) = fitWithin(width, height, preset.imageMaxLongEdge)
        val sourcePixels = width.toDouble() * height.toDouble()
        val targetPixels = targetWidth.toDouble() * targetHeight.toDouble()
        val pixelRatio = (targetPixels / sourcePixels).coerceIn(0.0, 1.0)

        val compressionRatio = when (normalizedMime) {
            "image/png" -> (pixelRatio * 0.96).coerceIn(0.05, 1.0)
            else -> {
                val qualityRatio = (preset.imageQuality / 100.0).pow(1.45)
                (pixelRatio * qualityRatio * 0.96).coerceIn(0.05, 1.0)
            }
        }
        val estimatedOutput = safeScale(item.sizeBytes, compressionRatio)
        return supportedEstimate(
            item = item,
            estimatedOutputBytes = estimatedOutput,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outputMimeType = normalizedMime,
            outputExtension = extensionForMime(normalizedMime),
            replaceAvailable = true,
        )
    }

    private fun estimateVideo(
        item: CleanableItem,
        preset: MediaReencodePreset,
        width: Int,
        height: Int,
    ): MediaReencodeEstimate {
        val durationMillis = item.durationMillis?.takeIf { it > 0L }
            ?: return unavailable(item, "The video duration is unavailable.")
        val (targetWidth, targetHeight) = fitWithin(width, height, preset.videoMaxLongEdge)
        val videoBitrate = targetVideoBitrate(targetWidth, targetHeight, preset)
        val totalBitrate = safeAdd(videoBitrate, ASSUMED_AUDIO_BITRATE)
        val estimatedOutput = safeDurationBytes(durationMillis, totalBitrate)
        val inputIsMp4 = normalizedMime(item.mimeType) == "video/mp4" ||
            item.displayName.substringAfterLast('.', "").equals("mp4", ignoreCase = true)

        return supportedEstimate(
            item = item,
            estimatedOutputBytes = estimatedOutput,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outputMimeType = "video/mp4",
            outputExtension = "mp4",
            replaceAvailable = inputIsMp4,
            replaceUnavailableReason = if (inputIsMp4) {
                null
            } else {
                "Replacing is disabled because transcoding changes this video's container to MP4. Save a copy instead."
            },
        )
    }

    private fun supportedEstimate(
        item: CleanableItem,
        estimatedOutputBytes: Long,
        targetWidth: Int,
        targetHeight: Int,
        outputMimeType: String,
        outputExtension: String,
        replaceAvailable: Boolean,
        replaceUnavailableReason: String? = null,
    ): MediaReencodeEstimate {
        val boundedOutput = estimatedOutputBytes.coerceAtLeast(0L)
        val savings = (item.sizeBytes - boundedOutput).coerceAtLeast(0L)
        val savingsPercent = if (item.sizeBytes <= 0L) {
            0
        } else {
            ((savings.toDouble() / item.sizeBytes.toDouble()) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }
        return MediaReencodeEstimate(
            sourceBytes = item.sizeBytes,
            estimatedOutputBytes = boundedOutput,
            estimatedSavingsBytes = savings,
            estimatedSavingsPercent = savingsPercent,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outputMimeType = outputMimeType,
            outputExtension = outputExtension,
            replaceAvailable = replaceAvailable,
            replaceUnavailableReason = replaceUnavailableReason,
        )
    }

    private fun unavailable(
        item: CleanableItem,
        reason: String,
    ): MediaReencodeEstimate = MediaReencodeEstimate(
        sourceBytes = item.sizeBytes.coerceAtLeast(0L),
        estimatedOutputBytes = 0L,
        estimatedSavingsBytes = 0L,
        estimatedSavingsPercent = 0,
        targetWidth = 0,
        targetHeight = 0,
        outputMimeType = item.mimeType.orEmpty(),
        outputExtension = "",
        copyAvailable = false,
        copyUnavailableReason = reason,
        replaceAvailable = false,
        replaceUnavailableReason = reason,
        supported = false,
        unavailableReason = reason,
    )

    private fun normalizedImageMime(mimeType: String?, displayName: String): String? {
        return when (normalizedMime(mimeType)) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            else -> when (displayName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> null
            }
        }
    }

    private fun normalizedMime(mimeType: String?): String =
        mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    private fun extensionForMime(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> error("Unsupported image MIME type: $mimeType")
    }

    private fun evenDimension(value: Int): Int {
        val positive = value.coerceAtLeast(2)
        return if (positive % 2 == 0) positive else positive - 1
    }

    private fun safeScale(value: Long, factor: Double): Long {
        if (value <= 0L || factor <= 0.0) return 0L
        val scaled = value.toDouble() * factor
        return if (!scaled.isFinite() || scaled >= Long.MAX_VALUE.toDouble()) {
            Long.MAX_VALUE
        } else {
            scaled.roundToLong().coerceAtLeast(1L)
        }
    }

    private fun safeDurationBytes(durationMillis: Long, bitrate: Long): Long {
        if (durationMillis <= 0L || bitrate <= 0L) return 0L
        val bytes = durationMillis.toDouble() / 1_000.0 * bitrate.toDouble() / 8.0
        return if (!bytes.isFinite() || bytes >= Long.MAX_VALUE.toDouble()) {
            Long.MAX_VALUE
        } else {
            bytes.roundToLong().coerceAtLeast(1L)
        }
    }

    private fun safeAdd(a: Long, b: Long): Long {
        val left = a.coerceAtLeast(0L)
        val right = b.coerceAtLeast(0L)
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
