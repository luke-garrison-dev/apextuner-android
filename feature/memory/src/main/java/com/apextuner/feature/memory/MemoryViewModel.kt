package com.apextuner.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.feature.memory.model.MemoryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
) : ViewModel() {
    private val restartGeneration = MutableStateFlow(0L)

    val state = restartGeneration
        .flatMapLatest { memoryStream() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MILLIS),
            initialValue = MemoryUiState.Loading,
        )

    fun refresh() {
        restartGeneration.update { it + 1L }
    }

    private fun memoryStream(): Flow<MemoryUiState> = flow {
        emit(MemoryUiState.Loading)
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            try {
                val insights = repository.readInsights()
                consecutiveFailures = 0
                emit(MemoryUiState.Ready(insights))
                delay(REFRESH_MILLIS)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                consecutiveFailures += 1
                if (consecutiveFailures > MAX_CONSECUTIVE_AUTOMATIC_RETRIES) {
                    emit(MemoryUiState.Error(error.message ?: "Memory data could not be read."))
                    return@flow
                }
                delay(RETRY_BASE_DELAY_MILLIS * consecutiveFailures.toLong())
            }
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 10_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_CONSECUTIVE_AUTOMATIC_RETRIES = 2
        const val RETRY_BASE_DELAY_MILLIS = 750L
    }
}
