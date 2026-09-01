package com.apextuner.feature.tools.systeminfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SystemInfoUiState {
    data object Loading : SystemInfoUiState
    data class Ready(val snapshot: SystemInfoSnapshot) : SystemInfoUiState
    data class Error(val message: String) : SystemInfoUiState
}

@HiltViewModel
class SystemInfoViewModel @Inject constructor(
    private val repository: SystemInfoRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SystemInfoUiState>(SystemInfoUiState.Loading)
    val state: StateFlow<SystemInfoUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = SystemInfoUiState.Loading
            try { _state.value = SystemInfoUiState.Ready(repository.snapshot()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { _state.value = SystemInfoUiState.Error("System information is temporarily unavailable.") }
        }
    }
}
