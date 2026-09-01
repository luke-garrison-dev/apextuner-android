package com.apextuner.core.tuning

import com.apextuner.core.model.PrivilegedCpuPolicyBackup

data class CpuPolicySnapshot(
    val policyId: Int,
    val governor: String,
    val availableGovernors: Set<String>,
    val minimumFrequencyKHz: Long,
    val maximumFrequencyKHz: Long,
    val hardwareMinimumFrequencyKHz: Long,
    val hardwareMaximumFrequencyKHz: Long,
) {
    fun isValid(): Boolean =
        policyId in 0..63 &&
            GOVERNOR_REGEX.matches(governor) &&
            availableGovernors.isNotEmpty() &&
            governor in availableGovernors &&
            availableGovernors.all(GOVERNOR_REGEX::matches) &&
            hardwareMinimumFrequencyKHz > 0L &&
            hardwareMaximumFrequencyKHz >= hardwareMinimumFrequencyKHz &&
            minimumFrequencyKHz in hardwareMinimumFrequencyKHz..hardwareMaximumFrequencyKHz &&
            maximumFrequencyKHz in minimumFrequencyKHz..hardwareMaximumFrequencyKHz

    fun toBackup(): PrivilegedCpuPolicyBackup =
        PrivilegedCpuPolicyBackup(policyId, governor, minimumFrequencyKHz, maximumFrequencyKHz)

    private companion object {
        val GOVERNOR_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

data class CpuPolicyTarget(
    val policyId: Int,
    val governor: String,
    val minimumFrequencyKHz: Long,
    val maximumFrequencyKHz: Long,
) {
    fun isSafeAgainst(snapshot: CpuPolicySnapshot): Boolean =
        policyId == snapshot.policyId &&
            governor in snapshot.availableGovernors &&
            minimumFrequencyKHz in snapshot.hardwareMinimumFrequencyKHz..snapshot.hardwareMaximumFrequencyKHz &&
            maximumFrequencyKHz in minimumFrequencyKHz..snapshot.hardwareMaximumFrequencyKHz
}

data class PrivilegedTuningSnapshot(
    val cpuPolicies: List<CpuPolicySnapshot>,
) {
    fun isUsable(): Boolean =
        cpuPolicies.isNotEmpty() &&
            cpuPolicies.size <= 64 &&
            cpuPolicies.all(CpuPolicySnapshot::isValid) &&
            cpuPolicies.map { it.policyId }.distinct().size == cpuPolicies.size
}

data class PrivilegedTuningTargets(
    val cpuPolicies: List<CpuPolicyTarget>,
    val thermalPolicy: ThermalPolicy = ThermalPolicy.PlatformManaged,
) {
    enum class ThermalPolicy { PlatformManaged }
}

sealed interface PrivilegedTuningResult {
    data class Applied(val changedPolicies: Int, val message: String) : PrivilegedTuningResult
    data class Unavailable(val reason: String) : PrivilegedTuningResult
    data class Failed(val reason: String, val rollbackVerified: Boolean) : PrivilegedTuningResult
}

/**
 * Typed privileged tuning boundary used by SafeSystemTuningController.
 * Implementations must reject arbitrary paths/commands and preserve Android thermal protections.
 */
interface PrivilegedSystemTuningGateway {
    suspend fun snapshot(): PrivilegedTuningSnapshot?
    suspend fun apply(targets: PrivilegedTuningTargets, baseline: PrivilegedTuningSnapshot): PrivilegedTuningResult
    suspend fun restore(baseline: PrivilegedTuningSnapshot): PrivilegedTuningResult
}
