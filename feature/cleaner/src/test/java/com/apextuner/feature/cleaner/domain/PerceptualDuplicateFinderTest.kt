package com.apextuner.feature.cleaner.domain

import com.apextuner.feature.cleaner.model.CleanableItem
import com.apextuner.feature.cleaner.model.CleanerCategory
import com.apextuner.feature.cleaner.model.CleanerOrigin
import com.apextuner.feature.cleaner.model.PerceptualImageMetrics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualDuplicateFinderTest {
    @Test
    fun hammingDistance_countsChangedBitsDeterministically() {
        assertEquals(0, PerceptualDuplicateFinder.hammingDistance(0L, 0L))
        assertEquals(1, PerceptualDuplicateFinder.hammingDistance(0L, 1L))
        assertEquals(4, PerceptualDuplicateFinder.hammingDistance(0L, 0x0fL))
        assertEquals(64, PerceptualDuplicateFinder.hammingDistance(0L, -1L))
    }

    @Test
    fun bucketsHashesWithinConfiguredThreshold() = runTest {
        val hashes = mapOf(
            "anchor" to 0x0000L,
            "near" to 0x000fL,
            "far" to 0x03ffL,
        )
        val finder = PerceptualDuplicateFinder(
            analyze = { item -> metrics(hashes.getValue(item.key)) },
            hammingDistanceThreshold = 4,
        )

        val result = finder.find(
            listOf(
                image("anchor"),
                image("near"),
                image("far"),
            ),
        )

        assertEquals(3, result.hashedItems)
        assertFalse(result.truncated)
        assertEquals(1, result.groups.size)
        assertEquals(listOf("anchor", "near"), result.groups.single().items.map { it.item.key })
        assertEquals(listOf(0, 4), result.groups.single().items.map { it.hammingDistanceFromAnchor })
    }

    @Test
    fun doesNotChainSimilarityBeyondAnchorThreshold() = runTest {
        val hashes = mapOf(
            "anchor" to 0x0000L,
            "near-anchor" to 0x000fL,
            "near-member-only" to 0x003fL,
        )
        val finder = PerceptualDuplicateFinder(
            analyze = { item -> metrics(hashes.getValue(item.key)) },
            hammingDistanceThreshold = 4,
        )

        val result = finder.find(
            listOf(
                image("anchor"),
                image("near-anchor"),
                image("near-member-only"),
            ),
        )

        assertEquals(1, result.groups.size)
        assertEquals(
            setOf("anchor", "near-anchor"),
            result.groups.single().items.map { it.item.key }.toSet(),
        )
    }

    @Test
    fun isolatesUnmappedProviderScopes() = runTest {
        val finder = PerceptualDuplicateFinder(
            analyze = { metrics(0L) },
            hammingDistanceThreshold = 5,
        )
        val first = image("first").copy(uri = "content://provider.one/first")
        val second = image("second").copy(uri = "content://provider.two/second")

        val result = finder.find(listOf(first, second))

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun comparesAndroidMappedMediaAcrossMediaStoreAndSaf() = runTest {
        val finder = PerceptualDuplicateFinder(
            analyze = { item -> metrics(if (item.key == "media") 0L else 1L) },
            hammingDistanceThreshold = 1,
        )
        val media = image("media").copy(
            origin = CleanerOrigin.MediaStore,
            physicalKey = "media-physical:external_primary:10",
        )
        val saf = image("saf").copy(
            origin = CleanerOrigin.SafDocument,
            physicalKey = "media-physical:external_primary:11",
        )

        val result = finder.find(listOf(media, saf))

        assertEquals(1, result.groups.size)
        assertEquals(setOf("media", "saf"), result.groups.single().items.map { it.item.key }.toSet())
    }

    @Test
    fun collapsesAliasesOfSamePhysicalItemBeforeAnalysis() = runTest {
        var analysisCalls = 0
        val finder = PerceptualDuplicateFinder(
            analyze = {
                analysisCalls++
                metrics(0L)
            },
        )
        val media = image("media").copy(
            origin = CleanerOrigin.MediaStore,
            physicalKey = "media-physical:external_primary:42",
        )
        val alias = image("alias").copy(
            origin = CleanerOrigin.SafDocument,
            physicalKey = "media-physical:external_primary:42",
        )

        val result = finder.find(listOf(media, alias))

        assertEquals(1, analysisCalls)
        assertEquals(1, result.hashedItems)
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun enforcesConfiguredHashCeilingWithoutAnalyzingLaterImages() = runTest {
        val analyzedKeys = mutableListOf<String>()
        val finder = PerceptualDuplicateFinder(
            analyze = {
                analyzedKeys += it.key
                metrics(0L)
            },
            maxHashedItems = 2,
        )

        val result = finder.find(
            listOf(
                image("one"),
                image("two"),
                image("three"),
            ),
        )

        assertEquals(listOf("one", "two"), analyzedKeys)
        assertEquals(2, result.hashedItems)
        assertTrue(result.truncated)
    }

    @Test
    fun ignoresNonImageItemsWithoutSpendingHashBudget() = runTest {
        val analyzedKeys = mutableListOf<String>()
        val finder = PerceptualDuplicateFinder(
            analyze = {
                analyzedKeys += it.key
                metrics(0L)
            },
            maxHashedItems = 1,
        )

        val result = finder.find(
            listOf(
                image("document").copy(category = CleanerCategory.Document),
                image("photo"),
            ),
        )

        assertEquals(listOf("photo"), analyzedKeys)
        assertEquals(1, result.hashedItems)
        assertFalse(result.truncated)
    }

    @Test
    fun unavailableSharpness_isNotFabricatedAsBlurry() = runTest {
        val finder = PerceptualDuplicateFinder(
            analyze = { item ->
                PerceptualImageMetrics(
                    dHash = if (item.key == "first") 0L else 1L,
                    laplacianVariance = null,
                )
            },
            hammingDistanceThreshold = 1,
        )

        val result = finder.find(listOf(image("first"), image("second")))

        assertEquals(1, result.groups.size)
        assertTrue(result.blurryPhotos.isEmpty())
    }

    @Test
    fun blurryPhotos_areManualReviewCandidatesBelowThresholdOnly() = runTest {
        val scores = mapOf(
            "very-blurry" to 12.0,
            "borderline" to 79.9,
            "at-threshold" to 80.0,
            "sharp" to 900.0,
        )
        val finder = PerceptualDuplicateFinder(
            analyze = { item -> metrics(dHash = item.key.hashCode().toLong(), sharpness = scores.getValue(item.key)) },
            blurryLaplacianVarianceThreshold = 80.0,
        )

        val result = finder.find(scores.keys.map(::image))

        assertEquals(
            listOf("very-blurry", "borderline"),
            result.blurryPhotos.map { it.item.key },
        )
        assertEquals(
            listOf(12.0, 79.9),
            result.blurryPhotos.map { it.laplacianVariance },
        )
    }

    private fun metrics(
        dHash: Long,
        sharpness: Double = 1_000.0,
    ) = PerceptualImageMetrics(
        dHash = dHash,
        laplacianVariance = sharpness,
    )

    private fun image(key: String) = CleanableItem(
        key = key,
        uri = "content://test.provider/$key",
        origin = CleanerOrigin.SafDocument,
        category = CleanerCategory.Image,
        displayName = "$key.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        modifiedAtEpochMillis = 1L,
        width = 1_920,
        height = 1_080,
        canDelete = true,
    )
}
