package com.apextuner.feature.cleaner

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.model.OptimizationOutcome
import com.apextuner.core.model.OptimizationRecord
import com.apextuner.core.model.ScanSession
import com.apextuner.core.model.ScanStatus
import com.apextuner.core.repository.OptimizationHistoryRepository
import com.apextuner.core.repository.ScanRepository
import com.apextuner.core.time.TimeProvider
import com.apextuner.feature.cleaner.data.CleanerRepository
import com.apextuner.feature.cleaner.data.MediaRemovalMode
import com.apextuner.feature.cleaner.domain.CleanerAnalyzer
import com.apextuner.feature.cleaner.domain.PhotoReviewAnalyzer
import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerAccessState
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerScanProgress
import com.apextuner.feature.cleaner.model.CleanerUiState
import com.apextuner.feature.cleaner.model.DeletionOutcome
import com.apextuner.feature.cleaner.model.DuplicateKeepStrategy
import com.apextuner.feature.cleaner.model.MediaReencodeMode
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import com.apextuner.feature.cleaner.model.MediaReencodeProgress
import com.apextuner.feature.cleaner.model.MediaReencodeReview
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CleanerEffect {
    data class LaunchMediaRemoval(val pendingIntent: PendingIntent) : CleanerEffect
    data class LaunchMediaWrite(val pendingIntent: PendingIntent) : CleanerEffect
}

