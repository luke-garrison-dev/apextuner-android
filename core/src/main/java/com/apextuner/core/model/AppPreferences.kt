package com.apextuner.core.model

enum class ThemeMode { Dark, Light, System }
enum class MaintenanceCadence { Daily, Weekly }

data class PrivilegedCpuPolicyBackup(
    val policyId: Int,
    val governor: String,
    val minimumFrequencyKHz: Long,
    val maximumFrequencyKHz: Long,
)

data class SystemProfileBackup(
    val originalScreenOffTimeoutMillis: Long,
    val originalHapticFeedbackEnabled: Boolean,
    val activeProfile: SystemProfile,
    /** 1.0.10 migration-only baseline; new profiles never populate this field. */
    val legacyOriginalMasterSyncEnabled: Boolean? = null,
    val mutationPending: Boolean = false,
    val privilegedCpuPolicies: List<PrivilegedCpuPolicyBackup> = emptyList(),
    val privilegedMutationPending: Boolean = false,
)

enum class SystemProfile { Balanced, Battery, Performance, Gaming }

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val showAdvancedTools: Boolean = false,
    val telemetryRefreshMillis: Long = 2_000L,
    val scheduledMaintenanceEnabled: Boolean = false,
    val maintenanceCadence: MaintenanceCadence = MaintenanceCadence.Weekly,
    val nightBatteryProfileEnabled: Boolean = false,
    val nightBatteryProfileAppliedByAutomation: Boolean = false,
    val scheduledBackupEnabled: Boolean = false,
    val scheduledBackupCadence: MaintenanceCadence = MaintenanceCadence.Weekly,
    val scheduledBackupRetentionCount: Int = 5,
    val scheduledBackupTreeUri: String? = null,
    val systemProfileBackup: SystemProfileBackup? = null,
)
