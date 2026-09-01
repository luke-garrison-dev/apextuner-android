package com.apextuner.feature.dashboard

import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.CpuSnapshot
import com.apextuner.core.model.CpuUsageAvailability
import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.model.GpuSnapshot
import com.apextuner.core.model.MemorySnapshot
import com.apextuner.core.model.NetworkSnapshot
import com.apextuner.core.model.PluggedSource
import com.apextuner.core.model.StorageSnapshot
import com.apextuner.core.model.StorageVolumeSnapshot
import com.apextuner.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardAccumulatorTest {

    @Test
    fun networkRateUsesMonotonicUptimeDelta() {
        val accumulator = DashboardAccumulator(DashboardRecommendationEngine(), maxHistoryPoints = 4)

        val first = accumulator.add(snapshot(uptime = 10_000L, rx = 1_000L, tx = 5_000L))
        val second = accumulator.add(snapshot(uptime = 12_000L, rx = 5_000L, tx = 7_000L))

        assertNull(first.data.downloadBytesPerSecond)
        assertNull(first.data.uploadBytesPerSecond)
        assertEquals(2_000L, second.data.downloadBytesPerSecond)
        assertEquals(1_000L, second.data.uploadBytesPerSecond)
    }

    @Test
    fun counterResetDoesNotProduceNegativeSpeed() {
        val accumulator = DashboardAccumulator(DashboardRecommendationEngine(), maxHistoryPoints = 4)

        accumulator.add(snapshot(uptime = 10_000L, rx = 10_000L, tx = 10_000L))
        val result = accumulator.add(snapshot(uptime = 12_000L, rx = 500L, tx = 600L))

        assertNull(result.data.downloadBytesPerSecond)
        assertNull(result.data.uploadBytesPerSecond)
    }

    @Test
    fun unsupportedNegativeCountersDoNotProduceRates() {
        val accumulator = DashboardAccumulator(DashboardRecommendationEngine(), maxHistoryPoints = 4)

        accumulator.add(snapshot(uptime = 10_000L, rx = -1L, tx = -1L))
        val result = accumulator.add(snapshot(uptime = 12_000L, rx = 5_000L, tx = 7_000L))

        assertNull(result.data.downloadBytesPerSecond)
        assertNull(result.data.uploadBytesPerSecond)
    }

    @Test
    fun cpuAvailabilityReasonIsPreservedForDashboardPresentation() {
        val accumulator = DashboardAccumulator(DashboardRecommendationEngine(), maxHistoryPoints = 4)

        val result = accumulator.add(
            snapshot(
                uptime = 10_000L,
                rx = 1_000L,
                tx = 1_000L,
                cpuUsagePercent = null,
                cpuUsageAvailability = CpuUsageAvailability.RestrictedByPlatform,
            ),
        )

        assertNull(result.data.cpuUsagePercent)
        assertEquals(CpuUsageAvailability.RestrictedByPlatform, result.data.cpuUsageAvailability)
        assertNull(result.history.single().cpuUsagePercent)
    }

    @Test
    fun historyIsBounded() {
        val accumulator = DashboardAccumulator(DashboardRecommendationEngine(), maxHistoryPoints = 3)

        repeat(6) { index ->
            accumulator.add(snapshot(uptime = 10_000L + index * 3_000L, rx = index * 1_000L, tx = index * 500L))
        }

        val result = accumulator.add(snapshot(uptime = 40_000L, rx = 20_000L, tx = 10_000L))
        assertEquals(3, result.history.size)
    }

    private fun snapshot(
        uptime: Long,
        rx: Long,
        tx: Long,
        cpuUsagePercent: Double? = 35.0,
        cpuUsageAvailability: CpuUsageAvailability = if (cpuUsagePercent == null) {
            CpuUsageAvailability.Unavailable
        } else {
            CpuUsageAvailability.Available
        },
    ) = DeviceSnapshot(
        capturedAtEpochMillis = 1_000_000L,
        uptimeMillis = uptime,
        cpu = CpuSnapshot(
            logicalCoreCount = 8,
            totalUsagePercent = cpuUsagePercent,
            currentFrequenciesKhz = listOf(1_800_000L, 1_600_000L),
            usageAvailability = cpuUsageAvailability,
        ),
        gpu = GpuSnapshot(utilizationPercent = null),
        memory = MemorySnapshot(
            totalBytes = 8_000L,
            availableBytes = 3_000L,
            lowMemory = false,
            thresholdBytes = 1_000L,
        ),
        storage = StorageSnapshot(
            internal = StorageVolumeSnapshot(totalBytes = 100_000L, availableBytes = 50_000L),
            primaryShared = null,
        ),
        battery = BatterySnapshot(
            levelPercent = 80,
            temperatureCelsius = 30.0,
            voltageMillivolts = 4_000,
            currentMicroamps = -500_000L,
            chargeCounterMicroampHours = 3_000_000L,
            health = BatteryHealth.Good,
            charging = false,
            pluggedSource = PluggedSource.None,
        ),
        network = NetworkSnapshot(
            totalRxBytes = rx,
            totalTxBytes = tx,
            activeNetworkValidated = true,
            metered = false,
        ),
        thermalStatus = ThermalStatus.None,
    )
}
