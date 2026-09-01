package com.apextuner.feature.settings.automation

import com.apextuner.core.database.AutomationRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutomationPolicyTest {
    @Test
    fun defaultsRequireExplicitOptInAndMutatingRuleStartsDryRun() {
        val defaults = SmartAutomationRepository.defaultRules()
        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.none { it.enabled })
        val lowBattery = defaults.single { it.id == SmartAutomationRepository.RULE_LOW_BATTERY }
        assertTrue(lowBattery.dryRun)
    }

    @Test
    fun thresholdIsSnappedToSupportedSafeValue() {
        val rule = rule(SmartConditionType.LowBatteryNotCharging, 20.0)
        assertEquals(25.0, SmartAutomationPolicy.sanitizeThreshold(rule, 24.4)!!, 0.0)
        assertNull(SmartAutomationPolicy.sanitizeThreshold(rule.copy(conditionType = SmartConditionType.MeteredNetwork.name), 20.0))
    }

    @Test
    fun chargeReminderIsOptInBoundedAndExplicitlyReminderOnly() {
        val chargeReminder = SmartAutomationRepository.defaultRules()
            .single { it.id == SmartAutomationRepository.RULE_CHARGE_LEVEL_REMINDER }

        assertFalse(chargeReminder.enabled)
        assertTrue(chargeReminder.dryRun)
        assertEquals(SmartActionType.Notify.name, chargeReminder.actionType)
        assertEquals(listOf(80.0, 85.0, 90.0, 95.0), SmartAutomationPolicy.thresholdOptions(chargeReminder))
        assertEquals(90.0, SmartAutomationPolicy.sanitizeThreshold(chargeReminder, 89.0)!!, 0.0)
        assertTrue(chargeReminder.actionArgument.orEmpty().contains("does not stop charging"))
    }

    @Test
    fun cooldownIsBounded() {
        assertEquals(SmartAutomationPolicy.MIN_COOLDOWN_MILLIS, SmartAutomationPolicy.sanitizeCooldown(1L))
        assertEquals(SmartAutomationPolicy.MAX_COOLDOWN_MILLIS, SmartAutomationPolicy.sanitizeCooldown(Long.MAX_VALUE))
        assertFalse(SmartAutomationPolicy.cooldownOptions().isEmpty())
    }

    @Test
    fun actionCapabilitiesAreExplicitAndDryRunDoesNotRequireWriteSettings() {
        val batteryRule = AutomationRuleEntity(
            id = "battery", name = "battery", enabled = true,
            conditionType = SmartConditionType.LowBatteryNotCharging.name, thresholdValue = 20.0,
            actionType = SmartActionType.ApplyBatteryProfile.name, actionArgument = null,
            cooldownMillis = 60L * 60L * 1_000L, dryRun = true, lastTriggeredAtEpochMillis = null,
        )
        assertTrue(SmartAutomationPolicy.isBatteryProfileRule(batteryRule))
        assertFalse(SmartAutomationPolicy.requiresModifySystemSettings(batteryRule))
        assertTrue(SmartAutomationPolicy.requiresModifySystemSettings(batteryRule.copy(dryRun = false)))
        assertFalse(SmartAutomationPolicy.isNotificationRule(batteryRule))
        assertTrue(SmartAutomationPolicy.isNotificationRule(batteryRule.copy(actionType = SmartActionType.Notify.name)))
    }

    private fun rule(type: SmartConditionType, threshold: Double?) = AutomationRuleEntity(
        id = "test",
        name = "test",
        enabled = true,
        conditionType = type.name,
        thresholdValue = threshold,
        actionType = SmartActionType.Notify.name,
        actionArgument = null,
        cooldownMillis = 60L * 60L * 1_000L,
        dryRun = true,
        lastTriggeredAtEpochMillis = null,
    )
}
