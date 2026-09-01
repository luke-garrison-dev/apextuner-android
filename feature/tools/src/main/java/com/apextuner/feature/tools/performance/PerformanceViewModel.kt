package com.apextuner.feature.tools.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.tuning.ProfileApplyResult
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
class PerformanceViewModel @Inject constructor(
    private val repository: PerformanceRepository,
) : ViewModel() {
    private val restartGeneration = MutableStateFlow(0L)
    private val pendingMessage = AtomicReference<String?>(null)
    private var profileActionJob: Job? = null

    val state = restartGeneration
        .flatMapLatest { performanceStream() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MILLIS),
            initialValue = PerformanceUiState.Loading,
        )

    fun refresh() {
        restartGeneration.update { it + 1L }
    }

    fun applyProfile(profile: SystemProfile) {
        if (profileActionJob?.isActive == true) return
        profileActionJob = viewModelScope.launch {
            val result = try {
                repository.applyProfile(profile)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ProfileApplyResult.Failed(profile, error.message ?: "The profile operation could not be completed safely.")
            }
            pendingMessage.set(result.toUserMessage())
            restartGeneration.update { it + 1L }
        }
    }

    private fun performanceStream(): Flow<PerformanceUiState> = flow {
        emit(PerformanceUiState.Loading)
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            try {
                val insights = repository.readInsights()
                consecutiveFailures = 0
                emit(PerformanceUiState.Ready(insights, pendingMessage.getAndSet(null)))
                delay(REFRESH_MILLIS)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                consecutiveFailures += 1
                if (consecutiveFailures > MAX_CONSECUTIVE_AUTOMATIC_RETRIES) {
                    emit(PerformanceUiState.Error(error.message ?: "Performance telemetry could not be read."))
                    return@flow
                }
                delay(RETRY_BASE_DELAY_MILLIS * consecutiveFailures.toLong())
            }
        }
    }

    private fun ProfileApplyResult.toUserMessage(): String = when (this) {
        is ProfileApplyResult.Applied -> buildString {
            append("Profile ${profile.name} applied")
            if (changedSettings.isNotEmpty()) append(": ${changedSettings.joinToString()}")
            if (privilegedChangesUnavailable.isNotEmpty()) append(". Privileged kernel changes remain locked.")
        }
        is ProfileApplyResult.Superseded -> "A newer profile request replaced this one."
        is ProfileApplyResult.PermissionRequired -> "Modify system settings access is required for the reversible stock-Android layer."
        is ProfileApplyResult.Failed -> reason
    }

    private companion object {
        const val REFRESH_MILLIS = 5_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_CONSECUTIVE_AUTOMATIC_RETRIES = 2
        const val RETRY_BASE_DELAY_MILLIS = 750L
    }
}
