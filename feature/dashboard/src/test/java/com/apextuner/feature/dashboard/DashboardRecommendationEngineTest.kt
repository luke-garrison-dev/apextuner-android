package com.apextuner.feature.dashboard

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
import com.apextuner.feature.dashboard.model.RecommendationSeverity
import com.apextuner.feature.dashboard.model.RecommendationType
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardRecommendationEngineTest {

    private val engine = DashboardRecommendationEngine()

    @Test
    fun severeThermalStateIsHighestPriority() {
        val recommendations = engine.evaluate(snapshot(thermalStatus = ThermalStatus.Severe))

        assertEquals(RecommendationSeverity.Critical, recommendations.first().severity)
        assertEquals(RecommendationType.ThermalCritical, recommendations.first().type)
    }

    @Test
    fun lowStorageProducesSafeNonDestructiveGuidance() {
        val recommendations = engine.evaluate(snapshot(storageAvailable = 9_000L))

        val storageRecommendation = recommendations.first { it.type == RecommendationType.StorageCritical }
        assertEquals(RecommendationSeverity.Critical, storageRecommendation.severity)
    }

    @Test
    fun healthySnapshotStillProvidesTruthfulStatus() {
        val recommendations = engine.evaluate(snapshot())

        assertEquals(1, recommendations.size)
        assertEquals(RecommendationType.Healthy, recommendations.single().type)
    }

    private fun snapshot(
        thermalStatus: ThermalStatus = ThermalStatus.None,
        storageAvailable: Long = 50_000L,
    ) = DeviceSnapshot(
        capturedAtEpochMillis = 1_000L,
        uptimeMillis = 5_000L,
        cpu = CpuSnapshot(8, 20.0, emptyList()),
        gpu = GpuSnapshot(null),
        memory = MemorySnapshot(8_000L, 4_000L, false, 1_000L),
        storage = StorageSnapshot(
            internal = StorageVolumeSnapshot(100_000L, storageAvailable),
            primaryShared = null,
        ),
        battery = BatterySnapshot(
            levelPercent = 80,
            temperatureCelsius = 30.0,
            voltageMillivolts = 4_000,
            currentMicroamps = -200_000L,
            chargeCounterMicroampHours = null,
            health = BatteryHealth.Good,
            charging = false,
            pluggedSource = PluggedSource.None,
        ),
        network = NetworkSnapshot(10_000L, 5_000L, true, false),
        thermalStatus = thermalStatus,
    )
}
