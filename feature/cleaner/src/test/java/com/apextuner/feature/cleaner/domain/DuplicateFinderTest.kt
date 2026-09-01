package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {
    @Test
    fun findsOnlyExactDuplicatesAndKeepsNewerItem() = runTest {
        val data = mapOf(
            "old" to "same-content".encodeToByteArray(),
            "new" to "same-content".encodeToByteArray(),
            "different" to "other-value!".encodeToByteArray(),
        )
        val items = listOf(
            item("old", data.getValue("old").size.toLong(), 10L),
            item("new", data.getValue("new").size.toLong(), 20L),
            item("different", data.getValue("different").size.toLong(), 30L),
        )
        val finder = DuplicateFinder { item, mode ->
            data[item.key]?.let { bytes ->
                val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
                sha256(bytes.copyOf(count))
            }
        }

        val groups = finder.find(items)

        assertEquals(1, groups.size)
        assertEquals("new", groups.single().bestQualityKey)
        assertEquals("new", groups.single().newestKey)
        assertEquals(items.first().sizeBytes, groups.single().reclaimableBytes)
        assertTrue(groups.single().items.none { it.key == "different" })
    }

    @Test
    fun doesNotTreatDifferentProviderViewsAsDuplicateCopies() = runTest {
        val bytes = "same-content".encodeToByteArray()
        val saf = item("saf", bytes.size.toLong(), 10L)
        val media = item("media", bytes.size.toLong(), 20L).copy(origin = CleanerOrigin.MediaStore)
        val finder = DuplicateFinder { _, mode ->
            val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
            sha256(bytes.copyOf(count))
        }
        assertTrue(finder.find(listOf(saf, media)).isEmpty())
    }

    @Test
    fun comparesSafTreeAndSingleDocumentWithinSameProvider() = runTest {
        val bytes = "same-content".encodeToByteArray()
        val tree = item("tree", bytes.size.toLong(), 10L).copy(origin = CleanerOrigin.SafTree)
        val single = item("single", bytes.size.toLong(), 20L).copy(origin = CleanerOrigin.SafDocument)
        val finder = DuplicateFinder { _, mode ->
            val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
            sha256(bytes.copyOf(count))
        }
        val group = finder.find(listOf(tree, single)).single()
        assertEquals("single", group.newestKey)
    }

    @Test
    fun isolatesDifferentSafProviderAuthorities() = runTest {
        val bytes = "same-content".encodeToByteArray()
        val first = item("first", bytes.size.toLong(), 10L)
        val second = item("second", bytes.size.toLong(), 20L).copy(uri = "content://other.provider/second")
        val finder = DuplicateFinder { _, mode ->
            val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
            sha256(bytes.copyOf(count))
        }
        assertTrue(finder.find(listOf(first, second)).isEmpty())
    }


    @Test
    fun androidProvenAlias_isCollapsedBeforeDuplicateSuggestion() = runTest {
        val bytes = "same-content".encodeToByteArray()
        val media = item("media", bytes.size.toLong(), 10L).copy(
            origin = CleanerOrigin.MediaStore,
            physicalKey = "media-physical:external_primary:42",
        )
        val safAlias = item("saf", bytes.size.toLong(), 20L).copy(
            physicalKey = "media-physical:external_primary:42",
        )
        val finder = DuplicateFinder { _, mode ->
            val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
            sha256(bytes.copyOf(count))
        }
        assertTrue(finder.find(listOf(media, safAlias)).isEmpty())
    }

    @Test
    fun distinctMappedMedia_canBeComparedAcrossMediaStoreAndSaf() = runTest {
        val bytes = "same-content".encodeToByteArray()
        val media = item("media", bytes.size.toLong(), 10L).copy(
            origin = CleanerOrigin.MediaStore,
            physicalKey = "media-physical:external_primary:42",
        )
        val safCopy = item("saf", bytes.size.toLong(), 20L).copy(
            physicalKey = "media-physical:external_primary:43",
        )
        val finder = DuplicateFinder { _, mode ->
            val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
            sha256(bytes.copyOf(count))
        }
        assertEquals(1, finder.find(listOf(media, safCopy)).size)
    }

    @Test
    fun reclaimableBytes_excludesReadOnlyRedundantCopy() = runTest {
        val bytes = "same-content".encodeToByteArray()
        val keep = item("keep", bytes.size.toLong(), 20L)
        val readOnly = item("readonly", bytes.size.toLong(), 10L).copy(canDelete = false)
        val finder = DuplicateFinder { _, mode ->
            val count = mode.maxBytes?.let { minOf(it.toInt(), bytes.size) } ?: bytes.size
            sha256(bytes.copyOf(count))
        }
        assertEquals(0L, finder.find(listOf(keep, readOnly)).single().reclaimableBytes)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun item(key: String, size: Long, modified: Long) = CleanableItem(
        key = key,
        uri = "content://test/$key",
        origin = CleanerOrigin.SafDocument,
        category = CleanerCategory.Document,
        displayName = "$key.pdf",
        mimeType = "application/pdf",
        sizeBytes = size,
        modifiedAtEpochMillis = modified,
        canDelete = true,
    )
}
