package com.apextuner.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.database.DeviceHealthSampleDao
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.feature.dashboard.model.DashboardUiState
import com.apextuner.feature.dashboard.model.HealthTimelineRange
import com.apextuner.feature.dashboard.model.toHealthTimeline
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val recommendationEngine: DashboardRecommendationEngine,
    private val healthDao: DeviceHealthSampleDao,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val restartGeneration = MutableStateFlow(0L)
    private val selectedRange = MutableStateFlow(HealthTimelineRange.Day)

    private val refreshInterval = preferencesRepository.preferences
        .map { it.telemetryRefreshMillis }
        .distinctUntilChanged()

    val uiState = combine(restartGeneration, selectedRange, refreshInterval) { generation, range, refreshMillis ->
        Triple(generation, range, refreshMillis)
    }
        .flatMapLatest { (_, range, refreshMillis) -> dashboardStream(range, refreshMillis) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MILLIS),
            initialValue = DashboardUiState.Loading,
        )

    fun retry() {
        restartGeneration.update { it + 1L }
    }

    fun selectTimelineRange(range: HealthTimelineRange) {
        selectedRange.value = range
    }

    private fun dashboardStream(range: HealthTimelineRange, refreshMillis: Long): Flow<DashboardUiState> = flow {
        val accumulator = DashboardAccumulator(recommendationEngine)
        var consecutiveFailures = 0
        var timeline = emptyList<com.apextuner.core.database.DeviceHealthSampleEntity>().toHealthTimeline(range)
        var lastTimelineRefresh = 0L

        while (currentCoroutineContext().isActive) {
            try {
                val snapshot = deviceRepository.snapshot()
                consecutiveFailures = 0
                val result = accumulator.add(snapshot)
                val now = System.currentTimeMillis()
                if (lastTimelineRefresh == 0L || now - lastTimelineRefresh >= TIMELINE_REFRESH_MILLIS) {
                    timeline = healthDao.since(now - range.durationMillis).toHealthTimeline(range)
                    lastTimelineRefresh = now
                }
                emit(DashboardUiState.Ready(data = result.data, history = result.history, timeline = timeline))
                delay(refreshMillis.coerceAtLeast(MIN_DASHBOARD_REFRESH_MILLIS))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                consecutiveFailures += 1
                if (consecutiveFailures > MAX_CONSECUTIVE_AUTOMATIC_RETRIES) {
                    emit(DashboardUiState.Error)
                    return@flow
                }
                delay(RETRY_BASE_DELAY_MILLIS * consecutiveFailures.toLong())
            }
        }
    }

    private companion object {
        const val MIN_DASHBOARD_REFRESH_MILLIS = 3_000L
        const val TIMELINE_REFRESH_MILLIS = 30_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_CONSECUTIVE_AUTOMATIC_RETRIES = 2
        const val RETRY_BASE_DELAY_MILLIS = 750L
    }
}
