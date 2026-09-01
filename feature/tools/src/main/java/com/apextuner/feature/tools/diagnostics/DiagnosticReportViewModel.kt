package com.apextuner.feature.tools.diagnostics

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DiagnosticReportViewModel @Inject constructor(
    private val repository: DiagnosticReportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticReportUiState())
    val state: StateFlow<DiagnosticReportUiState> = _state.asStateFlow()

    init { refreshCurrent() }

    fun selectAllSections() {
        _state.update { it.copy(selectedSections = DiagnosticReportSection.entries.toSet()) }
    }

    fun clearSections() {
        _state.update { it.copy(selectedSections = emptySet()) }
    }

    fun toggleSection(section: DiagnosticReportSection) {
        _state.update { state ->
            val next = state.selectedSections.toMutableSet().apply {
                if (!add(section)) remove(section)
            }
            state.copy(selectedSections = next)
        }
    }

    fun captureBaseline() = runCapture { capture ->
        copy(
            baseline = capture,
            current = null,
            message = "Baseline captured. Apply the change you want to evaluate, then tap Refresh after changes.",
        )
    }
    fun refreshCurrent() = runCapture { capture -> copy(current = capture, message = null) }

    fun clearBaseline() { _state.update { it.copy(baseline = null, message = "Baseline cleared.") } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }

    fun export(destination: Uri, format: DiagnosticReportFormat) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val current = repository.capture()
                val snapshot = _state.value
                val bytes = repository.export(destination, format, current, snapshot.baseline, snapshot.selectedSections)
                _state.update { it.copy(current = current, busy = false, message = "Diagnostic report exported locally (${bytes} bytes).") }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false, message = error.message ?: "Diagnostic report export failed.") }
            }
        }
    }

    private fun runCapture(transform: DiagnosticReportUiState.(DiagnosticCapture) -> DiagnosticReportUiState) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val capture = repository.capture()
                _state.update { it.transform(capture).copy(busy = false) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false, message = error.message ?: "Device diagnostics could not be captured.") }
            }
        }
    }
}
