package com.apextuner.core.model

data class DeviceSnapshot(
    val capturedAtEpochMillis: Long,
    val uptimeMillis: Long,
    val cpu: CpuSnapshot,
    val gpu: GpuSnapshot,
    val memory: MemorySnapshot,
    val storage: StorageSnapshot,
    val battery: BatterySnapshot,
    val network: NetworkSnapshot,
    val thermalStatus: ThermalStatus,
)

data class CpuSnapshot(
    val logicalCoreCount: Int,
    val totalUsagePercent: Double?,
    val currentFrequenciesKhz: List<Long?>,
    val usageAvailability: CpuUsageAvailability = if (totalUsagePercent == null) {
        CpuUsageAvailability.Unavailable
    } else {
        CpuUsageAvailability.Available
    },
)

enum class CpuUsageAvailability {
    Available,
    Sampling,
    RestrictedByPlatform,
    Unavailable,
}

data class GpuSnapshot(
    val utilizationPercent: Double?,
)

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Double
        get() = if (totalBytes <= 0L) 0.0 else (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
}

data class StorageVolumeSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Double
        get() = if (totalBytes <= 0L) 0.0 else (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
}

data class StorageSnapshot(
    val internal: StorageVolumeSnapshot,
    val primaryShared: StorageVolumeSnapshot?,
)

data class BatterySnapshot(
    val levelPercent: Int?,
    val temperatureCelsius: Double?,
    val voltageMillivolts: Int?,
    val currentMicroamps: Long?,
    val chargeCounterMicroampHours: Long?,
    val health: BatteryHealth,
    val charging: Boolean,
    val pluggedSource: PluggedSource,
    val averageCurrentMicroamps: Long? = null,
    val energyCounterNanowattHours: Long? = null,
    val cycleCount: Int? = null,
    val technology: String? = null,
    val present: Boolean = true,
)

enum class BatteryHealth {
    Good,
    Cold,
    Dead,
    Overheat,
    OverVoltage,
    Failure,
    Unknown,
}

enum class PluggedSource {
    Ac,
    Usb,
    Wireless,
    Dock,
    None,
    Unknown,
}

data class NetworkSnapshot(
    val totalRxBytes: Long?,
    val totalTxBytes: Long?,
    val activeNetworkValidated: Boolean,
    val metered: Boolean,
)

enum class ThermalStatus {
    None,
    Light,
    Moderate,
    Severe,
    Critical,
    Emergency,
    Shutdown,
    Unknown,
}
