package com.apextuner.feature.dashboard

import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.feature.dashboard.model.DashboardData
import com.apextuner.feature.dashboard.model.DashboardHistoryPoint
import java.util.ArrayDeque
import kotlin.math.roundToLong

internal class DashboardAccumulator(
    private val recommendationEngine: DashboardRecommendationEngine,
    private val maxHistoryPoints: Int = DEFAULT_HISTORY_POINTS,
) {
    private var previousSnapshot: DeviceSnapshot? = null
    private val history = ArrayDeque<DashboardHistoryPoint>(maxHistoryPoints)

    init {
        require(maxHistoryPoints > 1) { "At least two history points are required." }
    }

    fun add(snapshot: DeviceSnapshot): Result {
        val previous = previousSnapshot
        val elapsedMillis = previous
            ?.let { snapshot.uptimeMillis - it.uptimeMillis }
            ?.takeIf { it > 0L }

        val downloadBytesPerSecond = ratePerSecond(
            current = snapshot.network.totalRxBytes,
            previous = previous?.network?.totalRxBytes,
            elapsedMillis = elapsedMillis,
        )
        val uploadBytesPerSecond = ratePerSecond(
            current = snapshot.network.totalTxBytes,
            previous = previous?.network?.totalTxBytes,
            elapsedMillis = elapsedMillis,
        )

        val averageCpuFrequencyMhz = snapshot.cpu.currentFrequenciesKhz
            .mapNotNull { it }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.div(1_000.0)

        val sharedStorage = snapshot.storage.primaryShared
        val data = DashboardData(
            capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
            uptimeMillis = snapshot.uptimeMillis,
            cpuUsagePercent = snapshot.cpu.totalUsagePercent,
            cpuUsageAvailability = snapshot.cpu.usageAvailability,
            cpuCoreCount = snapshot.cpu.logicalCoreCount,
            cpuAverageFrequencyMhz = averageCpuFrequencyMhz,
            gpuUsagePercent = snapshot.gpu.utilizationPercent,
            memoryUsedBytes = snapshot.memory.usedBytes,
            memoryTotalBytes = snapshot.memory.totalBytes,
            memoryUsedFraction = snapshot.memory.usedFraction,
            internalStorageUsedBytes = snapshot.storage.internal.usedBytes,
            internalStorageTotalBytes = snapshot.storage.internal.totalBytes,
            internalStorageUsedFraction = snapshot.storage.internal.usedFraction,
            sharedStorageUsedBytes = sharedStorage?.usedBytes,
            sharedStorageTotalBytes = sharedStorage?.totalBytes,
            batteryLevelPercent = snapshot.battery.levelPercent,
            batteryTemperatureCelsius = snapshot.battery.temperatureCelsius,
            batteryVoltageMillivolts = snapshot.battery.voltageMillivolts,
            batteryCurrentMicroamps = snapshot.battery.currentMicroamps,
            batteryHealth = snapshot.battery.health,
            batteryCharging = snapshot.battery.charging,
            batteryPluggedSource = snapshot.battery.pluggedSource,
            downloadBytesPerSecond = downloadBytesPerSecond,
            uploadBytesPerSecond = uploadBytesPerSecond,
            networkValidated = snapshot.network.activeNetworkValidated,
            networkMetered = snapshot.network.metered,
            thermalStatus = snapshot.thermalStatus,
            recommendations = recommendationEngine.evaluate(snapshot),
        )

        history.addLast(
            DashboardHistoryPoint(
                uptimeMillis = snapshot.uptimeMillis,
                cpuUsagePercent = snapshot.cpu.totalUsagePercent,
                memoryUsedPercent = snapshot.memory.usedFraction * 100.0,
                downloadBytesPerSecond = downloadBytesPerSecond,
                uploadBytesPerSecond = uploadBytesPerSecond,
                batteryLevelPercent = snapshot.battery.levelPercent,
            ),
        )
        while (history.size > maxHistoryPoints) history.removeFirst()
        previousSnapshot = snapshot

        return Result(data = data, history = history.toList())
    }

    private fun ratePerSecond(current: Long?, previous: Long?, elapsedMillis: Long?): Long? {
        if (current == null || previous == null || elapsedMillis == null) return null
        if (current < 0L || previous < 0L || current < previous) return null
        val delta = current - previous
        return (delta.toDouble() * 1_000.0 / elapsedMillis.toDouble()).roundToLong().coerceAtLeast(0L)
    }

    data class Result(
        val data: DashboardData,
        val history: List<DashboardHistoryPoint>,
    )

    private companion object {
        const val DEFAULT_HISTORY_POINTS = 40
    }
}
