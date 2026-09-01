package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.DuplicateGroup

enum class HashMode(val maxBytes: Long?) {
    Prefix(256L * 1024L),
    Full(null),
}

class DuplicateFinder(
    private val digest: suspend (CleanableItem, HashMode) -> String?,
) {
    suspend fun find(
        items: List<CleanableItem>,
        onFullHashed: suspend (CleanableItem) -> Unit = {},
    ): List<DuplicateGroup> {
        // MediaStore and SAF can expose the same physical media through different URI namespaces.
        // Keep MediaStore isolated from document-provider sources, while SAF tree/document entries
        // can safely be compared because their canonical document keys are deduplicated upstream.
        val sizeBuckets = items
            .asSequence()
            .filter { it.sizeBytes > 0L }
            .groupBy { duplicateSourceScope(it) to it.sizeBytes }
            .filterValues { it.size >= 2 }

        val groups = mutableListOf<DuplicateGroup>()
        for ((bucketKey, bucket) in sizeBuckets) {
            val byPrefix = linkedMapOf<String, MutableList<CleanableItem>>()
            for (item in bucket) {
                val fingerprint = digest(item, HashMode.Prefix) ?: continue
                byPrefix.getOrPut(fingerprint) { mutableListOf() }.add(item)
            }

            for (candidateGroup in byPrefix.values) {
                if (candidateGroup.size < 2) continue
                val byFullHash = linkedMapOf<String, MutableList<CleanableItem>>()
                for (item in candidateGroup) {
                    val fullDigest = digest(item, HashMode.Full) ?: continue
                    onFullHashed(item)
                    byFullHash.getOrPut(fullDigest) { mutableListOf() }.add(item)
                }

                for ((fullDigest, exactItems) in byFullHash) {
                    // The same physical object may have more than one Android URI alias. Never
                    // present aliases as duplicate copies, even if all bytes/hash values match.
                    val uniqueExactItems = exactItems.distinctBy { it.identityKey }
                    if (uniqueExactItems.size < 2) continue

                    val bestQuality = uniqueExactItems.maxWithOrNull(
                        compareBy<CleanableItem> { it.pixelCount ?: 0L }
                            .thenBy { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                            .thenBy { it.displayName.lowercase() },
                    ) ?: continue
                    val newest = uniqueExactItems.maxWithOrNull(
                        compareBy<CleanableItem> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                            .thenBy { it.pixelCount ?: 0L }
                            .thenBy { it.displayName.lowercase() },
                    ) ?: bestQuality

                    val ordered = uniqueExactItems.sortedWith(
                        compareByDescending<CleanableItem> { it.key == bestQuality.key }
                            .thenByDescending { it.key == newest.key }
                            .thenByDescending { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                            .thenBy { it.displayName.lowercase() },
                    )

                    groups += DuplicateGroup(
                        id = "${bucketKey.first}:${ordered.first().sizeBytes}:$fullDigest",
                        sha256 = fullDigest,
                        items = ordered,
                        bestQualityKey = bestQuality.key,
                        newestKey = newest.key,
                        reclaimableBytes = ordered
                            .asSequence()
                            .filter { it.key != bestQuality.key && it.canDelete }
                            .asIterable()
                            .sumOfSafe { it.sizeBytes.coerceAtLeast(0L) },
                    )
                }
            }
        }
        return groups.sortedByDescending { it.reclaimableBytes }
    }

    private inline fun <T> Iterable<T>.sumOfSafe(selector: (T) -> Long): Long {
        var total = 0L
        for (item in this) {
            val value = selector(item)
            total = if (value > 0L && total > Long.MAX_VALUE - value) Long.MAX_VALUE else total + value
        }
        return total
    }
}

/**
 * Keeps unproven provider aliases isolated while allowing Android-mapped media identities
 * to participate in the same duplicate-analysis scope.
 */
internal fun duplicateSourceScope(item: CleanableItem): String {
    if (item.physicalKey?.startsWith("media-physical:") == true) return "mapped-media"
    return when (item.origin) {
        CleanerOrigin.MediaStore -> "mediastore"
        CleanerOrigin.SafTree, CleanerOrigin.SafDocument -> {
            val authority = item.uri.substringAfter("://", missingDelimiterValue = "")
                .substringBefore('/')
                .ifBlank { "unknown" }
            "saf:$authority"
        }
        CleanerOrigin.SelectedMedia -> {
            val authority = item.uri.substringAfter("://", missingDelimiterValue = "")
                .substringBefore('/')
                .ifBlank { "unknown" }
            "picker:$authority"
        }
    }
}
