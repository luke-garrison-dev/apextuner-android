package com.apextuner.feature.battery

import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.PluggedSource
import com.apextuner.core.model.ThermalStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryRecommendationEngineTest {
    private val healthy = BatterySnapshot(
        levelPercent = 75,
        temperatureCelsius = 30.0,
        voltageMillivolts = 4_000,
        currentMicroamps = -150_000,
        chargeCounterMicroampHours = 3_000_000,
        health = BatteryHealth.Good,
        charging = false,
        pluggedSource = PluggedSource.None,
    )

    @Test fun unknownThermalIsNotTreatedAsSevere() {
        val recommendations = BatteryRecommendationEngine.evaluate(healthy, ThermalStatus.Unknown, powerSaveMode = false)
        assertFalse(recommendations.any { it.contains("thermal stress", ignoreCase = true) })
    }

    @Test fun severeThermalProducesCoolingGuidance() {
        val recommendations = BatteryRecommendationEngine.evaluate(healthy, ThermalStatus.Severe, powerSaveMode = false)
        assertTrue(recommendations.any { it.contains("cool", ignoreCase = true) })
    }

    @Test fun lowBatterySuggestsAndroidSaverOnlyWhenItIsOff() {
        val low = healthy.copy(levelPercent = 15)
        assertTrue(BatteryRecommendationEngine.evaluate(low, ThermalStatus.None, false).any { it.contains("Battery Saver") })
        assertFalse(BatteryRecommendationEngine.evaluate(low, ThermalStatus.None, true).any { it.contains("Battery Saver") })
    }
}
