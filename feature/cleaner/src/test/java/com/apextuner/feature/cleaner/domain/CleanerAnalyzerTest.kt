package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanerAnalyzerTest {
    @Test
    fun classify_isConservativeForTemporaryAndDocuments() {
        val temporary = CleanerAnalyzer.classify("clip.crdownload", "application/octet-stream")
        assertEquals(CleanerCategory.Temporary, temporary.first)
        assertTrue(temporary.second)

        val document = CleanerAnalyzer.classify("report.pdf", "application/pdf")
        assertEquals(CleanerCategory.Document, document.first)
        assertFalse(document.second)
    }

    @Test
    fun junkLocation_onlyMatchesWholeDirectorySegments() {
        assertTrue(CleanerAnalyzer.isJunkLocation("Pictures/.thumbnails"))
        assertTrue(CleanerAnalyzer.isJunkLocation("App/cache/images"))
        assertFalse(CleanerAnalyzer.isJunkLocation("Documents/cache-notes"))
    }

    @Test
    fun potentialReclaimBytes_saturatesInsteadOfOverflowing() {
        val item = item(size = Long.MAX_VALUE, junk = true)
        assertEquals(Long.MAX_VALUE, CleanerAnalyzer.potentialReclaimBytes(emptyList(), listOf(item)))
    }

    @Test
    fun potentialReclaimBytes_excludesReadOnlyCandidates() {
        val readOnly = item(size = 1_024L, junk = true).copy(canDelete = false)
        assertEquals(0L, CleanerAnalyzer.potentialReclaimBytes(emptyList(), listOf(readOnly)))
    }


    @Test
    fun analysisTotals_doNotDoubleCountAndroidProvenAliases() {
        val media = item(size = 2_048L, junk = true).copy(
            key = "media",
            physicalKey = "media-physical:external_primary:42",
            origin = CleanerOrigin.MediaStore,
        )
        val safAlias = item(size = 2_048L, junk = true).copy(
            key = "saf",
            physicalKey = "media-physical:external_primary:42",
        )
        assertEquals(2_048L, CleanerAnalyzer.totalAccessibleBytes(listOf(media, safAlias)))
        assertEquals(2_048L, CleanerAnalyzer.potentialReclaimBytes(emptyList(), listOf(media, safAlias)))
    }

    private fun item(size: Long, junk: Boolean) = CleanableItem(
        key = "1",
        uri = "content://test/1",
        origin = CleanerOrigin.SafDocument,
        category = CleanerCategory.Temporary,
        displayName = "x.tmp",
        mimeType = null,
        sizeBytes = size,
        modifiedAtEpochMillis = null,
        canDelete = true,
        suspectedJunk = junk,
    )
}
