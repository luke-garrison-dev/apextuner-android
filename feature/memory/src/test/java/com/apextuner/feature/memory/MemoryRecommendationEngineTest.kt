package com.apextuner.feature.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecommendationEngineTest {
    @Test fun healthyMemoryNeverRecommendsArtificialRamClearing() {
        val recommendations = MemoryRecommendationEngine.evaluate(
            lowMemory = false,
            available = 4L * 1024 * 1024 * 1024,
            threshold = 512L * 1024 * 1024,
            pressureAvg10 = 0.2,
        )
        assertTrue(recommendations.any { it.contains("Cached RAM is useful") })
        assertTrue(recommendations.any { it.contains("does not force-stop unrelated apps") })
    }

    @Test fun elevatedPressureRecommendsReducingActualWorkload() {
        val recommendations = MemoryRecommendationEngine.evaluate(
            lowMemory = false,
            available = 2L * 1024 * 1024 * 1024,
            threshold = 512L * 1024 * 1024,
            pressureAvg10 = 12.0,
        )
        assertTrue(recommendations.any { it.contains("heavy foreground workload") })
    }
}
