package com.apextuner.feature.tools.diagnostics

import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.feature.tools.security.SecuritySnapshot

enum class DiagnosticReportSection { Device, Battery, MemoryStorage, Network, Security, History }
enum class DiagnosticReportFormat { Json, Html }

data class DiagnosticCapture(
    val capturedAtEpochMillis: Long,
    val device: DeviceSnapshot,
    val security: SecuritySnapshot,
    val launchableAppCount: Int,
    val healthSampleCount: Int,
    val batteryHealthSnapshotCount: Int,
    val recentGameSessionCount: Int,
)

data class DiagnosticComparison(
    val baselineCapturedAtEpochMillis: Long,
    val currentCapturedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val batteryDeltaPercent: Int?,
    val memoryAvailableDeltaBytes: Long,
    val storageAvailableDeltaBytes: Long,
    val batteryTemperatureDeltaCelsius: Double?,
    val rxDeltaBytes: Long?,
    val txDeltaBytes: Long?,
)

data class DiagnosticReportUiState(
    val baseline: DiagnosticCapture? = null,
    val current: DiagnosticCapture? = null,
    val selectedSections: Set<DiagnosticReportSection> = DiagnosticReportSection.entries.toSet(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    val comparison: DiagnosticComparison?
        get() = baseline?.let { base -> current?.let { now -> compareCaptures(base, now) } }
}

fun compareCaptures(baseline: DiagnosticCapture, current: DiagnosticCapture): DiagnosticComparison {
    val baselineBatteryLevel = baseline.device.battery.levelPercent
    val currentBatteryLevel = current.device.battery.levelPercent
    val baselineBatteryTemperature = baseline.device.battery.temperatureCelsius
    val currentBatteryTemperature = current.device.battery.temperatureCelsius

    return DiagnosticComparison(
        baselineCapturedAtEpochMillis = baseline.capturedAtEpochMillis,
        currentCapturedAtEpochMillis = current.capturedAtEpochMillis,
        elapsedMillis = (current.capturedAtEpochMillis - baseline.capturedAtEpochMillis).coerceAtLeast(0L),
        batteryDeltaPercent = if (baselineBatteryLevel != null && currentBatteryLevel != null) {
            currentBatteryLevel - baselineBatteryLevel
        } else null,
        memoryAvailableDeltaBytes = current.device.memory.availableBytes - baseline.device.memory.availableBytes,
        storageAvailableDeltaBytes = current.device.storage.internal.availableBytes - baseline.device.storage.internal.availableBytes,
        batteryTemperatureDeltaCelsius = if (baselineBatteryTemperature != null && currentBatteryTemperature != null) {
            currentBatteryTemperature - baselineBatteryTemperature
        } else null,
        rxDeltaBytes = counterDelta(baseline.device.network.totalRxBytes, current.device.network.totalRxBytes),
        txDeltaBytes = counterDelta(baseline.device.network.totalTxBytes, current.device.network.totalTxBytes),
    )
}

private fun counterDelta(start: Long?, end: Long?): Long? = if (start != null && end != null && end >= start) end - start else null


fun DiagnosticComparison.totalNetworkDeltaBytes(): Long? {
    val available = listOfNotNull(rxDeltaBytes, txDeltaBytes)
    return available.takeIf { it.isNotEmpty() }?.sum()
}
