package com.apextuner.feature.files

import android.net.Uri
import android.provider.DocumentsContract
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
class FileManagerViewModel @Inject constructor(
    private val repository: SafFileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<FileManagerUiState>(FileManagerUiState.Loading(null))
    val state: StateFlow<FileManagerUiState> = _state.asStateFlow()
    private val backStack = ArrayDeque<SafLocation>()
    private var operationJob: Job? = null
    private var navigationJob: Job? = null
    private var transferSource: SafNode? = null
    private var transferMove: Boolean = false

    init {
        viewModelScope.launch { loadInitial() }
    }

    fun grantTree(uri: Uri) {
        navigationJob?.cancel()
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                if (!repository.persistTree(uri)) {
                    _state.value = FileManagerUiState.Error("Android did not grant persistent access to this folder.")
                    return@launch
                }
                val roots = repository.persistedTrees()
                val selected = roots.firstOrNull { sameTree(it.uri, uri) }
                if (selected == null) {
                    _state.value = FileManagerUiState.Error("Android granted access, but this folder could not be resolved safely.")
                } else {
                    openLocation(selected, clearHistory = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = FileManagerUiState.Error(error.message ?: "Folder access could not be saved.")
            }
        }
    }

    fun open(node: SafNode) {
        if (operationJob?.isActive == true || navigationJob?.isActive == true) return
        if (!node.isDirectory) {
            val ready = _state.value as? FileManagerUiState.Ready ?: return
            _state.value = ready.copy(selected = if (ready.selected?.uri == node.uri) null else node)
            return
        }
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        backStack.addLast(ready.location)
        navigationJob = viewModelScope.launch {
            openLocation(SafLocation(node.uri, node.displayName), clearHistory = false)
        }
    }

    fun navigateBack(): Boolean {
        if (operationJob?.isActive == true) {
            val ready = _state.value as? FileManagerUiState.Ready
            if (ready != null) {
                _state.value = ready.copy(message = "Cancel the active file operation before navigating.")
            }
            return true
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
        _state.value = ready.copy(selected = if (ready.selected?.uri == node.uri) null else node)
    }

    fun createFolder(name: String) = runOperation("Creating folder…") { ready ->
        repository.createFolder(Uri.parse(ready.location.uri), name)
        "Folder created."
    }

    fun renameSelected(name: String) = runOperation("Renaming…") { ready ->
        val node = requireNotNull(ready.selected) { "Select a file or folder first." }
        repository.rename(Uri.parse(node.uri), name)
        "Renamed successfully."
    }

    fun stageCopy() = stageTransfer(move = false)

    fun stageMove() = stageTransfer(move = true)

    fun cancelTransfer() {
        transferSource = null
        transferMove = false
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        _state.value = ready.copy(transferSource = null, transferMove = false, message = "Pending transfer cleared.")
    }

    fun pasteHere() = runOperation(if (transferMove) "Moving…" else "Copying…") { ready ->
        val source = requireNotNull(transferSource) { "Choose a source item first." }
        require(source.uri != ready.location.uri) { "Source and destination cannot be the same document." }
        if (transferMove) repository.move(Uri.parse(source.uri), Uri.parse(ready.location.uri))
        else repository.copy(Uri.parse(source.uri), Uri.parse(ready.location.uri))
        val action = if (transferMove) "Move" else "Copy"
        transferSource = null
        transferMove = false
        "$action complete."
    }

    private fun stageTransfer(move: Boolean) {
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        val source = ready.selected ?: run {
            _state.value = ready.copy(message = "Select a file or folder first.")
            return
        }
        transferSource = source
        transferMove = move
        _state.value = ready.copy(
            transferSource = source,
            transferMove = move,
            selected = null,
            message = if (move) "Move staged. Open the destination folder, then tap Paste here."
            else "Copy staged. Open the destination folder, then tap Paste here.",
        )
    }

    fun zipSelected(name: String) = runOperation("Creating ZIP…") { ready ->
        val node = requireNotNull(ready.selected) { "Select a file or folder first." }
        repository.zip(Uri.parse(node.uri), Uri.parse(ready.location.uri), name)
        "ZIP archive created."
    }

    fun extractSelected() = runOperation("Extracting ZIP…") { ready ->
        val node = requireNotNull(ready.selected) { "Select a ZIP archive first." }
        require(node.displayName.endsWith(".zip", ignoreCase = true)) { "The selected file is not a ZIP archive." }
        val count = repository.extractZip(Uri.parse(node.uri), Uri.parse(ready.location.uri))
        "Extracted $count entries."
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
            _state.value = FileManagerUiState.Ready(
                location = location,
                entries = repository.list(Uri.parse(location.uri)),
                transferSource = transferSource,
                transferMove = transferMove,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = FileManagerUiState.Error(error.message ?: "This folder could not be read.")
        }
    }

    private fun runOperation(
        busy: String,
        block: suspend (FileManagerUiState.Ready) -> String,
    ) {
        val ready = _state.value as? FileManagerUiState.Ready ?: return
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch {
            _state.value = ready.copy(busyMessage = busy, message = null)
            try {
                val message = block(ready)
                val entries = repository.list(Uri.parse(ready.location.uri))
                _state.value = ready.copy(
                    entries = entries,
                    selected = null,
                    transferSource = transferSource,
                    transferMove = transferMove,
                    busyMessage = null,
                    message = message,
                )
            } catch (cancelled: CancellationException) {
                _state.value = ready.copy(
                    transferSource = transferSource,
                    transferMove = transferMove,
                    busyMessage = null,
                    message = "Operation cancelled.",
                )
                throw cancelled
            } catch (error: Throwable) {
                _state.value = ready.copy(busyMessage = null, message = error.message ?: "File operation failed.")
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

    override fun onCleared() {
        operationJob?.cancel()
        navigationJob?.cancel()
        super.onCleared()
    }
}
