package com.apextuner.feature.contacts

data class ContactRecord(
    val contactId: Long,
    val displayName: String,
    val phones: Set<String>,
    val emails: Set<String>,
    val rawContactIds: Set<Long>,
)

data class ContactDuplicateCandidate(
    val first: ContactRecord,
    val second: ContactRecord,
    val score: Double,
    val reason: String,
)

data class AggregationRuleSnapshot(
    val rawContactId1: Long,
    val rawContactId2: Long,
    val previousType: Int?,
)

data class ContactMergeUndo(
    val firstDisplayName: String,
    val secondDisplayName: String,
    val rules: List<AggregationRuleSnapshot>,
)

sealed interface ContactToolUiState {
    data object NeedsPermission : ContactToolUiState
    data object Loading : ContactToolUiState
    data class Ready(
        val candidates: List<ContactDuplicateCandidate>,
        val undoAvailable: Boolean,
        val undoBlockedByFailure: Boolean = false,
        val message: String? = null,
    ) : ContactToolUiState
    data class Error(val message: String) : ContactToolUiState
}
