package com.apextuner.feature.settings.automation

import android.content.Context
import com.apextuner.core.database.AutomationEventDao
import com.apextuner.core.database.AutomationEventEntity
import com.apextuner.core.database.AutomationRuleDao
import com.apextuner.core.database.AutomationRuleEntity
import com.apextuner.core.database.DeviceHealthSampleDao
import com.apextuner.core.database.DeviceHealthSampleEntity
import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.ThermalStatus
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.core.tuning.TemporaryProfileOverrideCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal enum class SmartConditionType {
    LowBatteryNotCharging,
    BatteryTemperatureAbove,
    StorageFreeBelowPercent,
    ThermalSevereOrWorse,
    MeteredNetwork,
    ChargingBatteryTemperatureAbove,
    MemoryUsedAbovePercent,
    NetworkUnvalidated,
    ChargingBatteryLevelAtOrAbove,
}

internal enum class SmartActionType {
    ApplyBatteryProfile,
    Notify,
    CaptureDiagnosticSnapshot,
}

data class SmartAutomationSnapshot(
    val rules: List<AutomationRuleEntity>,
    val events: List<AutomationEventEntity>,
)

@Singleton
class SmartAutomationRepository @Inject constructor(
    private val ruleDao: AutomationRuleDao,
    private val eventDao: AutomationEventDao,
) {
    suspend fun ensureDefaults() {
        ruleDao.insertDefaults(defaultRules())
    }

    suspend fun snapshot(): SmartAutomationSnapshot {
        ensureDefaults()
        return SmartAutomationSnapshot(ruleDao.all(), eventDao.recent(12))
    }

    suspend fun enabledRules(): List<AutomationRuleEntity> {
        ensureDefaults()
        return ruleDao.enabled()
    }

    suspend fun hasEnabledRules(): Boolean = enabledRules().isNotEmpty()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ruleDao.setEnabled(id, enabled)
        // Re-enabling a rule is an explicit user action and should allow an immediate fresh
        // evaluation instead of inheriting an old cooldown from before it was disabled.
        if (enabled) ruleDao.clearLastTriggered(id)
    }

    suspend fun clearNotificationCooldowns() {
        ruleDao.clearLastTriggeredForAction(SmartActionType.Notify.name)
    }

    suspend fun allRules(): List<AutomationRuleEntity> {
        ensureDefaults()
        return ruleDao.all()
    }

    suspend fun setDryRun(id: String, dryRun: Boolean) {
        ruleDao.setDryRun(id, dryRun)
    }

    suspend fun setThreshold(id: String, thresholdValue: Double) {
        val rule = ruleDao.all().firstOrNull { it.id == id } ?: return
        val sanitized = SmartAutomationPolicy.sanitizeThreshold(rule, thresholdValue) ?: return
        ruleDao.setThreshold(id, sanitized)
    }

    suspend fun setCooldown(id: String, cooldownMillis: Long) {
        ruleDao.setCooldown(id, SmartAutomationPolicy.sanitizeCooldown(cooldownMillis))
    }

    suspend fun record(rule: AutomationRuleEntity, outcome: String, detail: String, markTriggered: Boolean = true) {
        val now = System.currentTimeMillis()
        eventDao.insert(
            AutomationEventEntity(
                ruleId = rule.id,
                ruleName = rule.name,
                createdAtEpochMillis = now,
                outcome = outcome,
                detail = detail.take(500),
            ),
        )
        if (markTriggered) ruleDao.markTriggered(rule.id, now)
        eventDao.deleteBefore(now - EVENT_RETENTION_MILLIS)
        eventDao.trimToNewest(EVENT_HISTORY_MAX_ROWS)
    }

    companion object {
        private const val EVENT_RETENTION_MILLIS = 90L * 24L * 60L * 60L * 1_000L
        private const val EVENT_HISTORY_MAX_ROWS = 1_000
        const val RULE_LOW_BATTERY = "low_battery_profile"
        const val RULE_HOT_BATTERY = "hot_battery_warning"
        const val RULE_LOW_STORAGE = "low_storage_warning"
        const val RULE_THERMAL = "thermal_warning"
        const val RULE_METERED = "metered_network_warning"
        const val RULE_CHARGING_HEAT = "charging_heat_warning"
        const val RULE_MEMORY_PRESSURE = "memory_pressure_capture"
        const val RULE_NETWORK_UNVALIDATED = "network_unvalidated_capture"
        const val RULE_CHARGE_LEVEL_REMINDER = "charge_level_reminder"

        fun defaultRules(): List<AutomationRuleEntity> = listOf(
            AutomationRuleEntity(
                id = RULE_LOW_BATTERY,
                name = "Low battery profile",
                enabled = false,
                conditionType = SmartConditionType.LowBatteryNotCharging.name,
                thresholdValue = 20.0,
                actionType = SmartActionType.ApplyBatteryProfile.name,
                actionArgument = null,
                cooldownMillis = 60L * 60L * 1_000L,
                dryRun = true,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_HOT_BATTERY,
                name = "Hot battery warning",
                enabled = false,
                conditionType = SmartConditionType.BatteryTemperatureAbove.name,
                thresholdValue = 42.0,
                actionType = SmartActionType.Notify.name,
                actionArgument = "Battery temperature is elevated. Reduce heavy workloads or charging heat.",
                cooldownMillis = 60L * 60L * 1_000L,
                dryRun = false,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_LOW_STORAGE,
                name = "Low storage warning",
                enabled = false,
                conditionType = SmartConditionType.StorageFreeBelowPercent.name,
                thresholdValue = 15.0,
                actionType = SmartActionType.Notify.name,
                actionArgument = "Internal storage is running low. Review reclaimable files before Android becomes constrained.",
                cooldownMillis = 24L * 60L * 60L * 1_000L,
                dryRun = false,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_THERMAL,
                name = "Severe thermal warning",
                enabled = false,
                conditionType = SmartConditionType.ThermalSevereOrWorse.name,
                thresholdValue = null,
                actionType = SmartActionType.Notify.name,
                actionArgument = "Android reports severe thermal pressure. Performance automation is suspended until the device cools.",
                cooldownMillis = 60L * 60L * 1_000L,
                dryRun = false,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_METERED,
                name = "Metered network reminder",
                enabled = false,
                conditionType = SmartConditionType.MeteredNetwork.name,
                thresholdValue = null,
                actionType = SmartActionType.Notify.name,
                actionArgument = "The active network is metered. Consider a restrictive firewall profile before large transfers.",
                cooldownMillis = 6L * 60L * 60L * 1_000L,
                dryRun = true,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_CHARGING_HEAT,
                name = "Warm while charging",
                enabled = false,
                conditionType = SmartConditionType.ChargingBatteryTemperatureAbove.name,
                thresholdValue = 40.0,
                actionType = SmartActionType.Notify.name,
                actionArgument = "Battery temperature is elevated while charging. Consider reducing workload or charging heat.",
                cooldownMillis = 60L * 60L * 1_000L,
                dryRun = true,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_MEMORY_PRESSURE,
                name = "Memory pressure capture",
                enabled = false,
                conditionType = SmartConditionType.MemoryUsedAbovePercent.name,
                thresholdValue = 90.0,
                actionType = SmartActionType.CaptureDiagnosticSnapshot.name,
                actionArgument = null,
                cooldownMillis = 60L * 60L * 1_000L,
                dryRun = false,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_NETWORK_UNVALIDATED,
                name = "Connectivity loss capture",
                enabled = false,
                conditionType = SmartConditionType.NetworkUnvalidated.name,
                thresholdValue = null,
                actionType = SmartActionType.CaptureDiagnosticSnapshot.name,
                actionArgument = null,
                cooldownMillis = 60L * 60L * 1_000L,
                dryRun = false,
                lastTriggeredAtEpochMillis = null,
            ),
            AutomationRuleEntity(
                id = RULE_CHARGE_LEVEL_REMINDER,
                name = "Charge level reminder",
                enabled = false,
                conditionType = SmartConditionType.ChargingBatteryLevelAtOrAbove.name,
                thresholdValue = 85.0,
                actionType = SmartActionType.Notify.name,
                actionArgument = "Battery reached your selected reminder level while charging. This is a reminder only; ApexTuner does not stop charging.",
                cooldownMillis = 6L * 60L * 60L * 1_000L,
                dryRun = true,
                lastTriggeredAtEpochMillis = null,
            ),
        )
    }
}

