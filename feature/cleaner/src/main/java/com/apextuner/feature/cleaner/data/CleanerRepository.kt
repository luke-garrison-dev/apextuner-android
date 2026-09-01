package com.apextuner.feature.cleaner.data

import android.app.PendingIntent
import android.net.Uri
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerAccessState
import com.apextuner.feature.cleaner.model.CleanerScanProgress
import com.apextuner.feature.cleaner.model.CleanerScanResult
import com.apextuner.feature.cleaner.model.DeletionOutcome
import com.apextuner.feature.cleaner.model.DuplicateGroup
import com.apextuner.feature.cleaner.model.PersistedAccess
import com.apextuner.feature.cleaner.model.PerceptualDuplicateResult
import com.apextuner.feature.cleaner.model.MediaReencodeProgress
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import com.apextuner.feature.cleaner.model.MediaReencodeOutcome
import com.apextuner.feature.cleaner.model.MediaReencodeMode
import com.apextuner.feature.cleaner.model.MediaReencodeEstimate

enum class MediaRemovalMode {
    Trash,
    Permanent,
}

data class PersistGrantOutcome(
    val granted: Int,
    val failed: Int,
)

interface CleanerRepository {
    suspend fun accessState(): CleanerAccessState
    suspend fun persistedAccess(): List<PersistedAccess>
    suspend fun persistTree(uri: Uri): Boolean
    suspend fun persistDocuments(uris: List<Uri>): PersistGrantOutcome
    suspend fun releasePersistedAccess(uri: Uri): Boolean

    suspend fun scan(
        onProgress: suspend (CleanerScanProgress) -> Unit,
    ): CleanerScanResult

    suspend fun findDuplicates(
        items: List<CleanableItem>,
        onProgress: suspend (CleanerScanProgress) -> Unit,
    ): List<DuplicateGroup>

    suspend fun findNearDuplicates(
        items: Iterable<CleanableItem>,
        onProgress: suspend (CleanerScanProgress) -> Unit,
    ): PerceptualDuplicateResult

    suspend fun estimateReencode(
        item: CleanableItem,
        preset: MediaReencodePreset,
    ): MediaReencodeEstimate

    suspend fun createMediaWriteRequest(item: CleanableItem): PendingIntent?

    suspend fun reencode(
        item: CleanableItem,
        preset: MediaReencodePreset,
        mode: MediaReencodeMode,
        onProgress: suspend (MediaReencodeProgress) -> Unit,
    ): MediaReencodeOutcome

    fun createMediaRemovalRequest(
        mediaItems: List<CleanableItem>,
        mode: MediaRemovalMode,
    ): PendingIntent?

    suspend fun deleteNonPromptItems(items: List<CleanableItem>): DeletionOutcome
    suspend fun evaluateMediaRemoval(items: List<CleanableItem>, mode: MediaRemovalMode): DeletionOutcome
}
