package com.apextuner.feature.tools.security

data class SecuritySnapshot(
    val secureScreenLock: Boolean,
    val deviceLockedNow: Boolean,
    val rootBinaryPotentiallyPresent: Boolean,
    val appCanInstallUnknownPackages: Boolean,
    val securityPatchLevel: String?,
    val securityPatchAgeDays: Long?,
    val advancedProtectionSupported: Boolean,
    val advancedProtectionEnabled: Boolean?,
    val adbDeveloperStateInspectable: Boolean = false,
    val diagnostics: List<String>,
)

sealed interface SecurityUiState {
    data object Loading : SecurityUiState
    data class Ready(val snapshot: SecuritySnapshot, val message: String? = null) : SecurityUiState
    data class Error(val message: String) : SecurityUiState
}