internal object SmartAutomationSchedulePolicy {
    fun shouldSchedule(premium: Boolean, hasEnabledRules: Boolean): Boolean = premium && hasEnabledRules
}

internal object SmartAutomationPolicy {
    const val MIN_COOLDOWN_MILLIS = 30L * 60L * 1_000L
    const val MAX_COOLDOWN_MILLIS = 48L * 60L * 60L * 1_000L

    fun thresholdOptions(rule: AutomationRuleEntity): List<Double> = when (
        runCatching { SmartConditionType.valueOf(rule.conditionType) }.getOrNull()
    ) {
        SmartConditionType.LowBatteryNotCharging -> listOf(10.0, 15.0, 20.0, 25.0, 30.0)
        SmartConditionType.BatteryTemperatureAbove,
        SmartConditionType.ChargingBatteryTemperatureAbove -> listOf(38.0, 40.0, 42.0, 44.0, 46.0)
        SmartConditionType.StorageFreeBelowPercent -> listOf(5.0, 10.0, 15.0, 20.0, 25.0)
        SmartConditionType.MemoryUsedAbovePercent -> listOf(75.0, 80.0, 85.0, 90.0, 95.0)
        SmartConditionType.ChargingBatteryLevelAtOrAbove -> listOf(80.0, 85.0, 90.0, 95.0)
        else -> emptyList()
    }

