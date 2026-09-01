package com.apextuner.feature.battery

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryDisplayLogicTest {
    @Test fun chargingStateOverridesOemCurrentSign() {
        assertEquals("charging", batteryCurrentDirection(-850_000L, charging = true))
        assertEquals("charging", batteryCurrentDirection(850_000L, charging = true))
        assertEquals("discharging", batteryCurrentDirection(850_000L, charging = false))
        assertEquals("discharging", batteryCurrentDirection(-850_000L, charging = false))
    }

    @Test fun zeroCurrentIsIdleRegardlessOfChargingFlag() {
        assertEquals("idle", batteryCurrentDirection(0L, charging = true))
        assertEquals("idle", batteryCurrentDirection(0L, charging = false))
    }
}