@HiltViewModel
class CleanerViewModel @Inject constructor(
    private val cleanerRepository: CleanerRepository,
    private val scanRepository: ScanRepository,
    private val historyRepository: OptimizationHistoryRepository,
    private val timeProvider: TimeProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CleanerUiState>(CleanerUiState.Loading)
    val uiState: StateFlow<CleanerUiState> = _uiState.asStateFlow()

    private val effectChannel = Channel<CleanerEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private var scanJob: Job? = null
    private var reencodeJob: Job? = null
    private var estimateJob: Job? = null
    private var pendingRemoval: PendingRemoval? = null
    private var pendingReencode: PendingReencode? = null

    init {
        refreshAccess()
    }

    fun refreshAccess(infoMessage: String? = null) {
        viewModelScope.launch {
            val previous = _uiState.value as? CleanerUiState.Ready
            try {
                _uiState.value = CleanerUiState.Ready(
                    access = cleanerRepository.accessState(),
                    persistedAccess = cleanerRepository.persistedAccess(),
                    scan = previous?.scan,
                    scanProgress = previous?.scanProgress,
                    selectedKeys = previous?.selectedKeys.orEmpty(),
                    awaitingRemovalConfirmation = previous?.awaitingRemovalConfirmation ?: false,
                    isRemoving = previous?.isRemoving ?: false,
                    reencodeReview = previous?.reencodeReview,
                    reencodeProgress = previous?.reencodeProgress,
                    awaitingMediaWriteConfirmation = previous?.awaitingMediaWriteConfirmation ?: false,
                    infoMessage = infoMessage,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = previous?.copy(
                    errorMessage = "Storage access state could not be refreshed.",
                ) ?: CleanerUiState.Ready(
                    access = EMPTY_ACCESS,
                    persistedAccess = emptyList(),
                    errorMessage = "Storage access state could not be loaded. No files are accessible until access is refreshed.",
                )
            }
        }
    }

    fun persistTree(uri: Uri) {
        if (!canChangeAccess()) return
        viewModelScope.launch {
            val success = runCatchingCancellable { cleanerRepository.persistTree(uri) }.getOrDefault(false)
            refreshAccess(if (success) "Folder access added." else "The selected folder did not grant persistent access.")
        }
    }

    fun persistDocuments(uris: List<Uri>) {
        if (uris.isEmpty() || !canChangeAccess()) return
        viewModelScope.launch {
            val result = runCatchingCancellable { cleanerRepository.persistDocuments(uris) }.getOrNull()
            if (result == null) {
                updateReady { it.copy(errorMessage = "The selected file grants could not be persisted.") }
                return@launch
            }
            val message = when {
                result.failed == 0 -> "Added ${result.granted} file${if (result.granted == 1) "" else "s"}."
                result.granted == 0 -> "No selected files granted persistent access."
                else -> "Added ${result.granted} files; ${result.failed} could not be persisted."
            }
            refreshAccess(message)
        }
    }

    fun releasePersistedAccess(uri: String) {
        if (!canChangeAccess()) return
        viewModelScope.launch {
            val success = runCatchingCancellable { cleanerRepository.releasePersistedAccess(Uri.parse(uri)) }.getOrDefault(false)
            refreshAccess(if (success) "Storage access removed." else "Could not remove that storage grant.")
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true || reencodeJob?.isActive == true ||
            pendingRemoval != null || pendingReencode != null
        ) {
            return
        }
        scanJob = viewModelScope.launch {
            val scanId = UUID.randomUUID().toString()
            val started = timeProvider.nowEpochMillis()
            runCatchingCancellable {
                scanRepository.save(
                    ScanSession(
                        id = scanId,
                        scanType = "storage",
                        startedAtEpochMillis = started,
                        completedAtEpochMillis = null,
                        itemsScanned = 0L,
                        bytesEligible = 0L,
                        status = ScanStatus.Running,
                    ),
                )
            }
            try {
                updateReady { it.copy(scanProgress = CleanerScanProgress(CleanerScanProgress.Phase.Discovering, 0L, 0L), errorMessage = null, infoMessage = null) }
                val result = cleanerRepository.scan { progress ->
                    updateReady { state -> state.copy(scanProgress = progress) }
                }
                val historyRecorded = runCatchingCancellable {
                    scanRepository.save(
                        ScanSession(
                            id = scanId,
                            scanType = "storage",
                            startedAtEpochMillis = started,
                            completedAtEpochMillis = timeProvider.nowEpochMillis(),
                            itemsScanned = result.items.size.toLong(),
                            bytesEligible = result.potentialReclaimBytes,
                            status = ScanStatus.Completed,
                        ),
                    )
                }.isSuccess
                val refreshedAccess = runCatchingCancellable { cleanerRepository.accessState() }.getOrNull()
                val refreshedGrants = runCatchingCancellable { cleanerRepository.persistedAccess() }.getOrNull()
                updateReady {
                    val baseMessage = if (result.truncated) {
                        "Scan completed with the safety item limit reached. Narrow the selected folders for a complete pass."
                    } else {
                        "Scan completed."
                    }
                    it.copy(
                        access = refreshedAccess ?: it.access,
                        persistedAccess = refreshedGrants ?: it.persistedAccess,
                        scan = result,
                        scanProgress = null,
                        selectedKeys = emptySet(),
                        infoMessage = baseMessage + if (historyRecorded) "" else " The local scan-history entry could not be saved.",
                    )
                }
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    runCatchingCancellable {
                        scanRepository.save(
                            ScanSession(
                                id = scanId,
                                scanType = "storage",
                                startedAtEpochMillis = started,
                                completedAtEpochMillis = timeProvider.nowEpochMillis(),
                                itemsScanned = currentReady()?.scanProgress?.itemsScanned ?: 0L,
                                bytesEligible = 0L,
                                status = ScanStatus.Cancelled,
                            ),
                        )
                    }
                    updateReady { it.copy(scanProgress = null, infoMessage = "Scan cancelled.") }
                }
                throw cancellation
            } catch (_: Exception) {
                runCatchingCancellable {
                    scanRepository.save(
                        ScanSession(
                            id = scanId,
                            scanType = "storage",
                            startedAtEpochMillis = started,
                            completedAtEpochMillis = timeProvider.nowEpochMillis(),
                            itemsScanned = currentReady()?.scanProgress?.itemsScanned ?: 0L,
                            bytesEligible = 0L,
                            status = ScanStatus.Failed,
                        ),
                    )
                }
                updateReady { it.copy(scanProgress = null, errorMessage = "The storage scan could not complete. No files were changed.") }
            }
        }
    }

    fun cancelScan() {
        val state = currentReady()
        if (state?.isRemoving == true || state?.awaitingRemovalConfirmation == true) return
        scanJob?.cancel()
    }

    fun findDuplicates() {
        val scan = currentReady()?.scan ?: return
        if (scanJob?.isActive == true || reencodeJob?.isActive == true ||
            pendingRemoval != null || pendingReencode != null
        ) {
            return
        }
        scanJob = viewModelScope.launch {
            try {
                updateReady { it.copy(scanProgress = CleanerScanProgress(CleanerScanProgress.Phase.Hashing, 0L, 0L), errorMessage = null, infoMessage = null) }
                val exactGroups = cleanerRepository.findDuplicates(scan.items) { progress ->
                    updateReady { it.copy(scanProgress = progress) }
                }
                val exactRedundantIdentities = exactGroups
                    .asSequence()
                    .flatMap { group ->
                        group.items
                            .asSequence()
                            .filter { it.key != group.recommendedKeepKey }
                            .map { it.identityKey }
                    }
                    .toHashSet()
                val perceptual = cleanerRepository.findNearDuplicates(
                    items = scan.items.asSequence().filter { it.identityKey !in exactRedundantIdentities }.asIterable(),
                ) { progress ->
                    updateReady { it.copy(scanProgress = progress) }
                }
                val updated = scan.copy(
                    duplicateGroups = exactGroups,
                    duplicateAnalysisCompleted = true,
                    nearDuplicateGroups = perceptual.groups,
                    nearDuplicateAnalysisCompleted = true,
                    nearDuplicateAnalysisTruncated = perceptual.truncated,
                    blurryPhotos = perceptual.blurryPhotos,
                    blurryPhotoAnalysisCompleted = true,
                    blurryPhotoAnalysisTruncated = perceptual.truncated,
                    photoReviewGroups = PhotoReviewAnalyzer.build(scan.items, perceptual.groups),
                    photoReviewAnalysisCompleted = true,
                    potentialReclaimBytes = CleanerAnalyzer.potentialReclaimBytes(exactGroups, scan.suspectedJunk),
                )
                val completionMessage = buildString {
                    append(
                        appContext.getString(
                            R.string.cleaner_duplicate_analysis_completed,
                            appContext.resources.getQuantityString(R.plurals.cleaner_exact_groups, exactGroups.size, exactGroups.size),
                            appContext.resources.getQuantityString(R.plurals.cleaner_near_duplicate_groups, perceptual.groups.size, perceptual.groups.size),
                            appContext.resources.getQuantityString(R.plurals.cleaner_blurry_candidates, perceptual.blurryPhotos.size, perceptual.blurryPhotos.size),
                        ),
                    )
                    if (perceptual.truncated) {
                        append(appContext.getString(R.string.cleaner_duplicate_analysis_truncated))
                    }
                }
                updateReady {
                    it.copy(
                        scan = updated,
                        scanProgress = null,
                        infoMessage = completionMessage,
                    )
                }
            } catch (cancellation: CancellationException) {
                updateReady { it.copy(scanProgress = null, infoMessage = appContext.getString(R.string.cleaner_duplicate_analysis_cancelled)) }
                throw cancellation
            } catch (_: Exception) {
                updateReady { it.copy(scanProgress = null, errorMessage = appContext.getString(R.string.cleaner_duplicate_analysis_failed)) }
            }
        }
    }

    fun reviewReencode(itemKey: String) {
        val state = currentReady() ?: return
        if (state.isBusy || pendingRemoval != null || pendingReencode != null) return
        val item = state.scan?.largeFiles?.firstOrNull { it.key == itemKey } ?: return
        if (item.category != CleanerCategory.Image && item.category != CleanerCategory.Video) {
            updateReady {
                it.copy(errorMessage = "Only large images and videos can be re-encoded.", infoMessage = null)
            }
            return
        }
        loadReencodeEstimate(item, MediaReencodePreset.Balanced)
    }

    fun updateReencodePreset(preset: MediaReencodePreset) {
        val state = currentReady() ?: return
        if (state.isBusy || pendingReencode != null) return
        val review = state.reencodeReview ?: return
        if (review.preset == preset && review.estimate != null) return
        val item = state.scan?.largeFiles?.firstOrNull { it.key == review.itemKey } ?: return
        loadReencodeEstimate(item, preset)
    }

    fun dismissReencodeReview() {
        if (pendingReencode != null || currentReady()?.isBusy == true) return
        estimateJob?.cancel()
        estimateJob = null
        updateReady { it.copy(reencodeReview = null) }
    }

    fun beginReencode(mode: MediaReencodeMode) {
        val state = currentReady() ?: return
        if (state.isBusy || pendingRemoval != null || pendingReencode != null) return
        val review = state.reencodeReview ?: return
        val estimate = review.estimate ?: return
        if (!estimate.supported || estimate.estimatedSavingsBytes <= 0L) {
            updateReady {
                it.copy(
                    errorMessage = estimate.unavailableReason
                        ?: "The current estimate does not predict a smaller output, so no file was changed.",
                )
            }
            return
        }
        val allowed = when (mode) {
            MediaReencodeMode.SaveAsCopy -> estimate.copyAvailable
            MediaReencodeMode.ReplaceOriginal -> estimate.replaceAvailable
        }
        if (!allowed) {
            val reason = when (mode) {
                MediaReencodeMode.SaveAsCopy -> estimate.copyUnavailableReason
                MediaReencodeMode.ReplaceOriginal -> estimate.replaceUnavailableReason
            }
            updateReady { it.copy(errorMessage = reason ?: "That re-encode mode is unavailable.") }
            return
        }
        val item = state.scan?.largeFiles?.firstOrNull { it.key == review.itemKey } ?: return
        estimateJob?.cancel()
        estimateJob = null
        pendingReencode = PendingReencode(item, review.preset, mode)
        updateReady {
            it.copy(
                reencodeReview = null,
                errorMessage = null,
                infoMessage = null,
            )
        }

        viewModelScope.launch {
            try {
                val writeRequest = if (mode == MediaReencodeMode.ReplaceOriginal) {
                    cleanerRepository.createMediaWriteRequest(item)
                } else {
                    null
                }
                if (writeRequest != null) {
                    updateReady {
                        it.copy(
                            awaitingMediaWriteConfirmation = true,
                            errorMessage = null,
                            infoMessage = null,
                        )
                    }
                    effectChannel.send(CleanerEffect.LaunchMediaWrite(writeRequest))
                } else {
                    startReencodeExecution()
                }
            } catch (cancellation: CancellationException) {
                pendingReencode = null
                throw cancellation
            } catch (_: Exception) {
                pendingReencode = null
                updateReady {
                    it.copy(
                        awaitingMediaWriteConfirmation = false,
                        errorMessage = "Android could not prepare write access for the original. No file was changed.",
                    )
                }
            }
        }
    }

    fun onMediaWriteResult(approved: Boolean) {
        val pending = pendingReencode
        if (pending == null) {
            updateReady {
                it.copy(
                    awaitingMediaWriteConfirmation = false,
                    infoMessage = if (approved) {
                        "Android returned a media-write approval after the operation state was lost. No replacement was attempted; re-scan and start again."
                    } else {
                        "Media replacement was cancelled."
                    },
                )
            }
            return
        }
        if (!approved) {
            pendingReencode = null
            updateReady {
                it.copy(
                    awaitingMediaWriteConfirmation = false,
                    infoMessage = "Replace original cancelled. The source was not changed.",
                )
            }
            return
        }
        updateReady {
            it.copy(
                awaitingMediaWriteConfirmation = false,
                errorMessage = null,
                infoMessage = null,
            )
        }
        startReencodeExecution()
    }

    fun cancelReencode() {
        val progress = currentReady()?.reencodeProgress ?: return
        if (progress.cancellable) reencodeJob?.cancel()
    }

    private fun loadReencodeEstimate(
        item: CleanableItem,
        preset: MediaReencodePreset,
    ) {
        estimateJob?.cancel()
        updateReady {
            it.copy(
                reencodeReview = MediaReencodeReview(
                    itemKey = item.key,
                    preset = preset,
                    isEstimating = true,
                ),
                errorMessage = null,
                infoMessage = null,
            )
        }
        estimateJob = viewModelScope.launch {
            try {
                val estimate = cleanerRepository.estimateReencode(item, preset)
                updateReady { state ->
                    val current = state.reencodeReview
                    if (current?.itemKey != item.key || current.preset != preset) {
                        state
                    } else {
                        state.copy(
                            reencodeReview = current.copy(
                                estimate = estimate,
                                isEstimating = false,
                                errorMessage = estimate.unavailableReason,
                            ),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateReady { state ->
                    val current = state.reencodeReview
                    if (current?.itemKey != item.key || current.preset != preset) {
                        state
                    } else {
                        state.copy(
                            reencodeReview = current.copy(
                                isEstimating = false,
                                errorMessage = "A safe size estimate could not be produced for this media item.",
                            ),
                        )
                    }
                }
            } finally {
                estimateJob = null
            }
        }
    }

    private fun startReencodeExecution() {
        val pending = pendingReencode ?: return
        if (reencodeJob?.isActive == true) return
        reencodeJob = viewModelScope.launch {
            try {
                val outcome = cleanerRepository.reencode(
                    item = pending.item,
                    preset = pending.preset,
                    mode = pending.mode,
                ) { progress ->
                    updateReady {
                        it.copy(
                            reencodeProgress = progress,
                            awaitingMediaWriteConfirmation = false,
                            errorMessage = null,
                        )
                    }
                }
                val historyRecorded = runCatchingCancellable {
                    historyRepository.record(
                        OptimizationRecord(
                            id = 0L,
                            actionType = when (outcome.mode) {
                                MediaReencodeMode.SaveAsCopy -> "media_reencode_copy"
                                MediaReencodeMode.ReplaceOriginal -> "media_reencode_replace"
                            },
                            scope = pending.item.displayName,
                            createdAtEpochMillis = timeProvider.nowEpochMillis(),
                            outcome = OptimizationOutcome.Succeeded,
                            bytesChanged = when (outcome.mode) {
                                MediaReencodeMode.SaveAsCopy -> -outcome.outputBytes
                                MediaReencodeMode.ReplaceOriginal -> outcome.savedBytes
                            },
                            reversibleUntilEpochMillis = null,
                        ),
                    )
                }.isSuccess
                pendingReencode = null
                reencodeJob = null
                updateReady {
                    val message = when (outcome.mode) {
                        MediaReencodeMode.SaveAsCopy ->
                            "Compressed copy saved (${formatBytes(outcome.outputBytes)}). The original was kept unchanged."
                        MediaReencodeMode.ReplaceOriginal ->
                            "Original replaced after verification. Saved ${formatBytes(outcome.savedBytes)}."
                    }
                    it.copy(
                        reencodeProgress = null,
                        selectedKeys = it.selectedKeys - pending.item.key,
                        infoMessage = message + if (historyRecorded) "" else " The local history entry could not be saved.",
                    )
                }
                startScan()
            } catch (cancellation: CancellationException) {
                pendingReencode = null
                reencodeJob = null
                withContext(NonCancellable) {
                    updateReady {
                        it.copy(
                            reencodeProgress = null,
                            awaitingMediaWriteConfirmation = false,
                            infoMessage = "Media compression cancelled. Partial staged/copy output was discarded.",
                        )
                    }
                }
                throw cancellation
            } catch (error: Exception) {
                pendingReencode = null
                reencodeJob = null
                updateReady {
                    it.copy(
                        reencodeProgress = null,
                        awaitingMediaWriteConfirmation = false,
                        errorMessage = error.message
                            ?.take(MAX_OPERATION_MESSAGE_CHARS)
                            ?: "Media compression failed safely. Re-scan storage before trying again.",
                    )
                }
            }
        }
    }

    fun toggleSelection(itemKey: String) {
        if (currentReady()?.isBusy == true || pendingRemoval != null || pendingReencode != null) return
        updateReady { state ->
            val scan = state.scan ?: return@updateReady state
            val item = scan.items.firstOrNull { it.key == itemKey } ?: return@updateReady state
            if (!item.canDelete) return@updateReady state
            val selected = state.selectedKeys.toMutableSet()
            if (!selected.add(itemKey)) selected.remove(itemKey)
            state.copy(selectedKeys = selected)
        }
    }

    fun selectDuplicateRedundant(groupId: String, strategy: DuplicateKeepStrategy) {
        if (currentReady()?.isBusy == true || pendingRemoval != null || pendingReencode != null) return
        updateReady { state ->
            val group = state.scan?.duplicateGroups?.firstOrNull { it.id == groupId } ?: return@updateReady state
            val keepKey = when (strategy) {
                DuplicateKeepStrategy.BestQuality -> group.bestQualityKey
                DuplicateKeepStrategy.Newest -> group.newestKey
            }
            val selected = state.selectedKeys.toMutableSet()
            // Replace this group's prior duplicate selection so changing strategy is predictable.
            group.items.forEach { selected.remove(it.key) }
            group.items
                .filter { it.key != keepKey && it.canDelete }
                .forEach { selected.add(it.key) }
            state.copy(selectedKeys = selected)
        }
    }

    fun selectAllSuspectedJunk() {
        if (currentReady()?.isBusy == true || pendingRemoval != null || pendingReencode != null) return
        updateReady { state ->
            val selected = state.selectedKeys.toMutableSet()
            state.scan?.suspectedJunk.orEmpty()
                .filter {
                    it.canDelete && (it.category == CleanerCategory.Temporary || it.category == CleanerCategory.Log)
                }
                .forEach { selected.add(it.key) }
            state.copy(selectedKeys = selected)
        }
    }

    fun clearSelection() {
        if (currentReady()?.isBusy == true || pendingRemoval != null || pendingReencode != null) return
        updateReady { it.copy(selectedKeys = emptySet()) }
    }

    fun beginRemoval(mode: MediaRemovalMode) {
        if (pendingRemoval != null || pendingReencode != null || reencodeJob?.isActive == true) return
        if (scanJob?.isActive == true) {
            updateReady { it.copy(errorMessage = "Wait for the current scan/analysis to finish or cancel it before removing files.") }
            return
        }
        val state = currentReady() ?: return
        val scan = state.scan ?: return
        val selectedItems = scan.items.filter { it.key in state.selectedKeys && it.canDelete }
        val items = deduplicateRemovalItems(selectedItems)
        if (items.isEmpty()) {
            updateReady { it.copy(errorMessage = "Select at least one removable item first.") }
            return
        }

        val mediaCount = items.count { it.origin == CleanerOrigin.MediaStore }
        val directCount = items.size - if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) mediaCount else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaCount > MAX_MEDIA_REQUEST_ITEMS) {
            updateReady { it.copy(errorMessage = "Select at most $MAX_MEDIA_REQUEST_ITEMS media items per removal request on this Android version.") }
            return
        }
        if (directCount > MAX_DIRECT_REMOVAL_ITEMS) {
            updateReady {
                it.copy(
                    errorMessage = "Select at most $MAX_DIRECT_REMOVAL_ITEMS directly removed items per operation. Smaller batches reduce provider timeouts and partial operations.",
                )
            }
            return
        }

        pendingRemoval = PendingRemoval(items, mode)
        try {
            val request = cleanerRepository.createMediaRemovalRequest(items, mode)
            if (request != null) {
                updateReady {
                    it.copy(
                        awaitingRemovalConfirmation = true,
                        isRemoving = false,
                        errorMessage = null,
                        infoMessage = null,
                    )
                }
                viewModelScope.launch { effectChannel.send(CleanerEffect.LaunchMediaRemoval(request)) }
            } else {
                updateReady {
                    it.copy(
                        awaitingRemovalConfirmation = false,
                        isRemoving = true,
                        errorMessage = null,
                        infoMessage = null,
                    )
                }
                scanJob = viewModelScope.launch { completeRemoval(mediaApproved = true) }
            }
        } catch (_: Exception) {
            pendingRemoval = null
            updateReady {
                it.copy(
                    awaitingRemovalConfirmation = false,
                    isRemoving = false,
                    errorMessage = "Android could not create the requested removal confirmation. No files were changed.",
                )
            }
        }
    }

    fun onMediaRemovalResult(approved: Boolean) {
        if (pendingRemoval == null) {
            // ActivityResultRegistry can redeliver the system result after process recreation.
            // The MediaStore operation is authoritative when RESULT_OK is delivered, but the
            // in-memory SAF selection is intentionally not reconstructed or guessed. Reconcile
            // storage from source-of-truth providers and leave any direct-document operation
            // untouched. This prevents accidental deletion after lost process state.
            viewModelScope.launch { recoverAfterLostRemovalState(approved) }
            return
        }
        if (!approved) {
            pendingRemoval = null
            updateReady {
                it.copy(
                    awaitingRemovalConfirmation = false,
                    isRemoving = false,
                    infoMessage = "Removal cancelled. No document-provider files were changed.",
                )
            }
            return
        }
        updateReady {
            it.copy(
                awaitingRemovalConfirmation = false,
                isRemoving = true,
                errorMessage = null,
                infoMessage = null,
            )
        }
        scanJob = viewModelScope.launch { completeRemoval(mediaApproved = true) }
    }

    private suspend fun recoverAfterLostRemovalState(approved: Boolean) {
        val previous = currentReady()
        val access = runCatchingCancellable { cleanerRepository.accessState() }.getOrElse { previous?.access ?: EMPTY_ACCESS }
        val persisted = runCatchingCancellable { cleanerRepository.persistedAccess() }.getOrElse { previous?.persistedAccess.orEmpty() }
        _uiState.value = CleanerUiState.Ready(
            access = access,
            persistedAccess = persisted,
            scan = previous?.scan,
            selectedKeys = emptySet(),
            awaitingRemovalConfirmation = false,
            isRemoving = false,
            infoMessage = if (approved) {
                "Android completed the confirmed media operation after ApexTuner was recreated. " +
                    "Document-provider items were not changed; storage will be re-scanned to reconcile the result."
            } else {
                "Removal confirmation was dismissed. No document-provider items were changed."
            },
        )
        if (approved) startScan()
    }

    fun reportAccessError(message: String) {
        updateReady { it.copy(errorMessage = message, infoMessage = null) }
    }

    fun dismissMessage() {
        updateReady { it.copy(errorMessage = null, infoMessage = null) }
    }

    private suspend fun completeRemoval(mediaApproved: Boolean) {
        val pending = pendingRemoval ?: return
        try {
            val (media, direct) = withContext(NonCancellable) {
                val committedMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaApproved) {
                    cleanerRepository.evaluateMediaRemoval(pending.items, pending.mode)
                } else {
                    DeletionOutcome(0, 0, 0, 0L)
                }
                // After explicit confirmation, keep the bounded direct-provider commit non-cancellable
                // to avoid intentionally stopping midway through a destructive batch.
                val committedDirect = cleanerRepository.deleteNonPromptItems(pending.items)
                committedMedia to committedDirect
            }
            val combined = DeletionOutcome(
                requested = media.requested + direct.requested,
                deleted = media.deleted + direct.deleted,
                failed = media.failed + direct.failed,
                bytesDeleted = safeAdd(media.bytesDeleted, direct.bytesDeleted),
            )
            val outcome = when {
                combined.failed == 0 -> OptimizationOutcome.Succeeded
                combined.deleted > 0 -> OptimizationOutcome.PartiallySucceeded
                else -> OptimizationOutcome.Failed
            }
            val hasMedia = media.requested > 0
            val hasDirectDocuments = direct.requested > 0
            val actionType = when {
                pending.mode == MediaRemovalMode.Trash && hasMedia && hasDirectDocuments -> "storage_mixed_remove"
                pending.mode == MediaRemovalMode.Trash && hasMedia -> "storage_trash"
                else -> "storage_delete"
            }
            val historyRecorded = runCatchingCancellable {
                historyRepository.record(
                    OptimizationRecord(
                        id = 0L,
                        actionType = actionType,
                        scope = "${pending.items.size} user-selected items",
                        createdAtEpochMillis = timeProvider.nowEpochMillis(),
                        outcome = outcome,
                        bytesChanged = combined.bytesDeleted,
                        reversibleUntilEpochMillis = null,
                    ),
                )
            }.isSuccess
            pendingRemoval = null
            updateReady {
                val successText = when {
                    pending.mode == MediaRemovalMode.Trash && hasMedia && hasDirectDocuments ->
                        "Moved ${media.deleted} media item${if (media.deleted == 1) "" else "s"} to system Trash and permanently deleted ${direct.deleted} document-provider item${if (direct.deleted == 1) "" else "s"}"
                    pending.mode == MediaRemovalMode.Trash && hasMedia ->
                        "Moved ${media.deleted} media item${if (media.deleted == 1) "" else "s"} to system Trash"
                    else ->
                        "Permanently deleted ${combined.deleted} item${if (combined.deleted == 1) "" else "s"}"
                }
                val failureSuffix = if (combined.failed > 0) "; ${combined.failed} item${if (combined.failed == 1) "" else "s"} could not be removed" else ""
                val auditSuffix = if (historyRecorded) "" else " The local history entry could not be saved."
                it.copy(
                    selectedKeys = emptySet(),
                    awaitingRemovalConfirmation = false,
                    isRemoving = false,
                    infoMessage = "$successText$failureSuffix.$auditSuffix",
                )
            }
            scanJob = null
            startScan()
        } catch (cancellation: CancellationException) {
            pendingRemoval = null
            withContext(NonCancellable) { updateReady { it.copy(awaitingRemovalConfirmation = false, isRemoving = false) } }
            throw cancellation
        } catch (_: Exception) {
            pendingRemoval = null
            updateReady { it.copy(awaitingRemovalConfirmation = false, isRemoving = false, errorMessage = "Removal did not complete cleanly. Re-scan storage before trying again.") }
            scanJob = null
            startScan()
        }
    }

    private fun deduplicateRemovalItems(items: List<CleanableItem>): List<CleanableItem> {
        if (items.size < 2) return items
        val byPhysicalIdentity = LinkedHashMap<String, CleanableItem>(items.size)
        for (item in items) {
            val identity = item.identityKey
            val current = byPhysicalIdentity[identity]
            // Prefer MediaStore when both aliases are removable: Android 11+ can provide the
            // platform Trash/system confirmation path, which is safer than direct SAF deletion.
            if (current == null || removalPreference(item) > removalPreference(current)) {
                byPhysicalIdentity[identity] = item
            }
        }
        return byPhysicalIdentity.values.toList()
    }

    private fun removalPreference(item: CleanableItem): Int = when (item.origin) {
        CleanerOrigin.MediaStore -> 30
        CleanerOrigin.SafTree -> 20
        CleanerOrigin.SafDocument -> 20
        CleanerOrigin.SelectedMedia -> 0
    }

    private fun canChangeAccess(): Boolean {
        if (scanJob?.isActive == true || reencodeJob?.isActive == true ||
            pendingRemoval != null || pendingReencode != null
        ) {
            updateReady { it.copy(errorMessage = "Finish or cancel the current cleaner operation before changing storage access.") }
            return false
        }
        return true
    }

    private fun currentReady(): CleanerUiState.Ready? = _uiState.value as? CleanerUiState.Ready

    private inline fun updateReady(transform: (CleanerUiState.Ready) -> CleanerUiState.Ready) {
        val current = currentReady() ?: return
        _uiState.value = transform(current)
    }

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024.0 && unit < units.lastIndex) {
            amount /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "$value ${units[unit]}"
        } else {
            java.lang.String.format(java.util.Locale.ROOT, "%.1f %s", amount, units[unit])
        }
    }

    private fun safeAdd(a: Long, b: Long): Long {
        val positive = b.coerceAtLeast(0L)
        return if (a > Long.MAX_VALUE - positive) Long.MAX_VALUE else a + positive
    }

    private data class PendingRemoval(
        val items: List<CleanableItem>,
        val mode: MediaRemovalMode,
    )

    private data class PendingReencode(
        val item: CleanableItem,
        val preset: MediaReencodePreset,
        val mode: MediaReencodeMode,
    )

    private companion object {
        const val MAX_MEDIA_REQUEST_ITEMS = 2_000
        const val MAX_DIRECT_REMOVAL_ITEMS = 500
        const val MAX_OPERATION_MESSAGE_CHARS = 500
        val EMPTY_ACCESS = CleanerAccessState(
            canReadImages = false,
            canReadVideos = false,
            canReadAudio = false,
            limitedVisualAccess = false,
            legacyMediaWriteGranted = false,
            persistedTrees = 0,
            persistedDocuments = 0,
            usageAccessGranted = false,
        )
    }
}