    fun thresholdLabel(rule: AutomationRuleEntity, value: Double): String = when (
        runCatching { SmartConditionType.valueOf(rule.conditionType) }.getOrNull()
    ) {
        SmartConditionType.BatteryTemperatureAbove,
        SmartConditionType.ChargingBatteryTemperatureAbove -> "${value.toInt()} °C"
        SmartConditionType.LowBatteryNotCharging,
        SmartConditionType.StorageFreeBelowPercent,
        SmartConditionType.MemoryUsedAbovePercent,
        SmartConditionType.ChargingBatteryLevelAtOrAbove -> "${value.toInt()}%"
        else -> value.toString()
    }

    fun sanitizeThreshold(rule: AutomationRuleEntity, value: Double): Double? {
        if (!value.isFinite()) return null
        val allowed = thresholdOptions(rule)
        if (allowed.isEmpty()) return null
        return allowed.minByOrNull { kotlin.math.abs(it - value) }
    }

    fun cooldownOptions(): List<Long> = listOf(
        30L * 60L * 1_000L,
        60L * 60L * 1_000L,
        6L * 60L * 60L * 1_000L,
        12L * 60L * 60L * 1_000L,
        24L * 60L * 60L * 1_000L,
        48L * 60L * 60L * 1_000L,
    )

    fun sanitizeCooldown(value: Long): Long = value.coerceIn(MIN_COOLDOWN_MILLIS, MAX_COOLDOWN_MILLIS)

    fun cooldownLabel(value: Long): String = when {
        value < 60L * 60L * 1_000L -> "${value / (60L * 1_000L)} min"
        value < 24L * 60L * 60L * 1_000L -> "${value / (60L * 60L * 1_000L)} h"
        else -> "${value / (24L * 60L * 60L * 1_000L)} d"
    }

    fun isBatteryProfileRule(rule: AutomationRuleEntity): Boolean =
        rule.actionType == SmartActionType.ApplyBatteryProfile.name

    fun requiresModifySystemSettings(rule: AutomationRuleEntity): Boolean =
        isBatteryProfileRule(rule) && !rule.dryRun

    fun isNotificationRule(rule: AutomationRuleEntity): Boolean =
        rule.actionType == SmartActionType.Notify.name

    fun isDiagnosticCaptureRule(rule: AutomationRuleEntity): Boolean =
        rule.actionType == SmartActionType.CaptureDiagnosticSnapshot.name
}

