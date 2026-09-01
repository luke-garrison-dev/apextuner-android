package com.apextuner.feature.tools.performance

import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.ThermalStatus

data class CpuCoreInsight(
    val core: Int,
    val currentKhz: Long?,
    val minKhz: Long?,
    val maxKhz: Long?,
    val governor: String?,
)

data class PerformanceInsights(
    val cpuUsagePercent: Double?,
    val cores: List<CpuCoreInsight>,
    val gpuUsagePercent: Double?,
    val thermalStatus: ThermalStatus,
    val ioSchedulers: List<String>,
    val tcpCongestionAlgorithm: String?,
    val rootPotentiallyAvailable: Boolean,
    val activeProfile: SystemProfile,
    val recommendations: List<String>,
)

sealed interface PerformanceUiState {
    data object Loading : PerformanceUiState
    data class Ready(val insights: PerformanceInsights, val message: String? = null) : PerformanceUiState
    data class Error(val message: String) : PerformanceUiState
}
