package com.apextuner.feature.network.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class NetworkDiagnosticsViewModel @Inject constructor(
    private val repository: NetworkDiagnosticsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkDiagnosticsUiState())
    val state: StateFlow<NetworkDiagnosticsUiState> = _state.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    init { refreshQualityHistory() }

    fun runPing(host: String) = launchReplacing(KEY_PING) {
        _state.update { it.copy(ping = DiagnosticRunState.Running("Checking reachability…")) }
        runCatchingCancellable { repository.ping(host) }
            .fold(
                onSuccess = { value -> _state.update { it.copy(ping = DiagnosticRunState.Ready(value)) } },
                onFailure = { error -> _state.update { it.copy(ping = DiagnosticRunState.Error(error.userMessage())) } },
            )
    }

    fun resolveDns(host: String) = launchReplacing(KEY_DNS) {
        _state.update { it.copy(dns = DiagnosticRunState.Running("Resolving DNS…")) }
        runCatchingCancellable { repository.resolveDns(host) }
            .fold(
                onSuccess = { value -> _state.update { it.copy(dns = DiagnosticRunState.Ready(value)) } },
                onFailure = { error -> _state.update { it.copy(dns = DiagnosticRunState.Error(error.userMessage())) } },
            )
    }

    fun testTcp(host: String, portText: String) = launchReplacing(KEY_TCP) {
        val port = portText.toIntOrNull()
        if (port == null) {
            _state.update { it.copy(tcp = DiagnosticRunState.Error("Enter a valid TCP port.")) }
            return@launchReplacing
        }
        _state.update { it.copy(tcp = DiagnosticRunState.Running("Testing TCP connection…")) }
        runCatchingCancellable { repository.testTcp(host, port) }
            .fold(
                onSuccess = { value -> _state.update { it.copy(tcp = DiagnosticRunState.Ready(value)) } },
                onFailure = { error -> _state.update { it.copy(tcp = DiagnosticRunState.Error(error.userMessage())) } },
            )
    }

    fun runQualityTest(host: String, portText: String) = launchReplacing(KEY_QUALITY) {
        val port = portText.toIntOrNull()
        if (port == null) {
            _state.update { it.copy(quality = DiagnosticRunState.Error("Enter a valid TCP port.")) }
            return@launchReplacing
        }
        _state.update { it.copy(quality = DiagnosticRunState.Running("Sampling DNS and TCP handshake quality…")) }
        runCatchingCancellable { repository.qualityTest(host, port) }
            .fold(
                onSuccess = { value ->
                    _state.update { it.copy(quality = DiagnosticRunState.Ready(value)) }
                    refreshQualityHistory()
                },
                onFailure = { error -> _state.update { it.copy(quality = DiagnosticRunState.Error(error.userMessage())) } },
            )
    }


    fun runThroughputTest() = launchReplacing(KEY_THROUGHPUT) {
        _state.update { it.copy(throughput = DiagnosticRunState.Running("Transferring a bounded 5 MB sample…")) }
        runCatchingCancellable { repository.throughputTest() }
            .fold(
                onSuccess = { value -> _state.update { it.copy(throughput = DiagnosticRunState.Ready(value)) } },
                onFailure = { error -> _state.update { it.copy(throughput = DiagnosticRunState.Error(error.userMessage())) } },
            )
    }

    private fun refreshQualityHistory() = launchReplacing(KEY_QUALITY_HISTORY) {
        runCatchingCancellable { repository.qualityHistory() }
            .onSuccess { history -> _state.update { it.copy(qualityHistory = history) } }
    }

    fun scanSubnet() = launchReplacing(KEY_SUBNET) {
        _state.update { it.copy(subnet = DiagnosticRunState.Running("Scanning local subnet at a bounded rate…")) }
        runCatchingCancellable { repository.scanLocalSubnet() }
            .fold(
                onSuccess = { value -> _state.update { it.copy(subnet = DiagnosticRunState.Ready(value)) } },
                onFailure = { error -> _state.update { it.copy(subnet = DiagnosticRunState.Error(error.userMessage())) } },
            )
    }

    fun cancelAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private fun launchReplacing(key: String, block: suspend () -> Unit) {
        jobs.remove(key)?.cancel()
        jobs[key] = viewModelScope.launch {
            try {
                block()
            } finally {
                jobs.remove(key)
            }
        }
    }

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun Throwable.userMessage(): String = message ?: "The diagnostic could not be completed."

    private companion object {
        const val KEY_PING = "ping"
        const val KEY_DNS = "dns"
        const val KEY_TCP = "tcp"
        const val KEY_SUBNET = "subnet"
        const val KEY_QUALITY = "quality"
        const val KEY_THROUGHPUT = "throughput"
        const val KEY_QUALITY_HISTORY = "quality_history"
    }
}