internal object SmartAutomationEvaluator {
    fun matches(rule: AutomationRuleEntity, snapshot: DeviceSnapshot): Boolean {
        return when (runCatching { SmartConditionType.valueOf(rule.conditionType) }.getOrNull()) {
            SmartConditionType.LowBatteryNotCharging -> {
                val threshold = rule.thresholdValue ?: return false
                val level = snapshot.battery.levelPercent?.toDouble() ?: return false
                level <= threshold && !snapshot.battery.charging
            }
            SmartConditionType.BatteryTemperatureAbove -> {
                val threshold = rule.thresholdValue ?: return false
                val temperature = snapshot.battery.temperatureCelsius ?: return false
                temperature >= threshold
            }
            SmartConditionType.StorageFreeBelowPercent -> {
                val threshold = rule.thresholdValue ?: return false
                val storage = snapshot.storage.internal
                storage.totalBytes > 0L && storage.availableBytes.toDouble() * 100.0 / storage.totalBytes.toDouble() <= threshold
            }
            SmartConditionType.ThermalSevereOrWorse -> snapshot.thermalStatus in setOf(
                ThermalStatus.Severe,
                ThermalStatus.Critical,
                ThermalStatus.Emergency,
                ThermalStatus.Shutdown,
            )
            SmartConditionType.MeteredNetwork -> snapshot.network.metered
            SmartConditionType.ChargingBatteryTemperatureAbove -> {
                val threshold = rule.thresholdValue ?: return false
                val temperature = snapshot.battery.temperatureCelsius ?: return false
                snapshot.battery.charging && temperature >= threshold
            }
            SmartConditionType.MemoryUsedAbovePercent -> {
                val threshold = rule.thresholdValue ?: return false
                snapshot.memory.usedFraction * 100.0 >= threshold
            }
            SmartConditionType.NetworkUnvalidated -> !snapshot.network.activeNetworkValidated
            SmartConditionType.ChargingBatteryLevelAtOrAbove -> {
                val threshold = rule.thresholdValue ?: return false
                val level = snapshot.battery.levelPercent?.toDouble() ?: return false
                snapshot.battery.charging && level >= threshold
            }
            null -> false
        }
    }

    fun description(rule: AutomationRuleEntity): String = when (
        runCatching { SmartConditionType.valueOf(rule.conditionType) }.getOrNull()
    ) {
        SmartConditionType.LowBatteryNotCharging -> "Battery ≤ ${rule.thresholdValue?.toInt() ?: 20}% and not charging"
        SmartConditionType.BatteryTemperatureAbove -> "Battery temperature ≥ ${rule.thresholdValue ?: 42.0} °C"
        SmartConditionType.StorageFreeBelowPercent -> "Free internal storage ≤ ${rule.thresholdValue?.toInt() ?: 15}%"
        SmartConditionType.ThermalSevereOrWorse -> "Android thermal status is Severe or worse"
        SmartConditionType.MeteredNetwork -> "Active network is metered"
        SmartConditionType.ChargingBatteryTemperatureAbove -> "Battery temperature ≥ ${rule.thresholdValue ?: 40.0} °C while charging"
        SmartConditionType.MemoryUsedAbovePercent -> "Memory used ≥ ${rule.thresholdValue?.toInt() ?: 90}%"
        SmartConditionType.NetworkUnvalidated -> "Android does not report the active network as internet-validated"
        SmartConditionType.ChargingBatteryLevelAtOrAbove -> "Battery ≥ ${rule.thresholdValue?.toInt() ?: 85}% while charging"
        null -> "Unsupported condition"
    }
}

@Singleton
class SmartAutomationOwnershipStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("apextuner_smart_automation", Context.MODE_PRIVATE)

    fun ownerRuleId(): String? = prefs.getString(KEY_PROFILE_OWNER, null)
    fun setProfileOwner(ruleId: String) { prefs.edit().putString(KEY_PROFILE_OWNER, ruleId).apply() }
    fun clearProfileOwner() { prefs.edit().remove(KEY_PROFILE_OWNER).apply() }

    private companion object { const val KEY_PROFILE_OWNER = "profile_owner_rule" }
}

