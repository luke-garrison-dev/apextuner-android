package com.apextuner.feature.appmanager

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    private val repository: AppManagerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<AppManagerUiState>(AppManagerUiState.Loading)
    val state: StateFlow<AppManagerUiState> = _state.asStateFlow()
    private val _exportState = MutableStateFlow<ApkExportUiState>(ApkExportUiState.Idle)
    val exportState: StateFlow<ApkExportUiState> = _exportState.asStateFlow()
    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var hasCompletedInitialLoad = false

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        detailJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = AppManagerUiState.Loading
            try {
                _state.value = AppManagerUiState.Ready(repository.loadApps())
                hasCompletedInitialLoad = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = AppManagerUiState.Error(error.message ?: "Android app inventory could not be loaded.")
            }
        }
    }

    fun refreshAfterExternalSettings() {
        if (hasCompletedInitialLoad && refreshJob?.isActive != true) refresh()
    }

    fun setQuery(value: String) = updateReady { copy(query = value.take(MAX_QUERY_LENGTH)) }
    fun setFilter(value: AppKindFilter) = updateReady { copy(kindFilter = value) }
    fun setInsightFilter(value: AppInsightFilter) = updateReady { copy(insightFilter = value) }
    fun setSort(value: AppSort) = updateReady { copy(sort = value) }
    fun clearFilters() = updateReady {
        copy(
            query = "",
            kindFilter = AppKindFilter.All,
            insightFilter = AppInsightFilter.All,
            sort = AppSort.Name,
        )
    }
    fun clearMessage() = updateReady { copy(message = null) }

    fun selectApp(packageName: String) {
        if (!isValidAppPackageName(packageName)) return
        detailJob?.cancel()
        updateReady { copy(selectedPackage = packageName, selectedDetail = null, detailLoading = true, message = null) }
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.loadDetail(packageName)
                updateReady {
                    if (selectedPackage == packageName) copy(selectedDetail = detail, detailLoading = false) else this
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateReady {
                    if (selectedPackage == packageName) copy(
                        detailLoading = false,
                        message = error.message ?: "Application details are unavailable.",
                    ) else this
                }
            }
        }
    }

    fun exportApkBackup(packageName: String, destination: Uri) {
        if (!isValidAppPackageName(packageName) || _exportState.value is ApkExportUiState.Working) return
        viewModelScope.launch {
            _exportState.value = ApkExportUiState.Working
            try {
                val result = repository.exportApkBackup(packageName, destination)
                _exportState.value = ApkExportUiState.Message(
                    "APK backup exported: ${result.apkCount} APK file${if (result.apkCount == 1) "" else "s"}, ${formatExportBytes(result.uncompressedBytes)} uncompressed."
                )
            } catch (cancelled: CancellationException) {
                _exportState.value = ApkExportUiState.Idle
                throw cancelled
            } catch (error: Throwable) {
                _exportState.value = ApkExportUiState.Message(error.message ?: "APK backup could not be exported.", isError = true)
            }
        }
    }

    fun dismissExportMessage() { _exportState.value = ApkExportUiState.Idle }

    fun dismissDetail() {
        detailJob?.cancel()
        updateReady { copy(selectedPackage = null, selectedDetail = null, detailLoading = false) }
    }

    private fun updateReady(transform: AppManagerUiState.Ready.() -> AppManagerUiState.Ready) {
        _state.update { current -> if (current is AppManagerUiState.Ready) current.transform() else current }
    }

    private fun formatExportBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mib < 1024.0) "%.1f MiB".format(java.util.Locale.US, mib) else "%.2f GiB".format(java.util.Locale.US, mib / 1024.0)
    }

    companion object { const val MAX_QUERY_LENGTH = 120 }
}
