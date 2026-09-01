package com.apextuner.feature.dashboard

import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.model.ThermalStatus
import com.apextuner.feature.dashboard.model.DashboardRecommendation
import com.apextuner.feature.dashboard.model.RecommendationSeverity
import com.apextuner.feature.dashboard.model.RecommendationType
import javax.inject.Inject

class DashboardRecommendationEngine @Inject constructor() {

    fun evaluate(snapshot: DeviceSnapshot): List<DashboardRecommendation> {
        val recommendations = mutableListOf<DashboardRecommendation>()

        when (snapshot.thermalStatus) {
            ThermalStatus.Shutdown,
            ThermalStatus.Emergency,
            ThermalStatus.Critical,
            ThermalStatus.Severe,
            -> recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Critical,
                type = RecommendationType.ThermalCritical,
            )

            ThermalStatus.Moderate -> recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Attention,
                type = RecommendationType.ThermalModerate,
            )

            else -> Unit
        }

        val freeStorageFraction = 1.0 - snapshot.storage.internal.usedFraction
        when {
            freeStorageFraction <= CRITICAL_STORAGE_FREE_FRACTION -> recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Critical,
                type = RecommendationType.StorageCritical,
            )

            freeStorageFraction <= LOW_STORAGE_FREE_FRACTION -> recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Attention,
                type = RecommendationType.StorageLow,
            )
        }

        when {
            snapshot.memory.lowMemory -> recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Attention,
                type = RecommendationType.MemoryLow,
            )

            snapshot.memory.usedFraction >= HIGH_MEMORY_USED_FRACTION -> recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Info,
                type = RecommendationType.MemoryHigh,
            )
        }

        val batteryTemperature = snapshot.battery.temperatureCelsius
        if (batteryTemperature != null && batteryTemperature >= HIGH_BATTERY_TEMPERATURE_C) {
            recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Attention,
                type = RecommendationType.BatteryWarm,
            )
        }

        val batteryLevel = snapshot.battery.levelPercent
        if (batteryLevel != null && batteryLevel <= LOW_BATTERY_PERCENT && !snapshot.battery.charging) {
            recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Info,
                type = RecommendationType.BatteryLow,
            )
        }

        if (!snapshot.network.activeNetworkValidated) {
            recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Info,
                type = RecommendationType.NetworkUnvalidated,
            )
        }

        if (recommendations.isEmpty()) {
            recommendations += DashboardRecommendation(
                severity = RecommendationSeverity.Info,
                type = RecommendationType.Healthy,
            )
        }

        return recommendations
            .distinctBy { it.type }
            .sortedByDescending { it.severity.priority }
            .take(MAX_RECOMMENDATIONS)
    }

    private val RecommendationSeverity.priority: Int
        get() = when (this) {
            RecommendationSeverity.Critical -> 3
            RecommendationSeverity.Attention -> 2
            RecommendationSeverity.Info -> 1
        }

    private companion object {
        const val LOW_STORAGE_FREE_FRACTION = 0.15
        const val CRITICAL_STORAGE_FREE_FRACTION = 0.10
        const val HIGH_MEMORY_USED_FRACTION = 0.90
        const val HIGH_BATTERY_TEMPERATURE_C = 45.0
        const val LOW_BATTERY_PERCENT = 15
        const val MAX_RECOMMENDATIONS = 4
    }
}
