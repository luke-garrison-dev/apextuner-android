package com.apextuner.core.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.BatterySnapshot
import com.apextuner.core.model.PluggedSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryTelemetryReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun read(): BatterySnapshot {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else null
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.div(10.0)
            ?.takeIf { it in MIN_PLAUSIBLE_BATTERY_C..MAX_PLAUSIBLE_BATTERY_C }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it in MIN_PLAUSIBLE_MILLIVOLTS..MAX_PLAUSIBLE_MILLIVOLTS }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        return BatterySnapshot(
            levelPercent = levelPercent,
            temperatureCelsius = temperature,
            voltageMillivolts = voltage,
            currentMicroamps = batteryManager.readLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, allowNegative = true),
            chargeCounterMicroampHours = batteryManager.readLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            health = (health ?: BatteryManager.BATTERY_HEALTH_UNKNOWN).toBatteryHealth(),
            charging = charging,
            pluggedSource = plugged.toPluggedSource(),
            averageCurrentMicroamps = batteryManager.readLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, allowNegative = true),
            energyCounterNanowattHours = batteryManager.readLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            cycleCount = cycleCount,
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            present = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true,
        )
    }

    private fun BatteryManager.readLongPropertyOrNull(propertyId: Int, allowNegative: Boolean = false): Long? {
        val value = getLongProperty(propertyId)
        if (value == Long.MIN_VALUE) return null
        return value.takeIf { allowNegative || it >= 0L }
    }

    private fun Int.toBatteryHealth(): BatteryHealth = when (this) {
        BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.Good
        BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.Cold
        BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.Dead
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.Overheat
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OverVoltage
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.Failure
        else -> BatteryHealth.Unknown
    }

    private fun Int.toPluggedSource(): PluggedSource = when {
        this == 0 -> PluggedSource.None
        this and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PluggedSource.Ac
        this and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PluggedSource.Usb
        this and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PluggedSource.Wireless
        this and BATTERY_PLUGGED_DOCK != 0 -> PluggedSource.Dock
        else -> PluggedSource.Unknown
    }

    private companion object {
        const val BATTERY_PLUGGED_DOCK = 8
        const val MIN_PLAUSIBLE_BATTERY_C = -40.0
        const val MAX_PLAUSIBLE_BATTERY_C = 120.0
        const val MIN_PLAUSIBLE_MILLIVOLTS = 1
        const val MAX_PLAUSIBLE_MILLIVOLTS = 30_000
    }
}
