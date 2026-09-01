package com.apextuner.feature.cleaner.model

object CleanerScanLimits {
    const val MAX_ITEMS = 100_000
}

enum class CleanerCategory {
    Image,
    Video,
    Audio,
    Document,
    Archive,
    Apk,
    Temporary,
    Log,
    EmptyFolder,
    Other,
}

enum class CleanerOrigin {
    MediaStore,
    SafTree,
    SafDocument,
    SelectedMedia,
}

data class CleanableItem(
    val key: String,
    val uri: String,
    val origin: CleanerOrigin,
    val category: CleanerCategory,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long?,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val relativeLocation: String? = null,
    val canDelete: Boolean,
    val canWrite: Boolean = false,
    val parentDocumentUri: String? = null,
    val canCreateSibling: Boolean = false,
    val suspectedJunk: Boolean = false,
    /**
     * Stable identity of the underlying physical item when Android can safely map aliases
     * (for example a SAF document URI to its equivalent MediaStore URI). This is never
     * used to widen permissions; [key] remains the operation/access identity.
     */
    val physicalKey: String? = null,
) {
    val identityKey: String get() = physicalKey ?: key

    val pixelCount: Long?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            width.toLong() * height.toLong()
        } else {
            null
        }
}

enum class DuplicateKeepStrategy {
    BestQuality,
    Newest,
}

data class DuplicateGroup(
    val id: String,
    val sha256: String,
    val items: List<CleanableItem>,
    val bestQualityKey: String,
    val newestKey: String,
    val reclaimableBytes: Long,
) {
    val recommendedKeepKey: String get() = bestQualityKey
}

data class NearDuplicateItem(
    val item: CleanableItem,
    val hammingDistanceFromAnchor: Int,
    val laplacianVariance: Double? = null,
)

data class NearDuplicateGroup(
    val id: String,
    val anchorDHash: Long,
    val anchorKey: String,
    val items: List<NearDuplicateItem>,
    val maxHammingDistance: Int,
)

data class BlurryPhotoCandidate(
    val item: CleanableItem,
    val laplacianVariance: Double,
)

data class PerceptualImageMetrics(
    val dHash: Long,
    val laplacianVariance: Double?,
)

data class PerceptualDuplicateResult(
    val groups: List<NearDuplicateGroup>,
    val blurryPhotos: List<BlurryPhotoCandidate>,
    val hashedItems: Int,
    val truncated: Boolean,
)


enum class PhotoReviewGroupType { Similar, ScreenshotBatch, Burst, RepeatedDownload }

data class PhotoReviewGroup(
    val id: String,
    val type: PhotoReviewGroupType,
    val items: List<CleanableItem>,
    val recommendedKeepKey: String,
    val reclaimableReviewBytes: Long,
    val rationale: String,
)

enum class MediaReencodePreset(
    val imageMaxLongEdge: Int,
    val imageQuality: Int,
    val videoMaxLongEdge: Int,
    val videoBitsPerPixel: Double,
) {
    Balanced(
        imageMaxLongEdge = 1_920,
        imageQuality = 82,
        videoMaxLongEdge = 1_920,
        videoBitsPerPixel = 0.070,
    ),
    Compact(
        imageMaxLongEdge = 1_280,
        imageQuality = 72,
        videoMaxLongEdge = 1_280,
        videoBitsPerPixel = 0.055,
    ),
}

enum class MediaReencodeMode {
    SaveAsCopy,
    ReplaceOriginal,
}

data class MediaReencodeEstimate(
    val sourceBytes: Long,
    val estimatedOutputBytes: Long,
    val estimatedSavingsBytes: Long,
    val estimatedSavingsPercent: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val outputMimeType: String,
    val outputExtension: String,
    val copyAvailable: Boolean = true,
    val copyUnavailableReason: String? = null,
    val replaceAvailable: Boolean,
    val replaceUnavailableReason: String? = null,
    val supported: Boolean = true,
    val unavailableReason: String? = null,
)

data class MediaReencodeProgress(
    val phase: Phase,
    val fraction: Float?,
) {
    enum class Phase {
        Preparing,
        Transcoding,
        SavingCopy,
        SnapshottingOriginal,
        ReplacingOriginal,
        Verifying,
    }

    val cancellable: Boolean
        get() = phase == Phase.Preparing ||
            phase == Phase.Transcoding ||
            phase == Phase.SavingCopy ||
            phase == Phase.SnapshottingOriginal
}

data class MediaReencodeReview(
    val itemKey: String,
    val preset: MediaReencodePreset,
    val estimate: MediaReencodeEstimate? = null,
    val isEstimating: Boolean = false,
    val errorMessage: String? = null,
)

data class MediaReencodeOutcome(
    val mode: MediaReencodeMode,
    val outputUri: String,
    val sourceBytes: Long,
    val outputBytes: Long,
) {
    val savedBytes: Long
        get() = (sourceBytes - outputBytes).coerceAtLeast(0L)
}

data class CategoryUsage(
    val category: CleanerCategory,
    val bytes: Long,
    val itemCount: Int,
)

