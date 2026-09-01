package com.apextuner.feature.tools.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val repository: SecurityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SecurityUiState>(SecurityUiState.Loading)
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        val existing = _state.value as? SecurityUiState.Ready
        if (existing == null) _state.value = SecurityUiState.Loading
        refreshJob = viewModelScope.launch {
            try {
                _state.value = SecurityUiState.Ready(repository.snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message ?: "Security posture could not be read."
                val latest = _state.value
                _state.value = if (latest is SecurityUiState.Ready) latest.copy(message = message) else SecurityUiState.Error(message)
            }
        }
    }

    fun clearClipboard() {
        val current = _state.value as? SecurityUiState.Ready ?: return
        val cleared = repository.clearClipboard()
        _state.value = current.copy(message = if (cleared) "Clipboard cleared." else "Android did not allow ApexTuner to clear the clipboard.")
    }
}
