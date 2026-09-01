package com.apextuner.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ContactMergeViewModel @Inject constructor(
    private val repository: ContactRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ContactToolUiState>(
        if (repository.hasPermissions()) ContactToolUiState.Loading else ContactToolUiState.NeedsPermission,
    )
    val state: StateFlow<ContactToolUiState> = _state.asStateFlow()
    private val undoHistory = ContactUndoHistory()
    private var job: Job? = null

    init {
        if (repository.hasPermissions()) scan()
    }

    fun onPermissionResult(granted: Boolean) {
        if (!granted || !repository.hasPermissions()) {
            _state.value = ContactToolUiState.NeedsPermission
            return
        }
        scan()
    }

    fun scan() {
        if (!repository.hasPermissions()) {
            _state.value = ContactToolUiState.NeedsPermission
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = ContactToolUiState.Loading
            try {
                val candidates = repository.findDuplicates()
                _state.value = ContactToolUiState.Ready(
                    candidates,
                    undoAvailable = undoHistory.hasUndo,
                    undoBlockedByFailure = undoHistory.topFailed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = ContactToolUiState.Error(error.message ?: "Contacts could not be analyzed.")
            }
        }
    }

    fun merge(candidate: ContactDuplicateCandidate) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                val snapshot = repository.merge(candidate)
                undoHistory.push(snapshot)
                val candidates = repository.findDuplicates()
                _state.value = ContactToolUiState.Ready(
                    candidates,
                    undoAvailable = true,
                    undoBlockedByFailure = false,
                    message = "Merged ${snapshot.firstDisplayName} and ${snapshot.secondDisplayName}. Undo remains available until you leave this screen.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val current = _state.value as? ContactToolUiState.Ready
                _state.value = current?.copy(message = error.message ?: "The merge could not be completed.")
                    ?: ContactToolUiState.Error(error.message ?: "The merge could not be completed.")
            }
        }
    }

    fun undoLastMerge() {
        val snapshot = undoHistory.peek() ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                repository.undo(snapshot)
                check(undoHistory.complete(snapshot)) { "Contact undo history changed while an undo was running." }
                val candidates = repository.findDuplicates()
                _state.value = ContactToolUiState.Ready(
                    candidates,
                    undoAvailable = undoHistory.hasUndo,
                    undoBlockedByFailure = undoHistory.topFailed,
                    message = "Last merge was undone.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                undoHistory.markFailed(snapshot)
                val current = _state.value as? ContactToolUiState.Ready
                _state.value = current?.copy(
                    undoAvailable = true,
                    undoBlockedByFailure = true,
                    message = error.message ?: "Undo failed; Android kept the current contact aggregation.",
                ) ?: ContactToolUiState.Error(error.message ?: "Undo failed.")
            }
        }
    }

    fun discardFailedUndo() {
        if (job?.isActive == true) return
        if (undoHistory.discardFailedTop() == null) return
        val current = _state.value as? ContactToolUiState.Ready ?: return
        _state.value = current.copy(
            undoAvailable = undoHistory.hasUndo,
            undoBlockedByFailure = undoHistory.topFailed,
            message = "The failed undo record was discarded. Earlier merge undos remain available.",
        )
    }

    override fun onCleared() {
        job?.cancel()
        undoHistory.clear()
        super.onCleared()
    }
}
