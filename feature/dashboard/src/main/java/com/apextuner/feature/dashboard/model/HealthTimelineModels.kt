package com.apextuner.feature.dashboard.model

import com.apextuner.core.database.DeviceHealthSampleEntity

enum class HealthTimelineRange(val durationMillis: Long, val displayName: String) {
    Day(24L * 60L * 60L * 1_000L, "24 h"),
    Week(7L * 24L * 60L * 60L * 1_000L, "7 d"),
    Month(30L * 24L * 60L * 60L * 1_000L, "30 d"),
    Quarter(90L * 24L * 60L * 60L * 1_000L, "90 d"),
}

data class HealthTimelinePoint(
    val capturedAtEpochMillis: Long,
    val cpuUsagePercent: Double?,
    val memoryUsedPercent: Double,
    val storageFreeBytes: Long,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Double?,
    val thermalStatus: String,
    val networkMetered: Boolean,
    val totalRxBytes: Long?,
    val totalTxBytes: Long?,
)

data class HealthTimelineSummary(
    val sampleCount: Int,
    val averageCpuUsagePercent: Double?,
    val averageMemoryUsedPercent: Double?,
    val averageBatteryTemperatureCelsius: Double?,
    val maximumBatteryTemperatureCelsius: Double?,
    val minimumStorageFreeBytes: Long?,
    val storageFreeDeltaBytes: Long?,
    val elevatedThermalSamples: Int,
    val elevatedThermalEvents: Int,
    val highCpuHotBatterySamples: Int,
    val meteredSamplePercent: Int?,
    val observedTrafficBytes: Long?,
)

data class HealthTimelineData(
    val range: HealthTimelineRange = HealthTimelineRange.Day,
    val points: List<HealthTimelinePoint> = emptyList(),
    val summary: HealthTimelineSummary = HealthTimelineSummary(
        sampleCount = 0,
        averageCpuUsagePercent = null,
        averageMemoryUsedPercent = null,
        averageBatteryTemperatureCelsius = null,
        maximumBatteryTemperatureCelsius = null,
        minimumStorageFreeBytes = null,
        storageFreeDeltaBytes = null,
        elevatedThermalSamples = 0,
        elevatedThermalEvents = 0,
        highCpuHotBatterySamples = 0,
        meteredSamplePercent = null,
        observedTrafficBytes = null,
    ),
)

internal fun List<DeviceHealthSampleEntity>.toHealthTimeline(range: HealthTimelineRange): HealthTimelineData {
    val points = map { row ->
        HealthTimelinePoint(
            capturedAtEpochMillis = row.capturedAtEpochMillis,
            cpuUsagePercent = row.cpuUsagePercent,
            memoryUsedPercent = row.memoryUsedPercent,
            storageFreeBytes = row.internalStorageAvailableBytes,
            batteryLevelPercent = row.batteryLevelPercent,
            batteryTemperatureCelsius = row.batteryTemperatureCelsius,
            thermalStatus = row.thermalStatus,
            networkMetered = row.networkMetered,
            totalRxBytes = row.totalRxBytes,
            totalTxBytes = row.totalTxBytes,
        )
    }
    val elevated = points.count { it.thermalStatus in ELEVATED_THERMAL_STATES }
    val elevatedEvents = points.fold(0 to false) { (events, previouslyElevated), point ->
        val elevatedNow = point.thermalStatus in ELEVATED_THERMAL_STATES
        (events + if (elevatedNow && !previouslyElevated) 1 else 0) to elevatedNow
    }.first
    val storageDelta = points.takeIf { it.size >= 2 }?.let { it.last().storageFreeBytes - it.first().storageFreeBytes }
    val meteredPercent = points.takeIf { it.isNotEmpty() }?.let { samples ->
        (samples.count { it.networkMetered } * 100 / samples.size).coerceIn(0, 100)
    }
    val observedTraffic = points.takeIf { it.size >= 2 }?.let { samples ->
        val first = samples.first()
        val last = samples.last()
        val rx = monotonicDelta(first.totalRxBytes, last.totalRxBytes)
        val tx = monotonicDelta(first.totalTxBytes, last.totalTxBytes)
        when {
            rx == null && tx == null -> null
            else -> saturatingAdd(rx ?: 0L, tx ?: 0L)
        }
    }
    return HealthTimelineData(
        range = range,
        points = points,
        summary = HealthTimelineSummary(
            sampleCount = points.size,
            averageCpuUsagePercent = points.mapNotNull { it.cpuUsagePercent }.takeIf { it.isNotEmpty() }?.average(),
            averageMemoryUsedPercent = points.map { it.memoryUsedPercent }.takeIf { it.isNotEmpty() }?.average(),
            averageBatteryTemperatureCelsius = points.mapNotNull { it.batteryTemperatureCelsius }.takeIf { it.isNotEmpty() }?.average(),
            maximumBatteryTemperatureCelsius = points.mapNotNull { it.batteryTemperatureCelsius }.maxOrNull(),
            minimumStorageFreeBytes = points.minOfOrNull { it.storageFreeBytes },
            storageFreeDeltaBytes = storageDelta,
            elevatedThermalSamples = elevated,
            elevatedThermalEvents = elevatedEvents,
            highCpuHotBatterySamples = points.count { point ->
                (point.cpuUsagePercent ?: 0.0) >= HIGH_CPU_PERCENT &&
                    (point.batteryTemperatureCelsius ?: Double.NEGATIVE_INFINITY) >= WARM_BATTERY_CELSIUS
            },
            meteredSamplePercent = meteredPercent,
            observedTrafficBytes = observedTraffic,
        ),
    )
}

private fun monotonicDelta(first: Long?, last: Long?): Long? {
    if (first == null || last == null || first < 0L || last < first) return null
    return last - first
}

private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

private const val HIGH_CPU_PERCENT = 70.0
private const val WARM_BATTERY_CELSIUS = 40.0
private val ELEVATED_THERMAL_STATES = setOf("Severe", "Critical", "Emergency", "Shutdown")