@Singleton
class SmartAutomationExecutor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tuning: SafeSystemTuningController,
    private val ownership: SmartAutomationOwnershipStore,
    private val temporaryProfileOverride: TemporaryProfileOverrideCoordinator,
    private val deviceHealthSampleDao: DeviceHealthSampleDao,
) {
    fun hasOwnedProfile(): Boolean = ownership.ownerRuleId() != null

    suspend fun execute(rule: AutomationRuleEntity, snapshot: DeviceSnapshot): ExecutionResult {
        val action = runCatching { SmartActionType.valueOf(rule.actionType) }.getOrNull()
            ?: return ExecutionResult(false, "Unsupported automation action.")
        return when (action) {
            SmartActionType.Notify -> {
                val posted = AutomationNotifications.notify(
                    context,
                    7300 + (rule.id.hashCode() and 0x3ff),
                    rule.name,
                    rule.actionArgument ?: "ApexTuner automation condition matched.",
                )
                if (posted) ExecutionResult(true, rule.actionArgument ?: "Notification posted.")
                else ExecutionResult(false, "Android notification permission or policy prevented this reminder from being posted.")
            }
            SmartActionType.CaptureDiagnosticSnapshot -> {
                deviceHealthSampleDao.insert(snapshot.toHealthSampleEntity())
                ExecutionResult(true, "Captured a local diagnostic snapshot for later trend correlation.")
            }
            SmartActionType.ApplyBatteryProfile -> {
                if (tuning.activeProfile() != SystemProfile.Balanced) {
                    ExecutionResult(false, "Skipped because a non-Balanced profile is already active.")
                } else when (val result = tuning.apply(SystemProfile.Battery)) {
                    is ProfileApplyResult.Applied -> {
                        ownership.setProfileOwner(rule.id)
                        ExecutionResult(true, "Battery profile applied with an owned restore point.")
                    }
                    is ProfileApplyResult.PermissionRequired -> ExecutionResult(false, "Modify system settings access is required.")
                    is ProfileApplyResult.Superseded -> ExecutionResult(false, "A newer profile request superseded this automation.")
                    is ProfileApplyResult.Failed -> ExecutionResult(false, result.reason)
                }
            }
        }
    }

    suspend fun restoreIfOwnedAndConditionCleared(
        rules: List<AutomationRuleEntity>,
        snapshot: DeviceSnapshot,
        forceRestore: Boolean = false,
    ): String? {
        val ownerId = ownership.ownerRuleId() ?: return null
        val owner = rules.firstOrNull { it.id == ownerId }
        if (!forceRestore && owner != null && owner.enabled && SmartAutomationEvaluator.matches(owner, snapshot)) return null
        temporaryProfileOverride.activeOwner()?.let { temporaryOwner ->
            return "Automation restore deferred while $temporaryOwner temporarily controls the system profile."
        }
        if (tuning.activeProfile() != SystemProfile.Battery) {
            ownership.clearProfileOwner()
            return "Automation ownership cleared because the active profile changed outside this rule."
        }
        return when (val result = tuning.restoreBalanced()) {
            is ProfileApplyResult.Applied -> {
                ownership.clearProfileOwner()
                "Battery condition cleared; ApexTuner restored the owned Balanced baseline."
            }
            is ProfileApplyResult.PermissionRequired -> "Battery condition cleared, but Android no longer grants permission to restore the owned baseline."
            is ProfileApplyResult.Superseded -> "Restore deferred because a newer profile request is active."
            is ProfileApplyResult.Failed -> result.reason
        }
    }

    data class ExecutionResult(val success: Boolean, val detail: String)
}



private fun DeviceSnapshot.toHealthSampleEntity(): DeviceHealthSampleEntity = DeviceHealthSampleEntity(
    capturedAtEpochMillis = capturedAtEpochMillis,
    cpuUsagePercent = cpu.totalUsagePercent,
    memoryUsedPercent = memory.usedFraction * 100.0,
    internalStorageAvailableBytes = storage.internal.availableBytes,
    internalStorageTotalBytes = storage.internal.totalBytes,
    batteryLevelPercent = battery.levelPercent,
    batteryTemperatureCelsius = battery.temperatureCelsius,
    batteryCurrentMicroamps = battery.currentMicroamps,
    batteryCharging = battery.charging,
    thermalStatus = thermalStatus.name,
    networkMetered = network.metered,
    networkValidated = network.activeNetworkValidated,
    totalRxBytes = network.totalRxBytes,
    totalTxBytes = network.totalTxBytes,
)

/**
 * Reconciles Smart Automation profile ownership after rule edits, app restarts, or permission
 * changes. It performs no device snapshot unless ApexTuner actually owns a profile mutation.
 */
@Singleton
class SmartAutomationRecovery @Inject internal constructor(
    private val repository: SmartAutomationRepository,
    private val executor: SmartAutomationExecutor,
    private val deviceRepository: DeviceRepository,
) {
    suspend fun reconcileOwnedProfile(forceRestore: Boolean = false): String? {
        if (!executor.hasOwnedProfile()) return null
        val rules = repository.allRules()
        val snapshot = deviceRepository.snapshot()
        return executor.restoreIfOwnedAndConditionCleared(rules, snapshot, forceRestore)
    }
}
