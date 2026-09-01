package com.apextuner.feature.dashboard.intelligence

import com.apextuner.core.database.AutomationEventEntity
import com.apextuner.core.database.BatteryHealthSnapshotEntity
import com.apextuner.core.database.ChargingSessionEntity
import com.apextuner.core.database.DeviceHealthSampleEntity
import com.apextuner.core.database.GameSessionRecordEntity
import com.apextuner.core.database.NetworkQualityRunEntity
import com.apextuner.core.database.OptimizationHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexIntelligenceEngineTest {
    @Test
    fun emptyHistoryNeverFabricatesAHealthScore() {
        val snapshot = analyze()

        assertNull(snapshot.overallScore)
        assertTrue(snapshot.scores.isEmpty())
        assertEquals(0, snapshot.sampleCount)
        assertTrue(snapshot.findings.single().tone == IntelligenceTone.Healthy)
    }

    @Test
    fun invalidStorageTelemetryIsOmittedInsteadOfReportedAsCritical() {
        val sample = health(
            capturedAt = NOW,
            storageAvailable = 0L,
            storageTotal = 0L,
        )

        val snapshot = analyze(health = listOf(sample))

        assertTrue(snapshot.scores.none { it.name == "Storage" })
    }

    @Test
    fun componentScoresStayBoundedAndDegradedEvidenceProducesAttention() {
        val sample = health(
            capturedAt = NOW,
            storageAvailable = 3L,
            storageTotal = 100L,
            memoryUsed = 96.0,
            cpuUsed = 96.0,
            batteryTemperature = 46.0,
            thermalStatus = "Severe",
            networkValidated = false,
        )
        val network = NetworkQualityRunEntity(
            capturedAtEpochMillis = NOW,
            host = "example.test",
            port = 443,
            attempts = 10,
            successes = 4,
            minLatencyMillis = 100L,
            medianLatencyMillis = 260L,
            averageLatencyMillis = 260.0,
            p95LatencyMillis = 400L,
            jitterMillis = 90.0,
            dnsLatencyMillis = 40L,
            ipv4Count = 1,
            ipv6Count = 1,
            networkMetered = false,
        )

        val snapshot = analyze(health = listOf(sample), networkRuns = listOf(network))

        assertTrue(snapshot.scores.isNotEmpty())
        assertTrue(snapshot.scores.all { it.score in 0..100 })
        assertTrue(snapshot.scores.any { it.tone == IntelligenceTone.Attention })
        assertTrue(snapshot.findings.any { it.tone == IntelligenceTone.Attention })
    }

    @Test
    fun nonFiniteTelemetryIsIgnoredInsteadOfPoisoningScoresOrFindings() {
        val sample = health(
            capturedAt = NOW,
            memoryUsed = Double.NaN,
            cpuUsed = Double.POSITIVE_INFINITY,
            batteryTemperature = Double.NaN,
        )
        val invalidNetwork = NetworkQualityRunEntity(
            capturedAtEpochMillis = NOW,
            host = "example.test",
            port = 443,
            attempts = 0,
            successes = 99,
            minLatencyMillis = null,
            medianLatencyMillis = null,
            averageLatencyMillis = Double.NaN,
            p95LatencyMillis = null,
            jitterMillis = Double.POSITIVE_INFINITY,
            dnsLatencyMillis = null,
            ipv4Count = 0,
            ipv6Count = 0,
            networkMetered = false,
        )

        val snapshot = analyze(health = listOf(sample), networkRuns = listOf(invalidNetwork))

        assertTrue(snapshot.scores.none { it.name == "Performance" })
        assertTrue(snapshot.scores.none { it.name == "Network" })
        assertTrue(snapshot.scores.none { it.summary.contains("NaN") || it.summary.contains("Infinity") })
        assertTrue(snapshot.findings.none { it.detail.contains("NaN") || it.detail.contains("Infinity") })
    }

    @Test
    fun activityLedgerOnlyReportsObservedNearbyTelemetryChanges() {
        val before = health(
            capturedAt = NOW - 20L * 60L * 1_000L,
            storageAvailable = 10L * MIB,
            storageTotal = 100L * MIB,
            memoryUsed = 91.0,
            batteryTemperature = 42.0,
        )
        val after = health(
            capturedAt = NOW + 20L * 60L * 1_000L,
            storageAvailable = 60L * MIB,
            storageTotal = 100L * MIB,
            memoryUsed = 82.0,
            batteryTemperature = 39.0,
        )
        val optimization = OptimizationHistoryEntity(
            actionType = "cleaner_delete",
            scope = "duplicates",
            createdAtEpochMillis = NOW,
            outcome = "APPLIED",
            bytesChanged = 50L * MIB,
            reversibleUntilEpochMillis = null,
        )

        val snapshot = analyze(
            health = listOf(before, after),
            optimizations = listOf(optimization),
        )

        val observation = snapshot.activities.single().observation.orEmpty()
        assertTrue(observation.startsWith("Observed around this action:"))
        assertTrue(observation.contains("storage +"))
        assertTrue(observation.contains("memory -"))
        assertTrue(observation.contains("battery temp -"))
    }

    private fun analyze(
        health: List<DeviceHealthSampleEntity> = emptyList(),
        batteryHealth: List<BatteryHealthSnapshotEntity> = emptyList(),
        chargingSessions: List<ChargingSessionEntity> = emptyList(),
        networkRuns: List<NetworkQualityRunEntity> = emptyList(),
        optimizations: List<OptimizationHistoryEntity> = emptyList(),
        automationEvents: List<AutomationEventEntity> = emptyList(),
        gameSessions: List<GameSessionRecordEntity> = emptyList(),
    ) = ApexIntelligenceEngine.analyze(
        ApexIntelligenceEngine.Input(
            nowEpochMillis = NOW,
            health = health,
            batteryHealth = batteryHealth,
            chargingSessions = chargingSessions,
            networkRuns = networkRuns,
            optimizations = optimizations,
            automationEvents = automationEvents,
            gameSessions = gameSessions,
        ),
    )

    private fun health(
        capturedAt: Long,
        storageAvailable: Long = 50L,
        storageTotal: Long = 100L,
        memoryUsed: Double = 50.0,
        cpuUsed: Double? = 30.0,
        batteryTemperature: Double? = 32.0,
        thermalStatus: String = "None",
        networkValidated: Boolean = true,
    ) = DeviceHealthSampleEntity(
        capturedAtEpochMillis = capturedAt,
        cpuUsagePercent = cpuUsed,
        memoryUsedPercent = memoryUsed,
        internalStorageAvailableBytes = storageAvailable,
        internalStorageTotalBytes = storageTotal,
        batteryLevelPercent = 70,
        batteryTemperatureCelsius = batteryTemperature,
        batteryCurrentMicroamps = null,
        batteryCharging = false,
        thermalStatus = thermalStatus,
        networkMetered = false,
        networkValidated = networkValidated,
        totalRxBytes = null,
        totalTxBytes = null,
    )

    private companion object {
        const val NOW = 10L * 24L * 60L * 60L * 1_000L
        const val MIB = 1024L * 1024L
    }
}
