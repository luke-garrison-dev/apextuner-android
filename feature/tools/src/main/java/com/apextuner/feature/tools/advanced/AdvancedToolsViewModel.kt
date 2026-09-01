package com.apextuner.feature.tools.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.tuning.ProfileApplyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltViewModel
class AdvancedToolsViewModel @Inject constructor(
    private val repository: AdvancedToolsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<AdvancedToolsUiState>(AdvancedToolsUiState.Loading)
    val state: StateFlow<AdvancedToolsUiState> = _state.asStateFlow()
    private var operationJob: Job? = null
    private var refreshJob: Job? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == AdvancedToolsRepository.SHIZUKU_PERMISSION_REQUEST_CODE) refresh()
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    init {
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val current = _state.value as? AdvancedToolsUiState.Ready
                _state.value = AdvancedToolsUiState.Ready(
                    status = repository.status(),
                    selectedBackend = current?.selectedBackend ?: PrivilegedBackend.Shizuku,
                    busy = current?.busy ?: false,
                    output = current?.output,
                    message = current?.message,
                    savedAnimationBaseline = repository.savedAnimationBaseline(),
                    packageTargets = repository.packageTargets(),
                    cpuTuningStatus = current?.cpuTuningStatus ?: CpuTuningStatus(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = AdvancedToolsUiState.Error(error.message ?: "Advanced access status is unavailable.")
            }
        }
    }

    fun setBackend(backend: PrivilegedBackend) = updateReady { copy(selectedBackend = backend, message = null) }

    fun requestShizukuPermission() {
        runCatching { repository.requestShizukuPermission() }
            .onSuccess { updateReady { copy(message = "Complete the Shizuku permission request.") } }
            .onFailure { error -> updateReady { copy(message = error.message ?: "Shizuku permission could not be requested.") } }
    }

    fun testRoot() = runCommand { _ ->
        val state = repository.testRootAuthorization()
        PrivilegedCommandResult(PrivilegedBackend.Root, state == RootAuthorizationState.Granted, "Root authorization: ${state.name}")
    }

    fun execute(command: PrivilegedReadCommand) = runCommand { backend -> repository.executeReadOnly(backend, command) }
    fun applyFastAnimations() = runCommand { backend -> repository.applyAnimationPreset(backend, AnimationScales(0.5f, 0.5f, 0.5f)) }
    fun applyNormalAnimations() = runCommand { backend -> repository.applyAnimationPreset(backend, AnimationScales(1f, 1f, 1f)) }
    fun restoreAnimations() = runCommand { backend -> repository.restoreAnimationBaseline(backend) }
    fun freeze(packageName: String) = runCommand(refreshAfter = true) { backend -> repository.freezePackage(backend, packageName) }
    fun unfreeze(packageName: String) = runCommand(refreshAfter = true) { backend -> repository.unfreezePackage(backend, packageName) }
    fun forceStop(packageName: String) = runCommand { backend -> repository.forceStopPackage(backend, packageName) }

    fun inspectCpu() {
        runProfileOperation { backend ->
            val status = repository.inspectCpuTuning(backend)
            updateReady { copy(cpuTuningStatus = status) }
            "Verified ${status.availablePolicies} CPU policy/policies. Thermal management remains platform controlled."
        }
    }

    fun applyExtended(profile: SystemProfile) {
        require(profile != SystemProfile.Balanced)
        runProfileOperation { backend -> profileMessage(repository.applyExtendedProfile(backend, profile)) }
    }

    fun restoreExtended() = runProfileOperation { backend -> profileMessage(repository.restoreExtendedProfile(backend)) }

    fun explainCacheSafety() = updateReady {
        copy(
            message = when (repository.cacheMaintenanceCapability()) {
                CacheMaintenanceCapability.RollbackSafeUnavailable ->
                    "Rollback-safe ART/Dalvik cache clearing is unavailable. Android exposes cache/ART reset operations, but not a transactional restore primitive, so ApexTuner refuses this mutation."
            },
        )
    }

    private fun runCommand(
        refreshAfter: Boolean = false,
        block: suspend (PrivilegedBackend) -> PrivilegedCommandResult,
    ) {
        if (operationJob?.isActive == true) return
        val current = _state.value as? AdvancedToolsUiState.Ready ?: return
        val backend = current.selectedBackend
        _state.value = current.copy(busy = true, message = null)
        operationJob = viewModelScope.launch {
            try {
                val result = block(backend)
                updateReady { copy(busy = false, output = result.output, message = if (result.success) "Operation completed and verified." else "Operation failed; review the result.") }
                if (refreshAfter) refresh()
            } catch (cancelled: CancellationException) {
                updateReady { copy(busy = false) }
                throw cancelled
            } catch (error: Throwable) {
                updateReady { copy(busy = false, message = error.message ?: "Advanced operation failed safely.") }
            }
        }
    }

    private fun runProfileOperation(block: suspend (PrivilegedBackend) -> String) {
        if (operationJob?.isActive == true) return
        val current = _state.value as? AdvancedToolsUiState.Ready ?: return
        val backend = current.selectedBackend
        _state.value = current.copy(busy = true, message = null)
        operationJob = viewModelScope.launch {
            try {
                val message = block(backend)
                updateReady { copy(busy = false, message = message) }
            } catch (cancelled: CancellationException) {
                updateReady { copy(busy = false) }
                throw cancelled
            } catch (error: Throwable) {
                updateReady { copy(busy = false, message = error.message ?: "Privileged profile operation failed safely.") }
            }
        }
    }

    private fun profileMessage(result: ProfileApplyResult): String = when (result) {
        is ProfileApplyResult.Applied -> "${result.profile.name} profile applied. ${result.changedSettings.joinToString().ifBlank { "No setting required a change." }}"
        is ProfileApplyResult.PermissionRequired -> "Modify system settings access is required before the profile can be applied."
        is ProfileApplyResult.Superseded -> "A newer profile request superseded this operation."
        is ProfileApplyResult.Failed -> result.reason
    }

    private inline fun updateReady(transform: AdvancedToolsUiState.Ready.() -> AdvancedToolsUiState.Ready) {
        val ready = _state.value as? AdvancedToolsUiState.Ready ?: return
        _state.value = ready.transform()
    }

    override fun onCleared() {
        refreshJob?.cancel()
        operationJob?.cancel()
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        super.onCleared()
    }
}
