package com.apextuner.feature.dashboard.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.database.AutomationEventDao
import com.apextuner.core.database.BatteryHealthSnapshotDao
import com.apextuner.core.database.ChargingSessionDao
import com.apextuner.core.database.DeviceHealthSampleDao
import com.apextuner.core.database.GameSessionRecordDao
import com.apextuner.core.database.NetworkQualityRunDao
import com.apextuner.core.database.OptimizationHistoryDao
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class IntelligenceViewModel @Inject constructor(
    private val deviceHealthSampleDao: DeviceHealthSampleDao,
    private val batteryHealthSnapshotDao: BatteryHealthSnapshotDao,
    private val chargingSessionDao: ChargingSessionDao,
    private val networkQualityRunDao: NetworkQualityRunDao,
    private val optimizationHistoryDao: OptimizationHistoryDao,
    private val automationEventDao: AutomationEventDao,
    private val gameSessionRecordDao: GameSessionRecordDao,
    private val timeProvider: TimeProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow<IntelligenceUiState>(IntelligenceUiState.Loading)
    val uiState: StateFlow<IntelligenceUiState> = _uiState.asStateFlow()
    private var analysisJob: Job? = null

    init { refresh() }

    fun refresh() {
        analysisJob?.cancel()
        _uiState.value = IntelligenceUiState.Loading
        analysisJob = viewModelScope.launch {
            try {
                _uiState.value = IntelligenceUiState.Ready(loadSnapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.value = IntelligenceUiState.Error(error.message ?: "Unable to analyze device history")
            }
        }
    }

    private suspend fun loadSnapshot(): IntelligenceSnapshot = withContext(ioDispatcher) {
        val now = timeProvider.nowEpochMillis()
        val since = now - OBSERVATION_WINDOW_MILLIS
        val health = async { deviceHealthSampleDao.since(since) }
        val battery = async { batteryHealthSnapshotDao.recent(60) }
        val charging = async { chargingSessionDao.recentCompleted(40) }
        val network = async { networkQualityRunDao.recent(30) }
        val optimizations = async { optimizationHistoryDao.recent(50) }
        val automation = async { automationEventDao.recent(50) }
        val games = async { gameSessionRecordDao.recent(30) }

        ApexIntelligenceEngine.analyze(
            ApexIntelligenceEngine.Input(
                nowEpochMillis = now,
                health = health.await(),
                batteryHealth = battery.await(),
                chargingSessions = charging.await(),
                networkRuns = network.await(),
                optimizations = optimizations.await(),
                automationEvents = automation.await(),
                gameSessions = games.await(),
            ),
        )
    }

    private companion object {
        const val OBSERVATION_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}
