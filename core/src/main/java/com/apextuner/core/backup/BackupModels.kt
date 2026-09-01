package com.apextuner.core.backup

import com.apextuner.core.model.AppPreferences

data class BackupPreview(
    val schemaVersion: Int,
    val createdAtEpochMillis: Long,
    val sourcePackage: String,
    val sourceVersion: String,
    val visibleAppCount: Int,
    val preferences: AppPreferences,
    val warnings: List<String>,
)

data class BackupWriteResult(
    val visibleAppCount: Int,
    val bytesWritten: Int,
)

data class ScheduledBackupWriteResult(
    val displayName: String,
    val bytesWritten: Int,
    val retainedBackupCount: Int,
)

data class BackupFileEntry(
    val documentId: String,
    val displayName: String,
    val lastModifiedEpochMillis: Long,
)

object BackupRetentionPolicy {
    private val generatedName = Regex("""ApexTuner-backup-v1-(\d{8})-(\d{6})\.json""")

    fun isManagedBackup(displayName: String): Boolean = generatedName.matches(displayName)

    fun entriesToDelete(entries: List<BackupFileEntry>, keepCount: Int): List<BackupFileEntry> {
        require(keepCount in 1..30)
        return entries
            .filter { isManagedBackup(it.displayName) }
            .sortedWith(compareByDescending<BackupFileEntry> { it.displayName }.thenByDescending { it.lastModifiedEpochMillis })
            .drop(keepCount)
    }
}

sealed interface BackupReadResult {
    data class Valid(val preview: BackupPreview) : BackupReadResult
    data class Invalid(val reason: String) : BackupReadResult
}
