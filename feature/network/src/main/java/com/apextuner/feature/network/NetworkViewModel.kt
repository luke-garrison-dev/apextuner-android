package com.apextuner.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.feature.network.firewall.FirewallRuntimeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repository: NetworkRepository,
    private val dataUsageAlertScheduler: DataUsageAlertScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow<NetworkUiState>(NetworkUiState.Loading)
    val state: StateFlow<NetworkUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var runtimeJob: Job? = null
    private val selectionRequests = Channel<FirewallSelectionRequest>(capacity = Channel.UNLIMITED)

    init {
        refresh()
        runtimeJob = viewModelScope.launch {
            FirewallRuntimeRegistry.runtime.collect { runtime ->
                val current = _state.value
                if (current is NetworkUiState.Ready) {
                    val authoritativeSelection = if (runtime.state == FirewallRuntimeState.Active) runtime.packages else current.snapshot.firewallStatus.selectedPackages
                    _state.value = current.copy(
                        snapshot = current.snapshot.copy(
                            firewallApps = current.snapshot.firewallApps.map { app -> app.copy(selected = app.packageName in authoritativeSelection) },
                            firewallStatus = current.snapshot.firewallStatus.copy(
                                runtimeState = runtime.state,
                                selectedPackages = authoritativeSelection,
                                lastError = runtime.error,
                            ),
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            for (request in selectionRequests) applyFirewallSelection(request)
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        val current = _state.value
        if (current is NetworkUiState.Ready) _state.value = current.copy(refreshing = true, message = null)
        else _state.value = NetworkUiState.Loading
        refreshJob = viewModelScope.launch {
            try {
                val snapshot = repository.loadSnapshot()
                _state.value = NetworkUiState.Ready(snapshot)
            } catch (cancelled: CancellationException) {
                val latest = _state.value
                if (latest is NetworkUiState.Ready && latest.refreshing) {
                    _state.value = latest.copy(refreshing = false)
                }
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message ?: "Network diagnostics are currently unavailable."
                val latest = _state.value
                _state.value = if (latest is NetworkUiState.Ready) latest.copy(refreshing = false, message = message)
                else NetworkUiState.Error(message)
            }
        }
    }


    fun setFirewallProfile(profile: FirewallProfile) {
        val current = _state.value as? NetworkUiState.Ready ?: return
        if (current.snapshot.firewallStatus.runtimeState in setOf(FirewallRuntimeState.Active, FirewallRuntimeState.Starting)) {
            _state.value = current.copy(message = "Stop the firewall before switching profiles.")
            return
        }
        cancelRefreshForMutation(current)
        viewModelScope.launch {
            try {
                val selectedPackages = repository.setFirewallProfile(profile)
                val latest = _state.value as? NetworkUiState.Ready ?: return@launch
                _state.value = latest.copy(
                    snapshot = latest.snapshot.copy(
                        firewallApps = latest.snapshot.firewallApps.map { app -> app.copy(selected = app.packageName in selectedPackages) },
                        firewallStatus = latest.snapshot.firewallStatus.copy(profile = profile, selectedPackages = selectedPackages),
                    ),
                    message = null,
                )
            } catch (cancelled: CancellationException) {
                val latest = _state.value
                if (latest is NetworkUiState.Ready && latest.refreshing) {
                    _state.value = latest.copy(refreshing = false)
                }
                throw cancelled
            } catch (error: Throwable) {
                val latest = _state.value as? NetworkUiState.Ready ?: return@launch
                _state.value = latest.copy(message = error.message ?: "The firewall profile could not be changed.")
            }
        }
    }

    fun setFirewallPackageSelected(packageName: String, selected: Boolean) {
        val current = _state.value as? NetworkUiState.Ready ?: return
        if (!isSafePackageName(packageName)) {
            _state.value = current.copy(message = "Invalid application package.")
            return
        }
        if (current.snapshot.firewallStatus.runtimeState == FirewallRuntimeState.Active || current.snapshot.firewallStatus.runtimeState == FirewallRuntimeState.Starting) {
            _state.value = current.copy(message = "Stop the firewall before changing its app list.")
            return
        }
        val afterCancel = cancelRefreshForMutation(current)
        if (selectionRequests.trySend(FirewallSelectionRequest(packageName, selected)).isFailure) {
            _state.value = afterCancel.copy(message = "The firewall selection queue is unavailable.")
        }
    }

    private suspend fun applyFirewallSelection(request: FirewallSelectionRequest) {
        val runtime = FirewallRuntimeRegistry.runtime.value.state
        if (runtime == FirewallRuntimeState.Active || runtime == FirewallRuntimeState.Starting) {
            val latest = _state.value as? NetworkUiState.Ready ?: return
            _state.value = latest.copy(refreshing = false, message = "Stop the firewall before changing its app list.")
            return
        }
        try {
            val selectedPackages = repository.setFirewallPackageSelected(request.packageName, request.selected)
            val latest = _state.value as? NetworkUiState.Ready ?: return
            _state.value = latest.copy(
                snapshot = latest.snapshot.copy(
                    firewallApps = latest.snapshot.firewallApps.map { app -> app.copy(selected = app.packageName in selectedPackages) },
                    firewallStatus = latest.snapshot.firewallStatus.copy(selectedPackages = selectedPackages),
                ),
                refreshing = false,
                message = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val latest = _state.value as? NetworkUiState.Ready ?: return
            _state.value = latest.copy(refreshing = false, message = error.message ?: "The firewall selection could not be saved.")
        }
    }


    fun setMonthlyDataCap(packageName: String, megabytesText: String, notificationsAllowed: Boolean = true) {
        val current = _state.value as? NetworkUiState.Ready ?: return
        val bytes = if (megabytesText.isBlank()) {
            null
        } else {
            val megabytes = megabytesText.toLongOrNull()
            if (megabytes == null || megabytes <= 0L) {
                _state.value = current.copy(message = "Enter a positive threshold in MB, or leave it blank to remove the alert.")
                return
            }
            runCatching { Math.multiplyExact(megabytes, 1024L * 1024L) }.getOrNull()
                ?: run {
                    _state.value = current.copy(message = "The threshold is too large.")
                    return
                }
        }
        cancelRefreshForMutation(current)
        viewModelScope.launch {
            try {
                val caps = repository.setDataUsageCap(packageName, bytes)
                dataUsageAlertScheduler.sync(caps.isNotEmpty())
                val usage = try {
                    repository.loadMonthlyUsageForPackages(caps.keys)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                val latest = _state.value as? NetworkUiState.Ready ?: return@launch
                _state.value = latest.copy(
                    snapshot = latest.snapshot.copy(
                        monthlyDataCaps = caps,
                        monthlyDataCapUsage = usage ?: latest.snapshot.monthlyDataCapUsage.filterKeys(caps::containsKey),
                    ),
                    message = when {
                        bytes == null -> "Data usage alert removed."
                        !notificationsAllowed -> "Monthly data usage alert saved, but ApexTuner cannot notify you until notification permission is granted."
                        usage == null -> "Monthly data usage alert saved. Current usage could not be refreshed yet."
                        else -> "Monthly data usage alert saved."
                    },
                )
            } catch (cancelled: CancellationException) {
                val latest = _state.value
                if (latest is NetworkUiState.Ready && latest.refreshing) {
                    _state.value = latest.copy(refreshing = false)
                }
                throw cancelled
            } catch (error: Throwable) {
                val latest = _state.value as? NetworkUiState.Ready ?: return@launch
                _state.value = latest.copy(message = error.message ?: "The data usage alert could not be saved.")
            }
        }
    }

    private fun cancelRefreshForMutation(fallback: NetworkUiState.Ready): NetworkUiState.Ready {
        refreshJob?.cancel()
        refreshJob = null
        val stable = (_state.value as? NetworkUiState.Ready ?: fallback).copy(refreshing = false)
        _state.value = stable
        return stable
    }

    fun clearMessage() {
        val current = _state.value
        if (current is NetworkUiState.Ready) _state.value = current.copy(message = null)
    }

    override fun onCleared() {
        selectionRequests.close()
        super.onCleared()
    }

    private data class FirewallSelectionRequest(val packageName: String, val selected: Boolean)
}