data class CleanerAccessState(
    val canReadImages: Boolean,
    val canReadVideos: Boolean,
    val canReadAudio: Boolean,
    val limitedVisualAccess: Boolean,
    val legacyMediaWriteGranted: Boolean,
    val persistedTrees: Int,
    val persistedDocuments: Int,
    val usageAccessGranted: Boolean,
) {
    val hasAnySource: Boolean
        get() = canReadImages || canReadVideos || canReadAudio || persistedTrees > 0 || persistedDocuments > 0
}

data class CacheInsight(
    val bytes: Long?,
    val available: Boolean,
)

data class CleanerScanProgress(
    val phase: Phase,
    val itemsScanned: Long,
    val bytesScanned: Long,
) {
    enum class Phase {
        Discovering,
        Media,
        Documents,
        Analyzing,
        Hashing,
        PerceptualHashing,
    }
}

data class CleanerScanResult(
    val items: List<CleanableItem>,
    val duplicateGroups: List<DuplicateGroup>,
    val duplicateAnalysisCompleted: Boolean = false,
    val nearDuplicateGroups: List<NearDuplicateGroup> = emptyList(),
    val nearDuplicateAnalysisCompleted: Boolean = false,
    val nearDuplicateAnalysisTruncated: Boolean = false,
    val blurryPhotos: List<BlurryPhotoCandidate> = emptyList(),
    val blurryPhotoAnalysisCompleted: Boolean = false,
    val blurryPhotoAnalysisTruncated: Boolean = false,
    val photoReviewGroups: List<PhotoReviewGroup> = emptyList(),
    val photoReviewAnalysisCompleted: Boolean = false,
    val largeFiles: List<CleanableItem>,
    val suspectedJunk: List<CleanableItem>,
    val categoryUsage: List<CategoryUsage>,
    val totalAccessibleBytes: Long,
    val potentialReclaimBytes: Long,
    val skippedItems: Int,
    val truncated: Boolean,
    val cacheInsight: CacheInsight,
)


data class CleanerSmartReviewSummary(
    val exactDuplicateBytes: Long?,
    val photoReviewCandidateBytes: Long?,
    val largeFileBytes: Long,
    val suspectedCandidateBytes: Long,
    val compressibleMediaSourceBytes: Long,
    val exactDuplicateGroups: Int,
    val photoReviewGroups: Int,
    val largeFiles: Int,
    val suspectedCandidates: Int,
    val compressibleMediaCandidates: Int,
)

fun CleanerScanResult.smartReviewSummary(): CleanerSmartReviewSummary {
    val photoCandidates = photoReviewGroups
        .flatMap { group -> group.items.filter { it.key != group.recommendedKeepKey } }
        .distinctBy { it.identityKey }
    val largeUnique = largeFiles.distinctBy { it.identityKey }
    val suspectedUnique = suspectedJunk.distinctBy { it.identityKey }
    val compressible = largeUnique.filter { item ->
        item.category in setOf(CleanerCategory.Image, CleanerCategory.Video) && (item.canWrite || item.canCreateSibling)
    }
    return CleanerSmartReviewSummary(
        exactDuplicateBytes = if (duplicateAnalysisCompleted) duplicateGroups.saturatingByteSum { it.reclaimableBytes } else null,
        photoReviewCandidateBytes = if (photoReviewAnalysisCompleted) photoCandidates.saturatingByteSum { it.sizeBytes } else null,
        largeFileBytes = largeUnique.saturatingByteSum { it.sizeBytes },
        suspectedCandidateBytes = suspectedUnique.saturatingByteSum { it.sizeBytes },
        compressibleMediaSourceBytes = compressible.saturatingByteSum { it.sizeBytes },
        exactDuplicateGroups = duplicateGroups.size,
        photoReviewGroups = photoReviewGroups.size,
        largeFiles = largeUnique.size,
        suspectedCandidates = suspectedUnique.size,
        compressibleMediaCandidates = compressible.size,
    )
}


private inline fun <T> Iterable<T>.saturatingByteSum(selector: (T) -> Long): Long {
    var total = 0L
    for (item in this) {
        val value = selector(item).coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < value) return Long.MAX_VALUE
        total += value
    }
    return total
}

data class DeletionOutcome(
    val requested: Int,
    val deleted: Int,
    val failed: Int,
    val bytesDeleted: Long,
)

data class PersistedAccess(
    val uri: String,
    val displayName: String,
    val isTree: Boolean,
    val canWrite: Boolean,
)

sealed interface CleanerUiState {
    data object Loading : CleanerUiState

    data class Ready(
        val access: CleanerAccessState,
        val persistedAccess: List<PersistedAccess>,
        val scan: CleanerScanResult? = null,
        val scanProgress: CleanerScanProgress? = null,
        val selectedKeys: Set<String> = emptySet(),
        val awaitingRemovalConfirmation: Boolean = false,
        val isRemoving: Boolean = false,
        val reencodeReview: MediaReencodeReview? = null,
        val reencodeProgress: MediaReencodeProgress? = null,
        val awaitingMediaWriteConfirmation: Boolean = false,
        val errorMessage: String? = null,
        val infoMessage: String? = null,
    ) : CleanerUiState {
        val isScanning: Boolean get() = scanProgress != null
        val isReencoding: Boolean get() = reencodeProgress != null
        val isBusy: Boolean
            get() = isScanning || awaitingRemovalConfirmation || isRemoving ||
                isReencoding || awaitingMediaWriteConfirmation
    }
}
