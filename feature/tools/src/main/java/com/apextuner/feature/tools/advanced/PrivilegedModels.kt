package com.apextuner.feature.tools.advanced

import android.content.pm.PackageManager
import com.apextuner.core.tuning.CpuPolicySnapshot

enum class PrivilegedBackend { Shizuku, Root }

enum class PrivilegedReadCommand(val id: Int, val title: String) {
    Identity(1, "Privilege identity"),
    DeviceIdleState(2, "Device idle state"),
    AnimationScales(3, "Animation scales"),
    KernelVersion(4, "Kernel version"),
}

data class PrivilegedBackendStatus(
    val shizukuBinderAlive: Boolean,
    val shizukuApiVersion: Int?,
    val shizukuPermissionGranted: Boolean,
    val shizukuPermissionRationaleRequired: Boolean,
    val shizukuUid: Int?,
    val shizukuIsRoot: Boolean,
    val rootPotentiallyAvailable: Boolean,
    val lastRootAuthorization: RootAuthorizationState = RootAuthorizationState.NotChecked,
)

enum class RootAuthorizationState { NotChecked, Granted, DeniedOrUnavailable, TimedOut }

data class PrivilegedCommandResult(
    val backend: PrivilegedBackend,
    val success: Boolean,
    val output: String,
)

data class AnimationScales(
    val window: Float,
    val transition: Float,
    val animator: Float,
) {
    fun isSafe(): Boolean = listOf(window, transition, animator).all { it.isFinite() && it in 0f..10f }
}

data class PrivilegedPackageTarget(
    val packageName: String,
    val label: String,
    val enabledSetting: Int,
)

sealed interface CacheMaintenanceCapability {
    data object RollbackSafeUnavailable : CacheMaintenanceCapability
}

data class CpuTuningStatus(
    val availablePolicies: Int = 0,
    val baselinePolicies: Int = 0,
    val thermalPolicy: String = "Platform managed",
)

sealed interface AdvancedToolsUiState {
    data object Loading : AdvancedToolsUiState
    data class Error(val message: String) : AdvancedToolsUiState
    data class Ready(
        val status: PrivilegedBackendStatus,
        val selectedBackend: PrivilegedBackend = PrivilegedBackend.Shizuku,
        val busy: Boolean = false,
        val output: String? = null,
        val message: String? = null,
        val savedAnimationBaseline: AnimationScales? = null,
        val packageTargets: List<PrivilegedPackageTarget> = emptyList(),
        val cpuTuningStatus: CpuTuningStatus = CpuTuningStatus(),
    ) : AdvancedToolsUiState
}

fun isProtectedPackageTarget(
    packageName: String,
    ownPackageName: String,
    applicationFlags: Int,
    uid: Int,
): Boolean {
    if (!PACKAGE_NAME_REGEX.matches(packageName)) return true
    if (packageName == ownPackageName || packageName == "android" || packageName.startsWith("com.android.")) return true
    if (uid < FIRST_APPLICATION_UID) return true
    val systemMask = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    return applicationFlags and systemMask != 0
}

fun isRestorableEnabledState(state: Int): Boolean = state in setOf(
    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
)

fun parseAnimationScales(output: String): AnimationScales? {
    val values = output.lineSequence().mapNotNull { line ->
        val split = line.split('=', limit = 2)
        if (split.size != 2) null else split[0].trim() to split[1].trim().toFloatOrNull()
    }.toMap()
    val scales = AnimationScales(
        window = values["window"] ?: return null,
        transition = values["transition"] ?: return null,
        animator = values["animator"] ?: return null,
    )
    return scales.takeIf(AnimationScales::isSafe)
}

fun parseCpuPolicySnapshot(policyId: Int, output: String): CpuPolicySnapshot? {
    if (policyId !in 0..63) return null
    val values = output.lineSequence()
        .mapNotNull { line ->
            val split = line.split('=', limit = 2)
            if (split.size == 2) split[0].trim() to split[1].trim() else null
        }
        .toMap()
    val available = values["availableGovernors"]
        ?.split(Regex("\\s+"))
        ?.filter { GOVERNOR_REGEX.matches(it) }
        ?.toSet()
        .orEmpty()
    val snapshot = CpuPolicySnapshot(
        policyId = policyId,
        governor = values["governor"]?.takeIf(GOVERNOR_REGEX::matches) ?: return null,
        availableGovernors = available,
        minimumFrequencyKHz = values["minimumKHz"]?.toLongOrNull() ?: return null,
        maximumFrequencyKHz = values["maximumKHz"]?.toLongOrNull() ?: return null,
        hardwareMinimumFrequencyKHz = values["hardwareMinimumKHz"]?.toLongOrNull() ?: return null,
        hardwareMaximumFrequencyKHz = values["hardwareMaximumKHz"]?.toLongOrNull() ?: return null,
    )
    return snapshot.takeIf(CpuPolicySnapshot::isValid)
}

fun boundedOutput(value: String, maximumCharacters: Int = 32_768): String {
    // Treat the requested limit as untrusted defensive input as well. Privileged backends use
    // fixed audited limits today, but this helper must never become an exception source if a
    // future caller supplies a negative or excessively large value.
    val limit = maximumCharacters.coerceIn(0, MAX_BOUNDED_OUTPUT_CHARACTERS)
    val normalized = value.replace('\u0000', ' ').trim()
    return if (normalized.length <= limit) normalized else normalized.take(limit) + "\n…output truncated…"
}

private val PACKAGE_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
private val GOVERNOR_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
private const val FIRST_APPLICATION_UID = 10_000
private const val MAX_BOUNDED_OUTPUT_CHARACTERS = 128 * 1024
