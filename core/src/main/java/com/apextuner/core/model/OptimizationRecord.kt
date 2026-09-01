package com.apextuner.core.model

enum class OptimizationOutcome {
    Succeeded,
    PartiallySucceeded,
    Failed,
    Cancelled,
}

data class OptimizationRecord(
    val id: Long,
    val actionType: String,
    val scope: String,
    val createdAtEpochMillis: Long,
    val outcome: OptimizationOutcome,
    val bytesChanged: Long,
    val reversibleUntilEpochMillis: Long?,
)
