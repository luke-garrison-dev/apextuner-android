package com.apextuner.feature.tools.game

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apextuner.core.model.SystemProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.gameSessionStore by preferencesDataStore(name = "apextuner_game_session")

@Singleton
class GameSessionStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    val state: Flow<GameSessionState> = context.gameSessionStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val active = p[Keys.Active] ?: false
            GameSessionState(
                active = active,
                packageName = p[Keys.Package],
                appLabel = p[Keys.Label],
                startedAtEpochMillis = p[Keys.Started] ?: 0L,
                previousProfile = p[Keys.PreviousProfile]?.let { name -> SystemProfile.entries.firstOrNull { it.name == name } } ?: SystemProfile.Balanced,
                // Legacy active sessions predate this key and historically always attempted a
                // profile restore. Preserve that recovery behavior only for those old records.
                profileChangedByApexTuner = p[Keys.ProfileChanged] ?: active,
                dndChangedByApexTuner = p[Keys.DndChanged] ?: false,
                previousInterruptionFilter = p[Keys.PreviousFilter] ?: 0,
                startBatteryLevelPercent = p[Keys.StartBattery],
                peakBatteryTemperatureCelsius = p[Keys.PeakBatteryTemp],
                startRxBytes = p[Keys.StartRx],
                startTxBytes = p[Keys.StartTx],
                thermalWarningCelsius = p[Keys.ThermalWarning] ?: GameProfileStore.DEFAULT_THERMAL_WARNING,
                thermalWarningIssued = p[Keys.ThermalWarningIssued] ?: false,
            )
        }

    suspend fun save(state: GameSessionState) {
        context.gameSessionStore.edit { p ->
            p[Keys.Active] = state.active
            state.packageName?.let { p[Keys.Package] = it } ?: p.remove(Keys.Package)
            state.appLabel?.let { p[Keys.Label] = it } ?: p.remove(Keys.Label)
            p[Keys.Started] = state.startedAtEpochMillis
            p[Keys.PreviousProfile] = state.previousProfile.name
            p[Keys.ProfileChanged] = state.profileChangedByApexTuner
            p[Keys.DndChanged] = state.dndChangedByApexTuner
            p[Keys.PreviousFilter] = state.previousInterruptionFilter
            state.startBatteryLevelPercent?.let { p[Keys.StartBattery] = it } ?: p.remove(Keys.StartBattery)
            state.peakBatteryTemperatureCelsius?.let { p[Keys.PeakBatteryTemp] = it } ?: p.remove(Keys.PeakBatteryTemp)
            state.startRxBytes?.let { p[Keys.StartRx] = it } ?: p.remove(Keys.StartRx)
            state.startTxBytes?.let { p[Keys.StartTx] = it } ?: p.remove(Keys.StartTx)
            p[Keys.ThermalWarning] = state.thermalWarningCelsius
            p[Keys.ThermalWarningIssued] = state.thermalWarningIssued
        }
    }

    suspend fun clear() { context.gameSessionStore.edit { it.clear() } }

    private object Keys {
        val Active = booleanPreferencesKey("active")
        val Package = stringPreferencesKey("package")
        val Label = stringPreferencesKey("label")
        val Started = longPreferencesKey("started")
        val PreviousProfile = stringPreferencesKey("previous_profile")
        val ProfileChanged = booleanPreferencesKey("profile_changed")
        val DndChanged = booleanPreferencesKey("dnd_changed")
        val PreviousFilter = intPreferencesKey("previous_filter")
        val StartBattery = intPreferencesKey("start_battery")
        val PeakBatteryTemp = doublePreferencesKey("peak_battery_temp")
        val StartRx = longPreferencesKey("start_rx")
        val StartTx = longPreferencesKey("start_tx")
        val ThermalWarning = doublePreferencesKey("thermal_warning")
        val ThermalWarningIssued = booleanPreferencesKey("thermal_warning_issued")
    }
}
