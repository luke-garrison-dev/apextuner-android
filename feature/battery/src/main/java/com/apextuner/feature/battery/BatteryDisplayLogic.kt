package com.apextuner.feature.battery

/**
 * BatteryManager current-sign conventions are not consistent across all OEM kernels.
 * Use Android's authoritative charging state for direction and the current sensor only
 * for magnitude/idle detection.
 */
internal fun batteryCurrentDirection(currentMicroamps: Long, charging: Boolean): String = when {
    currentMicroamps == 0L -> "idle"
    charging -> "charging"
    else -> "discharging"
}
