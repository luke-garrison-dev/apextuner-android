package com.apextuner.feature.tools.game

import com.apextuner.core.model.SystemProfile

data class GameApp(
    val packageName: String,
    val label: String,
    val activityClassName: String,
)

data class GameSessionState(
    val active: Boolean = false,
    val packageName: String? = null,
    val appLabel: String? = null,
    val startedAtEpochMillis: Long = 0L,
    val previousProfile: SystemProfile = SystemProfile.Balanced,
    val profileChangedByApexTuner: Boolean = false,
    val dndChangedByApexTuner: Boolean = false,
    val previousInterruptionFilter: Int = 0,
    val startBatteryLevelPercent: Int? = null,
    val peakBatteryTemperatureCelsius: Double? = null,
    val startRxBytes: Long? = null,
    val startTxBytes: Long? = null,
    val thermalWarningCelsius: Double = GameProfileStore.DEFAULT_THERMAL_WARNING,
    val thermalWarningIssued: Boolean = false,
)

data class GameSessionOptions(
    val useGamingProfile: Boolean = true,
    val silenceInterruptions: Boolean = true,
    val thermalWarningCelsius: Double = GameProfileStore.DEFAULT_THERMAL_WARNING,
)

sealed interface GameSessionResult {
    data class Started(val warnings: List<String>) : GameSessionResult
    data class Stopped(val warnings: List<String>) : GameSessionResult
    data class Failed(val reason: String) : GameSessionResult
}


data class GameSessionInsight(
    val packageName: String,
    val appLabel: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val startBatteryLevelPercent: Int?,
    val endBatteryLevelPercent: Int?,
    val peakBatteryTemperatureCelsius: Double?,
    val receivedBytes: Long?,
    val transmittedBytes: Long?,
    val gamingProfileUsed: Boolean,
    val dndUsed: Boolean,
) {
    val durationMillis: Long get() = (endedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
    val batteryDeltaPercent: Int? get() = if (startBatteryLevelPercent != null && endBatteryLevelPercent != null) endBatteryLevelPercent - startBatteryLevelPercent else null
}
