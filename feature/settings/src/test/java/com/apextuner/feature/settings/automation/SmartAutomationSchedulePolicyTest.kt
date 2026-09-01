package com.apextuner.feature.settings.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutomationSchedulePolicyTest {
    @Test
    fun schedulesOnlyWhenPremiumAndAtLeastOneRuleIsEnabled() {
        assertFalse(SmartAutomationSchedulePolicy.shouldSchedule(premium = false, hasEnabledRules = false))
        assertFalse(SmartAutomationSchedulePolicy.shouldSchedule(premium = false, hasEnabledRules = true))
        assertFalse(SmartAutomationSchedulePolicy.shouldSchedule(premium = true, hasEnabledRules = false))
        assertTrue(SmartAutomationSchedulePolicy.shouldSchedule(premium = true, hasEnabledRules = true))
    }
}
