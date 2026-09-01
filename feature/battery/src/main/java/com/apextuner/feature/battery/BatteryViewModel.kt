package com.apextuner.feature.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.feature.battery.model.BatteryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val repository: BatteryRepository,
) : ViewModel() {
    private val restartGeneration = MutableStateFlow(0L)
    private val pendingMessage = AtomicReference<String?>(null)
    private var profileActionJob: Job? = null

    val state = restartGeneration
        .flatMapLatest { batteryStream() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MILLIS),
            initialValue = BatteryUiState.Loading,
        )

    fun retry() {
        restartGeneration.update { it + 1L }
    }

    fun applyBatteryProfile() = runProfileAction(SystemProfile.Battery) { repository.applyBatteryProfile() }
    fun restoreBalanced() = runProfileAction(SystemProfile.Balanced) { repository.restoreBalanced() }

    private fun runProfileAction(requestedProfile: SystemProfile, block: suspend () -> ProfileApplyResult) {
        if (profileActionJob?.isActive == true) return
        profileActionJob = viewModelScope.launch {
            val result = try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ProfileApplyResult.Failed(
                    profile = requestedProfile,
                    reason = error.message ?: "The profile operation could not be completed safely.",
                )
            }
            pendingMessage.set(result.toUserMessage())
            restartGeneration.update { it + 1L }
        }
    }

    private fun batteryStream(): Flow<BatteryUiState> = flow {
        emit(BatteryUiState.Loading)
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            try {
                val insights = repository.readInsights()
                consecutiveFailures = 0
                emit(BatteryUiState.Ready(insights, pendingMessage.getAndSet(null)))
                delay(REFRESH_MILLIS)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                consecutiveFailures += 1
                if (consecutiveFailures > MAX_CONSECUTIVE_AUTOMATIC_RETRIES) {
                    emit(BatteryUiState.Error(error.message ?: "Battery data could not be read."))
                    return@flow
                }
                delay(RETRY_BASE_DELAY_MILLIS * consecutiveFailures.toLong())
            }
        }
    }

    private fun ProfileApplyResult.toUserMessage(): String = when (this) {
        is ProfileApplyResult.Applied -> {
            val applied = if (changedSettings.isEmpty()) {
                "${profile.name} profile already matches the Android settings ApexTuner can safely manage."
            } else {
                "${profile.name} applied safely: ${changedSettings.joinToString()}."
            }
            if (privilegedChangesUnavailable.isEmpty()) applied
            else "$applied Not changed by ApexTuner: ${privilegedChangesUnavailable.joinToString("; ")}."
        }
        is ProfileApplyResult.Superseded -> "A newer profile request replaced this one."
        is ProfileApplyResult.PermissionRequired -> "Android requires explicit Modify system settings access."
        is ProfileApplyResult.Failed -> reason
    }

    private companion object {
        const val REFRESH_MILLIS = 5_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_CONSECUTIVE_AUTOMATIC_RETRIES = 2
        const val RETRY_BASE_DELAY_MILLIS = 750L
    }
}
