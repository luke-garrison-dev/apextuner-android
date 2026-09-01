package com.apextuner.feature.battery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingSessionMaintenancePolicyTest {
    @Test
    fun pruneRunsInitiallyAfterOneDayAndAfterClockRollback() {
        val day = ChargingSessionMaintenancePolicy.PRUNE_INTERVAL_MILLIS
        assertTrue(ChargingSessionMaintenancePolicy.shouldPrune(null, 1000L))
        assertFalse(ChargingSessionMaintenancePolicy.shouldPrune(1000L, 1000L + day - 1L))
        assertTrue(ChargingSessionMaintenancePolicy.shouldPrune(1000L, 1000L + day))
        assertTrue(ChargingSessionMaintenancePolicy.shouldPrune(10_000L, 9_000L))
    }
}
