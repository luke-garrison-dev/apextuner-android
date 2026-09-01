package com.apextuner.feature.tools.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class GameBoosterViewModel @Inject constructor(
    private val controller: GameSessionController,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val apps = MutableStateFlow<List<GameApp>>(emptyList())
    private val message = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val panel = MutableStateFlow(GameBoosterPanelState())

    val state: StateFlow<GameBoosterUiState> = combine(controller.state, apps, message, busy, panel) { session, appList, msg, isBusy, editor ->
        GameBoosterUiState(session, appList, msg, isBusy, editor.selectedApp, editor.options, editor.recentSessions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameBoosterUiState())

    init {
        refreshApps()
        refreshRecentSessions()
    }

    fun refreshApps() {
        viewModelScope.launch {
            try { apps.value = withContext(io) { controller.visibleGames() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { message.value = "Android did not expose the launchable app list." }
        }
    }

    fun selectApp(app: GameApp) {
        val saved = controller.savedOptionsFor(app.packageName) ?: GameSessionOptions()
        panel.value = panel.value.copy(selectedApp = app, options = saved)
    }

    fun setGamingProfile(enabled: Boolean) { panel.value = panel.value.copy(options = panel.value.options.copy(useGamingProfile = enabled)) }
    fun setSilenceInterruptions(enabled: Boolean) { panel.value = panel.value.copy(options = panel.value.options.copy(silenceInterruptions = enabled)) }
    fun setThermalWarningCelsius(value: Double) {
        panel.value = panel.value.copy(
            options = panel.value.options.copy(thermalWarningCelsius = value.coerceIn(GameProfileStore.MIN_THERMAL_WARNING, GameProfileStore.MAX_THERMAL_WARNING)),
        )
    }

    fun startSelected() {
        val app = panel.value.selectedApp ?: return
        start(app, panel.value.options)
    }

    fun start(app: GameApp, options: GameSessionOptions) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                message.value = when (val result = controller.start(app, options)) {
                    is GameSessionResult.Started -> if (result.warnings.isEmpty()) "Game session started. Use End & restore when finished." else result.warnings.joinToString(" ")
                    is GameSessionResult.Failed -> result.reason
                    is GameSessionResult.Stopped -> result.warnings.joinToString(" ")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { busy.value = false }
        }
    }

    fun stop() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val result = controller.stop("ui")
                message.value = when (result) {
                    is GameSessionResult.Stopped -> if (result.warnings.isEmpty()) "Game session ended and reversible settings were restored." else result.warnings.joinToString(" ")
                    is GameSessionResult.Failed -> result.reason
                    is GameSessionResult.Started -> result.warnings.joinToString(" ")
                }
                if (result is GameSessionResult.Stopped) refreshRecentSessions()
            } finally { busy.value = false }
        }
    }

    fun refreshRecentSessions() {
        viewModelScope.launch {
            try {
                val recent = withContext(io) { controller.recentSessions(8) }
                panel.value = panel.value.copy(recentSessions = recent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep the last known session history on a transient database/read failure.
            }
        }
    }

    fun dismissMessage() { message.value = null }
}

private data class GameBoosterPanelState(
    val selectedApp: GameApp? = null,
    val options: GameSessionOptions = GameSessionOptions(),
    val recentSessions: List<GameSessionInsight> = emptyList(),
)

data class GameBoosterUiState(
    val session: GameSessionState = GameSessionState(),
    val apps: List<GameApp> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
    val selectedApp: GameApp? = null,
    val selectedOptions: GameSessionOptions = GameSessionOptions(),
    val recentSessions: List<GameSessionInsight> = emptyList(),
)
