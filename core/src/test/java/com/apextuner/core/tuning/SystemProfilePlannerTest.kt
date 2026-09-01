package com.apextuner.core.tuning

import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.SystemProfileBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemProfilePlannerTest {
    private val baseline = SystemProfileBackup(120_000L, true, activeProfile = SystemProfile.Balanced)

    @Test fun batteryIsConservative() {
        val targets = SystemProfilePlanner.targets(SystemProfile.Battery, baseline)
        assertEquals(30_000L, targets.screenOffTimeoutMillis)
        assertFalse(targets.hapticFeedbackEnabled)
    }

    @Test fun balancedRestoresExactBaseline() {
        val targets = SystemProfilePlanner.targets(SystemProfile.Balanced, baseline)
        assertEquals(120_000L, targets.screenOffTimeoutMillis)
        assertTrue(targets.hapticFeedbackEnabled)
    }

    @Test fun gamingNeverShortensOriginalTimeout() {
        val longBaseline = baseline.copy(originalScreenOffTimeoutMillis = 45L * 60L * 1_000L)
        assertEquals(45L * 60L * 1_000L, SystemProfilePlanner.targets(SystemProfile.Gaming, longBaseline).screenOffTimeoutMillis)
    }
    @Test fun balancedPreservesLargeExactBaseline() {
        val longBaseline = baseline.copy(originalScreenOffTimeoutMillis = 2L * 60L * 60L * 1_000L)
        assertEquals(2L * 60L * 60L * 1_000L, SystemProfilePlanner.targets(SystemProfile.Balanced, longBaseline).screenOffTimeoutMillis)
        assertEquals(30_000L, SystemProfilePlanner.targets(SystemProfile.Battery, longBaseline).screenOffTimeoutMillis)
    }

    @Test fun batteryUsesSafeTargetWithoutMutatingInvalidRollbackSnapshot() {
        val unusualBaseline = baseline.copy(originalScreenOffTimeoutMillis = 0L)
        assertEquals(0L, SystemProfilePlanner.targets(SystemProfile.Balanced, unusualBaseline).screenOffTimeoutMillis)
        assertEquals(30_000L, SystemProfilePlanner.targets(SystemProfile.Battery, unusualBaseline).screenOffTimeoutMillis)
    }

    @Test fun profileMatchDetectsExternalTimeoutChange() {
        assertTrue(
            SystemProfilePlanner.matches(
                SystemProfile.Battery, baseline, 30_000L, false, manageHaptics = true,
            ),
        )
        assertFalse(
            SystemProfilePlanner.matches(
                SystemProfile.Battery, baseline, 10L * 60L * 1_000L, false, manageHaptics = true,
            ),
        )
    }

    @Test fun modernAndroidProfileMatchIgnoresLegacyHapticState() {
        assertTrue(
            SystemProfilePlanner.matches(
                SystemProfile.Battery, baseline, 30_000L, true, manageHaptics = false,
            ),
        )
    }



    @Test fun privilegedBatteryTargetStaysInsideVerifiedHardwareRange() {
        val snapshot = PrivilegedTuningSnapshot(
            listOf(
                CpuPolicySnapshot(
                    policyId = 0,
                    governor = "schedutil",
                    availableGovernors = setOf("schedutil", "powersave", "performance"),
                    minimumFrequencyKHz = 300_000L,
                    maximumFrequencyKHz = 2_000_000L,
                    hardwareMinimumFrequencyKHz = 300_000L,
                    hardwareMaximumFrequencyKHz = 2_400_000L,
                ),
            ),
        )
        val target = SystemProfilePlanner.privilegedTargets(SystemProfile.Battery, snapshot)!!.cpuPolicies.single()
        assertEquals("powersave", target.governor)
        assertTrue(target.minimumFrequencyKHz >= 300_000L)
        assertTrue(target.maximumFrequencyKHz in target.minimumFrequencyKHz..2_400_000L)
    }

    @Test fun privilegedPerformanceFallsBackToVerifiedGovernorOnly() {
        val snapshot = PrivilegedTuningSnapshot(
            listOf(
                CpuPolicySnapshot(
                    policyId = 1,
                    governor = "conservative",
                    availableGovernors = setOf("conservative"),
                    minimumFrequencyKHz = 500_000L,
                    maximumFrequencyKHz = 1_500_000L,
                    hardwareMinimumFrequencyKHz = 500_000L,
                    hardwareMaximumFrequencyKHz = 1_500_000L,
                ),
            ),
        )
        val target = SystemProfilePlanner.privilegedTargets(SystemProfile.Performance, snapshot)!!.cpuPolicies.single()
        assertEquals("conservative", target.governor)
        assertEquals(1_500_000L, target.maximumFrequencyKHz)
    }
}
