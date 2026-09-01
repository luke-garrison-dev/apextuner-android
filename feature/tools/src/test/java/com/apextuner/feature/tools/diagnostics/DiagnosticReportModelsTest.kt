package com.apextuner.feature.tools.diagnostics

import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.CpuSnapshot
import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.model.GpuSnapshot
import com.apextuner.core.model.MemorySnapshot
import com.apextuner.core.model.NetworkSnapshot
import com.apextuner.core.model.PluggedSource
import com.apextuner.core.model.StorageSnapshot
import com.apextuner.core.model.StorageVolumeSnapshot
import com.apextuner.core.model.ThermalStatus
import com.apextuner.feature.tools.security.SecuritySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticReportModelsTest {
    @Test
    fun comparisonUsesMonotonicCountersAndSignedResourceDeltas() {
        val baseline = capture(1L, 80, 500L, 1_000L, 100L, 200L, 35.0)
        val current = capture(2L, 75, 600L, 900L, 150L, 260L, 37.5)
        val result = compareCaptures(baseline, current)
        assertEquals(-5, result.batteryDeltaPercent)
        assertEquals(100L, result.memoryAvailableDeltaBytes)
        assertEquals(-100L, result.storageAvailableDeltaBytes)
        assertEquals(2.5, result.batteryTemperatureDeltaCelsius!!, 0.001)
        assertEquals(1L, result.elapsedMillis)
        assertEquals(50L, result.rxDeltaBytes)
        assertEquals(60L, result.txDeltaBytes)
        assertEquals(110L, result.totalNetworkDeltaBytes())
    }

    @Test
    fun networkDeltaIsUnavailableWhenBothCountersResetOrDisappear() {
        val baseline = capture(10L, 80, 500L, 1_000L, 500L, 600L, 35.0)
        val current = capture(5L, 80, 500L, 1_000L, 100L, 200L, 35.0)
        val result = compareCaptures(baseline, current)
        assertEquals(0L, result.elapsedMillis)
        assertEquals(null, result.rxDeltaBytes)
        assertEquals(null, result.txDeltaBytes)
        assertEquals(null, result.totalNetworkDeltaBytes())
    }

    private fun capture(time: Long, battery: Int, memory: Long, storage: Long, rx: Long, tx: Long, temperature: Double) = DiagnosticCapture(
        capturedAtEpochMillis = time,
        device = DeviceSnapshot(
            capturedAtEpochMillis = time,
            uptimeMillis = 1_000L,
            cpu = CpuSnapshot(8, 10.0, emptyList()),
            gpu = GpuSnapshot(null),
            memory = MemorySnapshot(1_000L, memory, false, 0L),
            storage = StorageSnapshot(StorageVolumeSnapshot(2_000L, storage), null),
            battery = BatterySnapshot(battery, temperature, null, null, null, BatteryHealth.Good, false, PluggedSource.None),
            network = NetworkSnapshot(rx, tx, true, false),
            thermalStatus = ThermalStatus.None,
        ),
        security = SecuritySnapshot(true, false, false, false, "2026-08-01", 29L, true, true, diagnostics = emptyList()),
        launchableAppCount = 10,
        healthSampleCount = 20,
        batteryHealthSnapshotCount = 3,
        recentGameSessionCount = 2,
    )
}
