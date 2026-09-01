package com.apextuner.feature.dashboard.model

import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.CpuUsageAvailability
import com.apextuner.core.model.PluggedSource
import com.apextuner.core.model.ThermalStatus

sealed interface DashboardUiState {
    data object Loading : DashboardUiState

    data class Ready(
        val data: DashboardData,
        val history: List<DashboardHistoryPoint>,
        val timeline: HealthTimelineData = HealthTimelineData(),
    ) : DashboardUiState

    data object Error : DashboardUiState
}

data class DashboardData(
    val capturedAtEpochMillis: Long,
    val uptimeMillis: Long,
    val cpuUsagePercent: Double?,
    val cpuUsageAvailability: CpuUsageAvailability,
    val cpuCoreCount: Int,
    val cpuAverageFrequencyMhz: Double?,
    val gpuUsagePercent: Double?,
    val memoryUsedBytes: Long,
    val memoryTotalBytes: Long,
    val memoryUsedFraction: Double,
    val internalStorageUsedBytes: Long,
    val internalStorageTotalBytes: Long,
    val internalStorageUsedFraction: Double,
    val sharedStorageUsedBytes: Long?,
    val sharedStorageTotalBytes: Long?,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Double?,
    val batteryVoltageMillivolts: Int?,
    val batteryCurrentMicroamps: Long?,
    val batteryHealth: BatteryHealth,
    val batteryCharging: Boolean,
    val batteryPluggedSource: PluggedSource,
    val downloadBytesPerSecond: Long?,
    val uploadBytesPerSecond: Long?,
    val networkValidated: Boolean,
    val networkMetered: Boolean,
    val thermalStatus: ThermalStatus,
    val recommendations: List<DashboardRecommendation>,
)

data class DashboardHistoryPoint(
    val uptimeMillis: Long,
    val cpuUsagePercent: Double?,
    val memoryUsedPercent: Double,
    val downloadBytesPerSecond: Long?,
    val uploadBytesPerSecond: Long?,
    val batteryLevelPercent: Int?,
)

data class DashboardRecommendation(
    val severity: RecommendationSeverity,
    val type: RecommendationType,
)

enum class RecommendationType {
    ThermalCritical,
    ThermalModerate,
    StorageCritical,
    StorageLow,
    MemoryLow,
    MemoryHigh,
    BatteryWarm,
    BatteryLow,
    NetworkUnvalidated,
    Healthy,
}

enum class RecommendationSeverity {
    Info,
    Attention,
    Critical,
}

enum class DashboardChartMetric {
    Cpu,
    Memory,
    Download,
    Upload,
}
