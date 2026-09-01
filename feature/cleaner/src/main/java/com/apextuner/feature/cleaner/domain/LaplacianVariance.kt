package com.apextuner.feature.cleaner.domain

object LaplacianVariance {
    fun score(
        luminance: IntArray,
        width: Int,
        height: Int,
    ): Double {
        require(width >= MIN_DIMENSION && height >= MIN_DIMENSION) {
            "Laplacian variance requires dimensions of at least ${MIN_DIMENSION}×$MIN_DIMENSION."
        }
        require(width.toLong() * height.toLong() == luminance.size.toLong()) {
            "Luminance buffer size must match width × height."
        }

        var count = 0
        var mean = 0.0
        var sumSquaredDelta = 0.0

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val laplacian = (
                    4 * luminance[index] -
                        luminance[index - 1] -
                        luminance[index + 1] -
                        luminance[index - width] -
                        luminance[index + width]
                    ).toDouble()

                count++
                val delta = laplacian - mean
                mean += delta / count
                sumSquaredDelta += delta * (laplacian - mean)
            }
        }

        return if (count == 0) 0.0 else sumSquaredDelta / count
    }

    private const val MIN_DIMENSION = 3
}
