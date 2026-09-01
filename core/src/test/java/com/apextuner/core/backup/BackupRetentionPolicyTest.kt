package com.apextuner.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionPolicyTest {
    @Test fun onlyStrictApexTunerVersionedNamesAreManaged() {
        assertTrue(BackupRetentionPolicy.isManagedBackup("ApexTuner-backup-v1-20260829-120000.json"))
        assertFalse(BackupRetentionPolicy.isManagedBackup("../ApexTuner-backup-v1-20260829-120000.json"))
        assertFalse(BackupRetentionPolicy.isManagedBackup("ApexTuner-backup-v2-20260829-120000.json"))
        assertFalse(BackupRetentionPolicy.isManagedBackup("notes.json"))
    }

    @Test fun retentionKeepsNewestNamesAndNeverTouchesUnrelatedFiles() {
        val entries = listOf(
            BackupFileEntry("a", "ApexTuner-backup-v1-20260827-120000.json", 1),
            BackupFileEntry("b", "ApexTuner-backup-v1-20260829-120000.json", 2),
            BackupFileEntry("c", "ApexTuner-backup-v1-20260828-120000.json", 3),
            BackupFileEntry("d", "family.json", 4),
        )
        assertEquals(
            listOf("a"),
            BackupRetentionPolicy.entriesToDelete(entries, keepCount = 2).map { it.documentId },
        )
    }
}
