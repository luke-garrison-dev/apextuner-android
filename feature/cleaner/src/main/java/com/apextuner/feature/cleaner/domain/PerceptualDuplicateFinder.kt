package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.BlurryPhotoCandidate
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerScanLimits
import com.apextuner.feature.cleaner.model.NearDuplicateGroup
import com.apextuner.feature.cleaner.model.NearDuplicateItem
import com.apextuner.feature.cleaner.model.PerceptualDuplicateResult
import com.apextuner.feature.cleaner.model.PerceptualImageMetrics
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class PerceptualDuplicateFinder(
    private val analyze: suspend (CleanableItem) -> PerceptualImageMetrics?,
    private val hammingDistanceThreshold: Int = DEFAULT_HAMMING_DISTANCE_THRESHOLD,
    private val blurryLaplacianVarianceThreshold: Double = DEFAULT_BLURRY_LAPLACIAN_VARIANCE_THRESHOLD,
    private val maxHashedItems: Int = MAX_HASHED_ITEMS,
) {
    init {
        require(hammingDistanceThreshold in 0..MAX_HAMMING_DISTANCE) {
            "Hamming distance threshold must be between 0 and $MAX_HAMMING_DISTANCE."
        }
        require(blurryLaplacianVarianceThreshold.isFinite() && blurryLaplacianVarianceThreshold >= 0.0) {
            "Blurry-photo Laplacian variance threshold must be finite and non-negative."
        }
        require(maxHashedItems in 1..MAX_HASHED_ITEMS) {
            "Perceptual hash ceiling must be between 1 and $MAX_HASHED_ITEMS."
        }
    }

    suspend fun find(
        items: Iterable<CleanableItem>,
        onHashed: suspend (CleanableItem) -> Unit = {},
    ): PerceptualDuplicateResult {
        val scopes = linkedMapOf<String, ScopeBuckets>()
        val blurryPhotos = mutableListOf<BlurryPhotoCandidate>()
        val seenPhysicalItems = HashSet<String>()
        var hashedItems = 0
        var truncated = false

        for (item in items) {
            coroutineContext.ensureActive()
            if (item.category != CleanerCategory.Image || !seenPhysicalItems.add(item.identityKey)) continue
            if (hashedItems >= maxHashedItems) {
                truncated = true
                continue
            }

            hashedItems++
            val metrics = analyze(item)
            onHashed(item)
            if (metrics == null) continue

            val scope = scopes.getOrPut(duplicateSourceScope(item)) { ScopeBuckets() }
            scope.add(item, metrics.dHash, metrics.laplacianVariance, hammingDistanceThreshold)

            val sharpness = metrics.laplacianVariance
            if (
                sharpness != null &&
                sharpness.isFinite() &&
                sharpness < blurryLaplacianVarianceThreshold
            ) {
                blurryPhotos += BlurryPhotoCandidate(
                    item = item,
                    laplacianVariance = sharpness,
                )
            }
        }

        val groups = scopes.values
            .asSequence()
            .flatMap { it.groups() }
            .sortedWith(
                compareByDescending<NearDuplicateGroup> { it.items.size }
                    .thenBy { it.id },
            )
            .toList()

        val orderedBlurryPhotos = blurryPhotos.sortedWith(
            compareBy<BlurryPhotoCandidate> { it.laplacianVariance }
                .thenByDescending { it.item.pixelCount ?: 0L }
                .thenByDescending { it.item.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.item.displayName.lowercase() },
        )

        return PerceptualDuplicateResult(
            groups = groups,
            blurryPhotos = orderedBlurryPhotos,
            hashedItems = hashedItems,
            truncated = truncated,
        )
    }

    private class ScopeBuckets {
        private val buckets = mutableListOf<Bucket>()
        private val anchors = HammingBkTree()

        fun add(item: CleanableItem, fingerprint: Long, laplacianVariance: Double?, threshold: Int) {
            val nearest = anchors.nearestWithin(fingerprint, threshold)
            if (nearest == null) {
                val index = buckets.size
                buckets += Bucket(
                    anchorHash = fingerprint,
                    items = mutableListOf(
                        NearDuplicateItem(
                            item = item,
                            hammingDistanceFromAnchor = 0,
                            laplacianVariance = laplacianVariance,
                        ),
                    ),
                )
                anchors.add(fingerprint, index)
                return
            }

            buckets[nearest.bucketIndex].items += NearDuplicateItem(
                item = item,
                hammingDistanceFromAnchor = nearest.distance,
                laplacianVariance = laplacianVariance,
            )
        }

        fun groups(): Sequence<NearDuplicateGroup> = buckets
            .asSequence()
            .filter { it.items.size >= 2 }
            .map { bucket ->
                val ordered = bucket.items.sortedWith(
                    compareByDescending<NearDuplicateItem> { it.laplacianVariance ?: Double.NEGATIVE_INFINITY }
                        .thenByDescending { it.item.pixelCount ?: 0L }
                        .thenBy { it.hammingDistanceFromAnchor }
                        .thenByDescending { it.item.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                        .thenBy { it.item.displayName.lowercase() },
                )
                val anchorKey = bucket.items.first().item.key
                NearDuplicateGroup(
                    id = "dhash:${java.lang.Long.toHexString(bucket.anchorHash)}:$anchorKey",
                    anchorDHash = bucket.anchorHash,
                    anchorKey = anchorKey,
                    items = ordered,
                    maxHammingDistance = ordered.maxOf { it.hammingDistanceFromAnchor },
                )
            }

        private data class Bucket(
            val anchorHash: Long,
            val items: MutableList<NearDuplicateItem>,
        )
    }

    private class HammingBkTree {
        private var root: Node? = null

        fun add(value: Long, bucketIndex: Int) {
            val currentRoot = root
            if (currentRoot == null) {
                root = Node(value, bucketIndex)
                return
            }

            var current: Node = currentRoot
            while (true) {
                val distance = hammingDistance(current.value, value)
                val child = current.children[distance]
                if (child == null) {
                    current.children[distance] = Node(value, bucketIndex)
                    return
                }
                current = child
            }
        }

        fun nearestWithin(value: Long, threshold: Int): Match? {
            val currentRoot = root ?: return null
            var best: Match? = null
            val pending = ArrayDeque<Node>()
            pending.addLast(currentRoot)

            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                val distance = hammingDistance(node.value, value)
                if (distance <= threshold) {
                    val currentBest = best
                    if (
                        currentBest == null ||
                        distance < currentBest.distance ||
                        (distance == currentBest.distance && node.bucketIndex < currentBest.bucketIndex)
                    ) {
                        best = Match(node.bucketIndex, distance)
                    }
                }

                val lower = (distance - threshold).coerceAtLeast(0)
                val upper = (distance + threshold).coerceAtMost(MAX_HAMMING_DISTANCE)
                node.children.forEach { (edgeDistance, child) ->
                    if (edgeDistance in lower..upper) pending.addLast(child)
                }
            }

            return best
        }

        private data class Node(
            val value: Long,
            val bucketIndex: Int,
            val children: MutableMap<Int, Node> = mutableMapOf(),
        )
    }

    private data class Match(
        val bucketIndex: Int,
        val distance: Int,
    )

    companion object {
        const val DEFAULT_HAMMING_DISTANCE_THRESHOLD = 5
        const val DEFAULT_BLURRY_LAPLACIAN_VARIANCE_THRESHOLD = 80.0
        const val MAX_HASHED_ITEMS = CleanerScanLimits.MAX_ITEMS
        private const val MAX_HAMMING_DISTANCE = Long.SIZE_BITS

        internal fun hammingDistance(first: Long, second: Long): Int =
            java.lang.Long.bitCount(first xor second)
    }
}
