package com.apextuner.core.model

enum class Capability {
    UsageAccess,
    DrawOverOtherApps,
    WriteSystemSettings,
    ScheduleExactAlarms,
    AllFilesAccess,
    RootAccess,
}

enum class CapabilityState {
    Granted,
    NotGranted,
    Available,
    Unsupported,
}

data class CapabilityStatus(
    val capability: Capability,
    val state: CapabilityState,
    val userActionRequired: Boolean,
    val detail: String,
)
