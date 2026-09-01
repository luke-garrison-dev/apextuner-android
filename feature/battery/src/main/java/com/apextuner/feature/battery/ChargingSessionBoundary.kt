package com.apextuner.feature.battery

import com.apextuner.core.database.ChargingSessionEntity
import com.apextuner.core.model.BatterySnapshot

/**
 * Applies the first post-charge observation only to terminal counters. Thermal/current metrics
 * belong to observations made while Android still reports the battery as charging.
 */
internal fun ChargingSessionEntity.withTerminalBatterySample(battery: BatterySnapshot): ChargingSessionEntity = copy(
    endLevelPercent = battery.levelPercent ?: endLevelPercent,
    endChargeCounterMicroampHours = battery.chargeCounterMicroampHours ?: endChargeCounterMicroampHours,
    endCycleCount = battery.cycleCount ?: endCycleCount,
)
