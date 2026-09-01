package com.apextuner.feature.battery

import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.ThermalStatus

internal object BatteryRecommendationEngine {
    fun evaluate(battery: BatterySnapshot, thermal: ThermalStatus, powerSaveMode: Boolean): List<String> {
        val result = mutableListOf<String>()
        if (!battery.present) result += "Battery hardware is not reported as present by Android."
        if (thermal in setOf(ThermalStatus.Severe, ThermalStatus.Critical, ThermalStatus.Emergency, ThermalStatus.Shutdown) || battery.health == BatteryHealth.Overheat) {
            result += "Device thermal stress is high. Stop heavy workloads and let the device cool naturally."
        } else if ((battery.temperatureCelsius ?: 0.0) >= 42.0) {
            result += "Battery temperature is elevated. Avoid charging under heavy workload or direct heat."
        }
        if (!battery.charging && (battery.levelPercent ?: 100) <= 20 && !powerSaveMode) {
            result += "Battery is low and Android Battery Saver is off. Consider enabling the system saver."
        }
        if (battery.charging && (battery.temperatureCelsius ?: 0.0) >= 40.0) {
            result += "Charging while warm can increase thermal stress. Improve airflow and reduce workload."
        }
        if (battery.health !in setOf(BatteryHealth.Good, BatteryHealth.Unknown)) {
            result += "Android reports battery health as ${battery.health.name}. Treat this as a device signal, not a diagnosis."
        }
        if (result.isEmpty()) result += "Battery and thermal signals are currently within normal Android-reported ranges."
        return result
    }
}
