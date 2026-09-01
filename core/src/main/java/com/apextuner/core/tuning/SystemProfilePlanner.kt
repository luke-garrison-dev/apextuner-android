package com.apextuner.core.tuning

import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.SystemProfileBackup
import kotlin.math.roundToLong

data class SystemProfileTargets(
    val screenOffTimeoutMillis: Long,
    val hapticFeedbackEnabled: Boolean,
)

object SystemProfilePlanner {
    fun targets(profile: SystemProfile, baseline: SystemProfileBackup): SystemProfileTargets = when (profile) {
        SystemProfile.Balanced -> SystemProfileTargets(
            baseline.originalScreenOffTimeoutMillis,
            baseline.originalHapticFeedbackEnabled,
        )
        SystemProfile.Battery -> SystemProfileTargets(
            minOf(safePositiveTimeout(baseline.originalScreenOffTimeoutMillis, BATTERY_TIMEOUT_MILLIS), BATTERY_TIMEOUT_MILLIS),
            false,
        )
        SystemProfile.Performance -> SystemProfileTargets(
            maxOf(safePositiveTimeout(baseline.originalScreenOffTimeoutMillis, PERFORMANCE_TIMEOUT_MILLIS), PERFORMANCE_TIMEOUT_MILLIS),
            baseline.originalHapticFeedbackEnabled,
        )
        SystemProfile.Gaming -> SystemProfileTargets(
            maxOf(safePositiveTimeout(baseline.originalScreenOffTimeoutMillis, GAMING_TIMEOUT_MILLIS), GAMING_TIMEOUT_MILLIS),
            baseline.originalHapticFeedbackEnabled,
        )
    }

    fun privilegedTargets(
        profile: SystemProfile,
        snapshot: PrivilegedTuningSnapshot,
    ): PrivilegedTuningTargets? {
        if (!snapshot.isUsable()) return null
        val policies = snapshot.cpuPolicies.map { policy ->
            val governor = when (profile) {
                SystemProfile.Balanced -> policy.governor
                SystemProfile.Battery -> firstAvailable(policy, "powersave", "schedutil", policy.governor)
                SystemProfile.Performance -> firstAvailable(policy, "performance", "schedutil", policy.governor)
                SystemProfile.Gaming -> firstAvailable(policy, "schedutil", "performance", policy.governor)
            }
            val targetMaximum = when (profile) {
                SystemProfile.Battery -> percentOfRange(policy, BATTERY_MAX_PERCENT)
                else -> policy.hardwareMaximumFrequencyKHz
            }
            val target = CpuPolicyTarget(
                policyId = policy.policyId,
                governor = governor,
                minimumFrequencyKHz = policy.hardwareMinimumFrequencyKHz,
                maximumFrequencyKHz = targetMaximum.coerceIn(
                    policy.hardwareMinimumFrequencyKHz,
                    policy.hardwareMaximumFrequencyKHz,
                ),
            )
            if (!target.isSafeAgainst(policy)) return null
            target
        }
        return PrivilegedTuningTargets(
            cpuPolicies = policies,
            thermalPolicy = PrivilegedTuningTargets.ThermalPolicy.PlatformManaged,
        )
    }

    fun matches(
        profile: SystemProfile,
        baseline: SystemProfileBackup,
        currentScreenOffTimeoutMillis: Long,
        currentHapticFeedbackEnabled: Boolean,
        manageHaptics: Boolean,
    ): Boolean {
        val expected = targets(profile, baseline)
        return currentScreenOffTimeoutMillis == expected.screenOffTimeoutMillis &&
            (!manageHaptics || currentHapticFeedbackEnabled == expected.hapticFeedbackEnabled)
    }

    private fun firstAvailable(policy: CpuPolicySnapshot, vararg preferred: String): String =
        preferred.firstOrNull { it in policy.availableGovernors } ?: policy.governor

    private fun percentOfRange(policy: CpuPolicySnapshot, percent: Int): Long {
        require(percent in 1..100)
        val low = policy.hardwareMinimumFrequencyKHz
        val high = policy.hardwareMaximumFrequencyKHz
        val span = (high - low).coerceAtLeast(0L)
        return (low.toDouble() + span.toDouble() * percent.toDouble() / 100.0).roundToLong()
    }

    private fun safePositiveTimeout(value: Long, fallback: Long): Long = if (value > 0L) value else fallback

    private const val BATTERY_MAX_PERCENT = 70
    private const val BATTERY_TIMEOUT_MILLIS = 30_000L
    private const val PERFORMANCE_TIMEOUT_MILLIS = 5L * 60L * 1_000L
    private const val GAMING_TIMEOUT_MILLIS = 30L * 60L * 1_000L
}
