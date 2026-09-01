package com.apextuner.feature.files

data class SafNode(
    val uri: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long?,
    val isDirectory: Boolean,
    val canWrite: Boolean,
)

data class SafLocation(
    val uri: String,
    val displayName: String,
)

sealed interface FileManagerUiState {
    data object NoAccess : FileManagerUiState
    data class Loading(val location: SafLocation?) : FileManagerUiState
    data class Ready(
        val location: SafLocation,
        val entries: List<SafNode>,
        val selected: SafNode? = null,
        val transferSource: SafNode? = null,
        val transferMove: Boolean = false,
        val busyMessage: String? = null,
        val message: String? = null,
    ) : FileManagerUiState
    data class Error(val message: String) : FileManagerUiState
}
