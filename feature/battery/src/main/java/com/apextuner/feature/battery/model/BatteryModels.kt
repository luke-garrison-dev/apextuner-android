package com.apextuner.feature.battery.model

import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.ThermalStatus
import com.apextuner.feature.battery.BatteryHealthTrend

data class RecentAppActivity(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
    val lastUsedEpochMillis: Long,
)


enum class EstimateConfidence { Low, Medium, High }

data class ChargingSessionInsight(
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val durationMillis: Long,
    val startLevelPercent: Int?,
    val endLevelPercent: Int?,
    val estimatedAddedMah: Double?,
    val averageTemperatureCelsius: Double?,
    val peakTemperatureCelsius: Double?,
    val maximumCurrentMicroamps: Long?,
    val cycleDelta: Int?,
    val sampleCount: Int,
    val confidence: EstimateConfidence,
)


data class ChargingHistorySummary(
    val completedSessions: Int,
    val typicalChargeRatePercentPerHour: Double?,
    val latestChargeRatePercentPerHour: Double?,
    val averagePeakTemperatureCelsius: Double?,
    val warmSessionCount: Int,
    val hotSessionCount: Int,
    val latestSlowerThanBaseline: Boolean,
    val insight: String,
)

data class BatteryInsights(
    val battery: BatterySnapshot,
    val thermalStatus: ThermalStatus,
    val powerSaveMode: Boolean,
    val predictedRemainingMillis: Long?,
    val usageAccessGranted: Boolean,
    val apexTunerStandbyBucket: String?,
    val recentActivity: List<RecentAppActivity>,
    val activeProfile: SystemProfile,
    val profileMatchesSystem: Boolean,
    val hasProfileRestorePoint: Boolean,
    val recommendations: List<String>,
    val healthTrend: BatteryHealthTrend,
    val chargingSessions: List<ChargingSessionInsight> = emptyList(),
    val chargingHistory: ChargingHistorySummary = ChargingHistorySummary(0, null, null, null, 0, 0, false, "More completed charging sessions are needed for a personal baseline."),
)

sealed interface BatteryUiState {
    data object Loading : BatteryUiState
    data class Ready(val insights: BatteryInsights, val message: String? = null) : BatteryUiState
    data class Error(val message: String) : BatteryUiState
}
