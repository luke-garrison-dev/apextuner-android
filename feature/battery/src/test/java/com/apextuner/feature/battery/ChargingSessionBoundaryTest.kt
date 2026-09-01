package com.apextuner.feature.battery

import com.apextuner.core.database.ChargingSessionEntity
import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.PluggedSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingSessionBoundaryTest {
    @Test
    fun unplugObservationUpdatesTerminalCountersWithoutPollutingChargingStatistics() {
        val active = ChargingSessionEntity(
            startedAtEpochMillis = 1_000L,
            endedAtEpochMillis = null,
            startLevelPercent = 20,
            endLevelPercent = 80,
            startChargeCounterMicroampHours = 1_000_000L,
            endChargeCounterMicroampHours = 3_000_000L,
            startCycleCount = 100,
            endCycleCount = 100,
            maxTemperatureCelsius = 39.0,
            temperatureSumCelsius = 72.0,
            temperatureSampleCount = 2,
            maxAbsCurrentMicroamps = 2_000_000L,
            sampleCount = 2,
        )
        val unplugged = BatterySnapshot(
            levelPercent = 81,
            temperatureCelsius = 47.0,
            voltageMillivolts = 4_200,
            currentMicroamps = -4_500_000L,
            chargeCounterMicroampHours = 3_050_000L,
            health = BatteryHealth.Good,
            charging = false,
            pluggedSource = PluggedSource.None,
            cycleCount = 101,
        )

        val terminal = active.withTerminalBatterySample(unplugged)

        assertEquals(81, terminal.endLevelPercent)
        assertEquals(3_050_000L, terminal.endChargeCounterMicroampHours)
        assertEquals(101, terminal.endCycleCount)
        assertEquals(39.0, terminal.maxTemperatureCelsius!!, 0.0)
        assertEquals(72.0, terminal.temperatureSumCelsius, 0.0)
        assertEquals(2, terminal.temperatureSampleCount)
        assertEquals(2_000_000L, terminal.maxAbsCurrentMicroamps)
        assertEquals(2, terminal.sampleCount)
    }
}
