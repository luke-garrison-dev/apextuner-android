package com.apextuner.feature.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val repository: SafFileRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<FileManagerUiState>(FileManagerUiState.Loading(null))
    val state: StateFlow<FileManagerUiState> = _state.asStateFlow()
    private val backStack = ArrayDeque<SafLocation>()
    private var operationJob: Job? = null
    private var navigationJob: Job? = null
    private var transferSource: SafNode? = null
    private var transferMove: Boolean = false
    private var lastReady: FileManagerUiState.Ready? = null

    init {
        viewModelScope.launch { loadInitial() }
    }

    fun grantTree(uri: Uri) {
        navigationJob?.cancel()
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                if (!repository.persistTree(uri)) {
                    reportGrantFailure(str(R.string.file_grant_not_persistent))
                    return@launch
                }
                val roots = repository.persistedTrees()
                val selected = roots.firstOrNull { sameTree(it.uri, uri) }
                if (selected == null) {
                    reportGrantFailure(str(R.string.file_grant_unresolved))
                } else {
                    openLocation(selected, clearHistory = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportGrantFailure(error.message ?: str(R.string.file_grant_save_failed))
            }
        }
    }

    fun open(node: SafNode) {
        if (operationJob?.isActive == true || navigationJob?.isActive == true) return
        if (!node.isDirectory) {
            select(node)
            return
        }
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        backStack.addLast(ready.location)
        navigationJob = viewModelScope.launch {
            openLocation(SafLocation(node.uri, node.displayName), clearHistory = false)
        }
    }

    fun navigateBack(): Boolean {
        if (_state.value is FileManagerUiState.NoAccess) return false
        if (operationJob?.isActive == true) {
            val ready = _state.value as? FileManagerUiState.Ready
            if (ready != null) {
                publishReady(ready.copy(message = str(R.string.file_cancel_before_navigate)))
            }
            return true
        }
        if (_state.value is FileManagerUiState.Error) {
            val fallback = lastReady
            if (fallback != null) {
                publishReady(fallback.copy(busyMessage = null))
                return true
            }
        }
        navigationJob?.cancel()
        val previous = if (backStack.isEmpty()) null else backStack.removeLast()
        if (previous == null) return false
        navigationJob = viewModelScope.launch { openLocation(previous, clearHistory = false) }
        return true
    }

    fun select(node: SafNode) {
        if (operationJob?.isActive == true || navigationJob?.isActive == true) return
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        publishReady(ready.copy(selected = if (ready.selected?.uri == node.uri) null else node))
    }

    fun createFolder(name: String) = runOperation(str(R.string.file_creating_folder)) { ready ->
        repository.createFolder(Uri.parse(ready.location.uri), name)
        str(R.string.file_folder_created)
    }

    fun renameSelected(name: String) = runOperation(str(R.string.file_renaming)) { ready ->
        val node = requireNotNull(ready.selected) { str(R.string.file_select_item_first) }
        repository.rename(Uri.parse(node.uri), name)
        str(R.string.file_renamed)
    }

    fun stageCopy() = stageTransfer(move = false)

    fun stageMove() = stageTransfer(move = true)

    fun cancelTransfer() {
        transferSource = null
        transferMove = false
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        publishReady(ready.copy(transferSource = null, transferMove = false, message = str(R.string.file_transfer_cleared)))
    }

    fun pasteHere() = runOperation(if (transferMove) str(R.string.file_moving) else str(R.string.file_copying)) { ready ->
        val source = requireNotNull(transferSource) { str(R.string.file_choose_source_first) }
        require(source.uri != ready.location.uri) { str(R.string.file_same_source_destination) }
        if (transferMove) repository.move(Uri.parse(source.uri), Uri.parse(ready.location.uri))
        else repository.copy(Uri.parse(source.uri), Uri.parse(ready.location.uri))
        val complete = if (transferMove) str(R.string.file_move_complete) else str(R.string.file_copy_complete)
        transferSource = null
        transferMove = false
        complete
    }

    private fun stageTransfer(move: Boolean) {
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        val source = ready.selected ?: run {
            publishReady(ready.copy(message = str(R.string.file_select_item_first)))
            return
        }
        transferSource = source
        transferMove = move
        publishReady(
            ready.copy(
                transferSource = source,
                transferMove = move,
                selected = null,
                message = str(if (move) R.string.file_move_staged else R.string.file_copy_staged),
            ),
        )
    }

    fun zipSelected(name: String) = runOperation(str(R.string.file_creating_zip)) { ready ->
        val node = requireNotNull(ready.selected) { str(R.string.file_select_item_first) }
        repository.zip(Uri.parse(node.uri), Uri.parse(ready.location.uri), name)
        str(R.string.file_zip_created)
    }

    fun extractSelected() = runOperation(str(R.string.file_extracting_zip)) { ready ->
        val node = requireNotNull(ready.selected) { str(R.string.file_select_zip_first) }
        require(node.displayName.endsWith(".zip", ignoreCase = true)) { str(R.string.file_not_a_zip) }
        val count = repository.extractZip(Uri.parse(node.uri), Uri.parse(ready.location.uri))
        appContext.resources.getQuantityString(R.plurals.file_extracted_entries, count, count)
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    private suspend fun loadInitial() {
        val roots = repository.persistedTrees()
        if (roots.isEmpty()) {
            _state.value = FileManagerUiState.NoAccess
        } else {
            openLocation(roots.first(), clearHistory = true)
        }
    }

    private suspend fun openLocation(location: SafLocation, clearHistory: Boolean) {
        if (clearHistory) backStack.clear()
        _state.value = FileManagerUiState.Loading(location)
        try {
            publishReady(
                FileManagerUiState.Ready(
                    location = location,
                    entries = repository.list(Uri.parse(location.uri)),
                    transferSource = transferSource,
                    transferMove = transferMove,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val fallback = lastReady
            if (fallback != null) {
                publishReady(fallback.copy(busyMessage = null, message = error.message ?: str(R.string.file_folder_unreadable)))
            } else {
                _state.value = FileManagerUiState.Error(error.message ?: str(R.string.file_folder_unreadable))
            }
        }
    }

    private fun reportGrantFailure(message: String) {
        val ready = (_state.value as? FileManagerUiState.Ready) ?: lastReady
        if (ready != null) {
            publishReady(ready.copy(busyMessage = null, message = message))
        } else {
            _state.value = FileManagerUiState.Error(message)
        }
    }

    private fun publishReady(ready: FileManagerUiState.Ready) {
        lastReady = ready
        _state.value = ready
    }

    private fun runOperation(
        busy: String,
        block: suspend (FileManagerUiState.Ready) -> String,
    ) {
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            publishReady(ready.copy(busyMessage = busy, message = null))
            try {
                val message = block(ready)
                val entries = repository.list(Uri.parse(ready.location.uri))
                publishReady(
                    ready.copy(
                        entries = entries,
                        selected = null,
                        transferSource = transferSource,
                        transferMove = transferMove,
                        busyMessage = null,
                        message = message,
                    ),
                )
            } catch (cancelled: CancellationException) {
                publishReady(
                    ready.copy(
                        transferSource = transferSource,
                        transferMove = transferMove,
                        busyMessage = null,
                        message = str(R.string.file_operation_cancelled),
                    ),
                )
                throw cancelled
            } catch (error: Throwable) {
                publishReady(ready.copy(busyMessage = null, message = error.message ?: str(R.string.file_operation_failed)))
            }
        }
    }

    private fun sameTree(locationUri: String, grantedUri: Uri): Boolean {
        val locationIdentity = treeIdentity(Uri.parse(locationUri)) ?: return false
        val grantedIdentity = treeIdentity(grantedUri) ?: return false
        return SafTreeIdentityPolicy.same(locationIdentity, grantedIdentity)
    }

    private fun treeIdentity(uri: Uri): SafTreeIdentity? {
        val authority = uri.authority ?: return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return SafTreeIdentity(authority, documentId)
    }

    private fun str(@StringRes id: Int): String = appContext.getString(id)

    override fun onCleared() {
        operationJob?.cancel()
        navigationJob?.cancel()
        super.onCleared()
    }
}
