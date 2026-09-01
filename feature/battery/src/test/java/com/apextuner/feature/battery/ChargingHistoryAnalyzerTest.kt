package com.apextuner.feature.battery

import com.apextuner.feature.battery.model.ChargingSessionInsight
import com.apextuner.feature.battery.model.EstimateConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingHistoryAnalyzerTest {
    @Test
    fun insufficientHistoryDoesNotClaimAnAnomaly() {
        val summary = ChargingHistoryAnalyzer.analyze(listOf(session(20, 60, 60)))

        assertEquals(1, summary.completedSessions)
        assertFalse(summary.latestSlowerThanBaseline)
        assertTrue(summary.insight.contains("personal baseline"))
    }

    @Test
    fun latestSlowSessionIsComparedAgainstPriorPersonalBaseline() {
        val summary = ChargingHistoryAnalyzer.analyze(
            listOf(
                session(20, 30, 60),
                session(20, 60, 60),
                session(30, 70, 60),
                session(40, 80, 60),
                session(50, 90, 60),
            ),
        )

        assertTrue(summary.latestSlowerThanBaseline)
        assertNotNull(summary.typicalChargeRatePercentPerHour)
        assertTrue(summary.insight.contains("personal baseline"))
    }

    @Test
    fun nonFiniteTemperatureTelemetryIsIgnored() {
        val summary = ChargingHistoryAnalyzer.analyze(
            listOf(
                session(20, 60, 60, Double.NaN),
                session(30, 70, 60, Double.POSITIVE_INFINITY),
                session(40, 80, 60, 36.0),
            ),
        )

        assertEquals(0, summary.hotSessionCount)
        assertEquals(0, summary.warmSessionCount)
        assertEquals(36.0, summary.averagePeakTemperatureCelsius!!, 0.0)
    }

    @Test
    fun hotSessionsTakePriorityOverRateSpeculation() {
        val summary = ChargingHistoryAnalyzer.analyze(
            listOf(
                session(20, 60, 60, 43.0),
                session(20, 60, 60, 35.0),
                session(30, 70, 60, 36.0),
            ),
        )

        assertEquals(1, summary.hotSessionCount)
        assertTrue(summary.insight.contains("42 °C"))
    }

    private fun session(start: Int, end: Int, minutes: Int, peakTemperature: Double = 35.0) = ChargingSessionInsight(
        startedAtEpochMillis = 0L,
        endedAtEpochMillis = minutes * 60_000L,
        durationMillis = minutes * 60_000L,
        startLevelPercent = start,
        endLevelPercent = end,
        estimatedAddedMah = null,
        averageTemperatureCelsius = null,
        peakTemperatureCelsius = peakTemperature,
        maximumCurrentMicroamps = null,
        cycleDelta = null,
        sampleCount = 10,
        confidence = EstimateConfidence.High,
    )
}
