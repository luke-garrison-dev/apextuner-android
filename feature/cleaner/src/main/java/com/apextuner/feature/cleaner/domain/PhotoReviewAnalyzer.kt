package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.NearDuplicateGroup
import com.apextuner.feature.cleaner.model.PhotoReviewGroup
import com.apextuner.feature.cleaner.model.PhotoReviewGroupType
import java.util.Locale
import java.util.concurrent.TimeUnit

object PhotoReviewAnalyzer {
    fun build(
        items: List<CleanableItem>,
        nearDuplicateGroups: List<NearDuplicateGroup>,
    ): List<PhotoReviewGroup> {
        val images = items.asSequence().filter { it.category == CleanerCategory.Image }.distinctBy { it.identityKey }.toList()
        val sharpness = nearDuplicateGroups.asSequence().flatMap { it.items.asSequence() }
            .mapNotNull { item -> item.laplacianVariance?.takeIf { it.isFinite() }?.let { item.item.identityKey to it } }
            .toMap()
        val groups = mutableListOf<PhotoReviewGroup>()

        nearDuplicateGroups.forEach { near ->
            val candidates = near.items.map { it.item }.distinctBy { it.identityKey }
            if (candidates.size >= 2) groups += group(
                id = "similar:${near.id}",
                type = PhotoReviewGroupType.Similar,
                items = candidates,
                sharpness = sharpness,
                rationale = "Visually similar photos. Suggested keep favors measured sharpness, resolution, then recency.",
            )
        }

        images.asSequence()
            .filter(::isScreenshot)
            .groupBy { item -> "${normalizedLocation(item)}:${dayBucket(item.modifiedAtEpochMillis)}" }
            .values
            .filter { it.size >= 2 }
            .forEachIndexed { index, batch ->
                groups += group("screenshots:$index:${batch.first().identityKey}", PhotoReviewGroupType.ScreenshotBatch, batch, sharpness,
                    "Screenshot batch from the same location/day. Review-only; ApexTuner does not assume older screenshots are disposable.")
            }

        images.groupBy(::burstKey).filterKeys { it != null }.values
            .filter { it.size >= 2 }
            .forEachIndexed { index, batch ->
                groups += group("burst:$index:${batch.first().identityKey}", PhotoReviewGroupType.Burst, batch, sharpness,
                    "Filename metadata indicates a burst sequence. Suggested keep favors quality and recency; every item remains user-reviewed.")
            }

        images.asSequence().filter(::isDownloadLike).groupBy(::repeatedDownloadKey).filterKeys { it != null }.values
            .filter { it.size >= 2 }
            .forEachIndexed { index, batch ->
                groups += group("download:$index:${batch.first().identityKey}", PhotoReviewGroupType.RepeatedDownload, batch, sharpness,
                    "Download filenames differ only by common copy-number suffixes. Contents are not assumed identical unless exact-duplicate analysis also confirms them.")
            }

        return groups.distinctBy { it.id }.sortedWith(compareByDescending<PhotoReviewGroup> { it.reclaimableReviewBytes }.thenByDescending { it.items.size }.thenBy { it.id })
    }

    private fun group(
        id: String,
        type: PhotoReviewGroupType,
        items: List<CleanableItem>,
        sharpness: Map<String, Double>,
        rationale: String,
    ): PhotoReviewGroup {
        val ordered = items.sortedWith(
            compareByDescending<CleanableItem> { sharpness[it.identityKey] ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.pixelCount ?: 0L }
                .thenByDescending { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
        )
        val keep = ordered.first()
        val reclaimable = ordered.drop(1).fold(0L) { sum, item ->
            if (item.sizeBytes <= 0L) sum else if (Long.MAX_VALUE - sum < item.sizeBytes) Long.MAX_VALUE else sum + item.sizeBytes
        }
        return PhotoReviewGroup(id, type, ordered, keep.key, reclaimable, rationale)
    }

    private fun isScreenshot(item: CleanableItem): Boolean {
        val haystack = "${item.relativeLocation.orEmpty()}/${item.displayName}".lowercase(Locale.ROOT)
        return "screenshot" in haystack || "screen_shot" in haystack || "screen-shot" in haystack
    }

    private fun normalizedLocation(item: CleanableItem): String = item.relativeLocation.orEmpty().lowercase(Locale.ROOT).trim('/').take(120)
    private fun dayBucket(value: Long?): Long = value?.takeIf { it > 0L }?.let { TimeUnit.MILLISECONDS.toDays(it) } ?: -1L

    private fun burstKey(item: CleanableItem): String? {
        val base = item.displayName.substringBeforeLast('.', item.displayName)
        val normalized = BURST_TOKEN.replace(base, "_BURST").lowercase(Locale.ROOT)
        return normalized.takeIf { it != base.lowercase(Locale.ROOT) }
    }

    private fun isDownloadLike(item: CleanableItem): Boolean = item.relativeLocation.orEmpty().lowercase(Locale.ROOT).contains("download")

    private fun repeatedDownloadKey(item: CleanableItem): String? {
        val extension = item.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = item.displayName.substringBeforeLast('.', item.displayName)
        val normalized = COPY_SUFFIX.replace(stem, "").trim().lowercase(Locale.ROOT)
        return "$normalized.$extension"
    }

    private val BURST_TOKEN = Regex("(?i)(?:[_ -]burst[_ -]?\\d+|[_ -]burst)$")
    private val COPY_SUFFIX = Regex("(?i)(?:\\s*\\(\\d+\\)|[ _-]+copy(?:[ _-]*\\d+)?)$")
}
