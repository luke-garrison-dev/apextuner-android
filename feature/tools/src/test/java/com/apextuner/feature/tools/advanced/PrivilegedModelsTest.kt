package com.apextuner.feature.tools.advanced

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedModelsTest {
    @Test
    fun animationParserAcceptsOnlyFiniteSafeValues() {
        assertEquals(AnimationScales(0.5f, 1f, 2f), parseAnimationScales("window=0.5\ntransition=1\nanimator=2"))
        assertNull(parseAnimationScales("window=NaN\ntransition=1\nanimator=1"))
        assertNull(parseAnimationScales("window=11\ntransition=1\nanimator=1"))
        assertTrue(AnimationScales(0f, 10f, 0.5f).isSafe())
        assertFalse(AnimationScales(-0.1f, 1f, 1f).isSafe())
    }

    @Test
    fun privilegedOutputIsBoundedAndNulFree() {
        val bounded = boundedOutput("abc\u0000" + "x".repeat(1_000), maximumCharacters = 64)
        assertFalse(bounded.contains('\u0000'))
        assertTrue(bounded.contains("output truncated"))
        assertTrue(bounded.length < 100)
    }

    @Test
    fun privilegedOutputClampsInvalidRequestedBounds() {
        val negative = boundedOutput("secret\u0000payload", maximumCharacters = -5)
        assertFalse(negative.contains('\u0000'))
        assertTrue(negative.contains("output truncated"))

        val huge = boundedOutput("x".repeat(200_000), maximumCharacters = Int.MAX_VALUE)
        assertTrue(huge.contains("output truncated"))
        assertTrue(huge.length < 132_000)
    }

    @Test
    fun protectedPackagesAndSystemUidsAreRejected() {
        assertTrue(isProtectedPackageTarget("com.apextuner.app", "com.apextuner.app", 0, 12_345))
        assertTrue(isProtectedPackageTarget("android", "com.apextuner.app", 0, 1_000))
        assertTrue(isProtectedPackageTarget("com.android.settings", "com.apextuner.app", ApplicationInfo.FLAG_SYSTEM, 10_001))
        assertTrue(isProtectedPackageTarget("com.vendor.system", "com.apextuner.app", ApplicationInfo.FLAG_SYSTEM, 10_500))
        assertTrue(isProtectedPackageTarget("com.example.lowuid", "com.apextuner.app", 0, 9_999))
        assertFalse(isProtectedPackageTarget("com.example.userapp", "com.apextuner.app", 0, 12_345))
    }

    @Test
    fun enabledStateRollbackAllowListIsExact() {
        assertTrue(isRestorableEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertTrue(isRestorableEnabledState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertTrue(isRestorableEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertFalse(isRestorableEnabledState(999))
    }

    @Test
    fun cpuPolicyParserRejectsOutOfRangeOrMalformedSnapshots() {
        val valid = """
            governor=schedutil
            availableGovernors=schedutil powersave performance
            minimumKHz=300000
            maximumKHz=2000000
            hardwareMinimumKHz=300000
            hardwareMaximumKHz=2400000
        """.trimIndent()
        val parsed = parseCpuPolicySnapshot(0, valid)
        assertEquals("schedutil", parsed?.governor)
        assertEquals(2_400_000L, parsed?.hardwareMaximumFrequencyKHz)
        assertNull(parseCpuPolicySnapshot(64, valid))
        assertNull(parseCpuPolicySnapshot(0, valid.replace("maximumKHz=2000000", "maximumKHz=9999999")))
    }
}
