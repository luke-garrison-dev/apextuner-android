package com.apextuner.feature.dashboard.model

import com.apextuner.core.database.DeviceHealthSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthTimelineModelsTest {
    @Test
    fun summaryDerivesEventsCorrelationStorageAndTrafficWithoutExtraSampling() {
        val rows = listOf(
            sample(1, cpu = 80.0, memory = 50.0, storage = 1_000, temp = 41.0, thermal = "None", metered = false, rx = 100, tx = 50),
            sample(2, cpu = 75.0, memory = 60.0, storage = 800, temp = 42.0, thermal = "Severe", metered = true, rx = 300, tx = 150),
            sample(3, cpu = 20.0, memory = 70.0, storage = 700, temp = 36.0, thermal = "Critical", metered = true, rx = 500, tx = 250),
            sample(4, cpu = 10.0, memory = 40.0, storage = 650, temp = 35.0, thermal = "None", metered = false, rx = 700, tx = 350),
        )

        val summary = rows.toHealthTimeline(HealthTimelineRange.Day).summary

        assertEquals(4, summary.sampleCount)
        assertEquals(46.25, summary.averageCpuUsagePercent!!, 0.001)
        assertEquals(2, summary.elevatedThermalSamples)
        assertEquals(1, summary.elevatedThermalEvents)
        assertEquals(2, summary.highCpuHotBatterySamples)
        assertEquals(-350L, summary.storageFreeDeltaBytes)
        assertEquals(50, summary.meteredSamplePercent)
        assertEquals(900L, summary.observedTrafficBytes)
    }

    @Test
    fun counterResetDoesNotInventTraffic() {
        val rows = listOf(
            sample(1, rx = 1_000, tx = 2_000),
            sample(2, rx = 50, tx = 25),
        )

        assertNull(rows.toHealthTimeline(HealthTimelineRange.Day).summary.observedTrafficBytes)
    }

    private fun sample(
        time: Long,
        cpu: Double? = 20.0,
        memory: Double = 50.0,
        storage: Long = 1_000,
        temp: Double? = 35.0,
        thermal: String = "None",
        metered: Boolean = false,
        rx: Long? = null,
        tx: Long? = null,
    ) = DeviceHealthSampleEntity(
        capturedAtEpochMillis = time,
        cpuUsagePercent = cpu,
        memoryUsedPercent = memory,
        internalStorageAvailableBytes = storage,
        internalStorageTotalBytes = 2_000,
        batteryLevelPercent = 50,
        batteryTemperatureCelsius = temp,
        batteryCurrentMicroamps = null,
        batteryCharging = false,
        thermalStatus = thermal,
        networkMetered = metered,
        networkValidated = true,
        totalRxBytes = rx,
        totalTxBytes = tx,
    )
}
