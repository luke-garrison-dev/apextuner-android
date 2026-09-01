package com.apextuner.core.model

enum class ScanStatus {
    Running,
    Completed,
    Failed,
    Cancelled,
}

data class ScanSession(
    val id: String,
    val scanType: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val itemsScanned: Long,
    val bytesEligible: Long,
    val status: ScanStatus,
)
