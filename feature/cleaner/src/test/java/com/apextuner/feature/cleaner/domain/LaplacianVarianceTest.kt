package com.apextuner.feature.cleaner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaplacianVarianceTest {
    @Test
    fun syntheticBlurredAndSharpEdges_fallOnExpectedSidesOfThreshold() {
        val width = 9
        val height = 9
        val blurry = verticalFixture(
            width = width,
            height = height,
            columns = intArrayOf(0, 10, 25, 50, 85, 125, 165, 200, 225),
        )
        val sharp = verticalFixture(
            width = width,
            height = height,
            columns = intArrayOf(0, 0, 0, 0, 255, 255, 255, 255, 255),
        )

        val blurryScore = LaplacianVariance.score(blurry, width, height)
        val sharpScore = LaplacianVariance.score(sharp, width, height)
        val threshold = PerceptualDuplicateFinder.DEFAULT_BLURRY_LAPLACIAN_VARIANCE_THRESHOLD

        assertTrue(blurryScore < threshold)
        assertTrue(sharpScore > threshold)
        assertTrue(sharpScore > blurryScore)
    }

    @Test
    fun uniformFixture_hasZeroSharpness() {
        val fixture = IntArray(9 * 9) { 128 }

        val score = LaplacianVariance.score(
            luminance = fixture,
            width = 9,
            height = 9,
        )

        assertEquals(0.0, score, 0.0)
    }

    @Test
    fun smoothLinearGradient_hasZeroInteriorLaplacianVariance() {
        val width = 9
        val height = 9
        val fixture = IntArray(width * height) { index ->
            (index % width) * 16
        }

        val score = LaplacianVariance.score(
            luminance = fixture,
            width = width,
            height = height,
        )

        assertEquals(0.0, score, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun undersizedFixture_isRejectedAsUnavailableForLaplacianScoring() {
        LaplacianVariance.score(
            luminance = intArrayOf(
                0, 255,
                255, 0,
            ),
            width = 2,
            height = 2,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedBuffer_isRejected() {
        LaplacianVariance.score(
            luminance = IntArray(8),
            width = 3,
            height = 3,
        )
    }

    private fun verticalFixture(
        width: Int,
        height: Int,
        columns: IntArray,
    ): IntArray {
        require(columns.size == width)
        return IntArray(width * height) { index ->
            columns[index % width]
        }
    }
}
