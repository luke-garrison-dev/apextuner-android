package com.apextuner.feature.dashboard.intelligence

import com.apextuner.core.database.AutomationEventEntity
import com.apextuner.core.database.BatteryHealthSnapshotEntity
import com.apextuner.core.database.ChargingSessionEntity
import com.apextuner.core.database.DeviceHealthSampleEntity
import com.apextuner.core.database.GameSessionRecordEntity
import com.apextuner.core.database.NetworkQualityRunEntity
import com.apextuner.core.database.OptimizationHistoryEntity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal object ApexIntelligenceEngine {
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    private const val IMPACT_WINDOW_MILLIS = 75L * 60L * 1000L

    data class Input(
        val nowEpochMillis: Long,
        val health: List<DeviceHealthSampleEntity>,
        val batteryHealth: List<BatteryHealthSnapshotEntity>,
        val chargingSessions: List<ChargingSessionEntity>,
        val networkRuns: List<NetworkQualityRunEntity>,
        val optimizations: List<OptimizationHistoryEntity>,
        val automationEvents: List<AutomationEventEntity>,
        val gameSessions: List<GameSessionRecordEntity>,
    )

    fun analyze(input: Input): IntelligenceSnapshot {
        val recentHealth = input.health.sortedBy { it.capturedAtEpochMillis }
        val latest = recentHealth.lastOrNull()
        val last24 = recentHealth.filter { it.capturedAtEpochMillis >= input.nowEpochMillis - DAY_MILLIS }
        val previous24 = recentHealth.filter {
            it.capturedAtEpochMillis >= input.nowEpochMillis - 2 * DAY_MILLIS &&
                it.capturedAtEpochMillis < input.nowEpochMillis - DAY_MILLIS
        }

        val scores = buildList {
            latest?.let { storageScore(it)?.let(::add) }
            thermalScore(last24.ifEmpty { recentHealth.takeLast(96) })?.let(::add)
            batteryScore(last24.ifEmpty { recentHealth.takeLast(96) }, input.batteryHealth)?.let(::add)
            networkScore(input.networkRuns)?.let(::add)
            performanceScore(last24.ifEmpty { recentHealth.takeLast(96) })?.let(::add)
        }
        val overall = scores.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.roundToInt()
        val findings = buildFindings(input, latest, last24, previous24, scores)
        val activities = buildActivities(input, recentHealth)

        return IntelligenceSnapshot(
            generatedAtEpochMillis = input.nowEpochMillis,
            overallScore = overall,
            overallSummary = when {
                overall == null -> "Collecting enough history for a reliable baseline"
                overall >= 90 -> "Device signals are stable across the available evidence"
                overall >= 75 -> "Mostly healthy, with a few areas worth watching"
                overall >= 60 -> "Some device signals deserve attention"
                else -> "Several measured signals deserve review"
            },
            scores = scores,
            findings = findings.take(8),
            activities = activities.take(20),
            sampleCount = recentHealth.size,
            observationWindowDays = 7,
        )
    }

    private fun storageScore(latest: DeviceHealthSampleEntity): IntelligenceScore? {
        if (latest.internalStorageTotalBytes <= 0L) return null
        val freePercent = (latest.internalStorageAvailableBytes.coerceAtLeast(0L) * 100.0 / latest.internalStorageTotalBytes)
            .coerceIn(0.0, 100.0)
        val score = when {
            freePercent >= 30 -> 100
            freePercent >= 20 -> 92
            freePercent >= 15 -> 82
            freePercent >= 10 -> 68
            freePercent >= 5 -> 45
            else -> 25
        }
        return IntelligenceScore(
            name = "Storage",
            score = score,
            summary = "${format1(freePercent)}% free",
            explanation = "Based on currently available internal storage; no files are removed automatically.",
            tone = toneFor(score),
        )
    }

    private fun thermalScore(samples: List<DeviceHealthSampleEntity>): IntelligenceScore? {
        if (samples.isEmpty()) return null
        val severeCount = samples.count { it.thermalStatus in setOf("Severe", "Critical", "Emergency", "Shutdown") }
        val severePercent = severeCount * 100.0 / samples.size
        val maxBatteryTemp = samples.mapNotNull { it.batteryTemperatureCelsius?.takeIf(Double::isFinite) }.maxOrNull()
        var score = 100
        score -= when {
            severePercent >= 20 -> 45
            severePercent >= 5 -> 25
            severeCount > 0 -> 12
            else -> 0
        }
        score -= when {
            maxBatteryTemp == null -> 0
            maxBatteryTemp >= 45 -> 25
            maxBatteryTemp >= 42 -> 15
            maxBatteryTemp >= 40 -> 8
            else -> 0
        }
        score = score.coerceIn(0, 100)
        return IntelligenceScore(
            name = "Thermal",
            score = score,
            summary = if (severeCount == 0) "No severe thermal samples" else "$severeCount severe thermal sample${if (severeCount == 1) "" else "s"}",
            explanation = buildString {
                append("Uses the observed Android thermal status")
                maxBatteryTemp?.let { append(" and a peak battery temperature of ${format1(it)} °C") }
                append(" within the recent sample window.")
            },
            tone = toneFor(score),
        )
    }

    private fun batteryScore(
        samples: List<DeviceHealthSampleEntity>,
        snapshots: List<BatteryHealthSnapshotEntity>,
    ): IntelligenceScore? {
        val temperatures = samples.mapNotNull { it.batteryTemperatureCelsius?.takeIf(Double::isFinite) }
        val capacitySnapshots = snapshots.filter { (it.estimatedFullChargeCapacityMicroampHours ?: 0L) > 0L }
        val latestCapacity = capacitySnapshots.maxByOrNull { it.epochDay }?.estimatedFullChargeCapacityMicroampHours
        val oldestCapacity = capacitySnapshots.minByOrNull { it.epochDay }?.estimatedFullChargeCapacityMicroampHours
        if (temperatures.isEmpty() && latestCapacity == null) return null
        val avgTemp = temperatures.takeIf { it.isNotEmpty() }?.average()
        val maxTemp = temperatures.maxOrNull()
        var score = when {
            maxTemp == null -> 95
            maxTemp >= 45 -> 50
            maxTemp >= 42 -> 70
            maxTemp >= 40 -> 83
            avgTemp != null && avgTemp >= 38 -> 88
            else -> 100
        }
        val capacityChangePercent = if (latestCapacity != null && oldestCapacity != null && oldestCapacity > 0L && capacitySnapshots.size >= 7) {
            (latestCapacity - oldestCapacity) * 100.0 / oldestCapacity
        } else null
        if (capacityChangePercent != null && capacityChangePercent <= -5.0) score -= 10
        score = score.coerceIn(0, 100)
        val summary = when {
            avgTemp != null -> "Avg ${format1(avgTemp)} °C · Peak ${format1(maxTemp ?: avgTemp)} °C"
            latestCapacity != null -> "Capacity estimate history available"
            else -> "Battery evidence available"
        }
        return IntelligenceScore(
            name = "Battery",
            score = score,
            summary = summary,
            explanation = buildString {
                append("Uses observed battery temperature")
                if (capacityChangePercent != null) append(" plus an estimated-capacity trend of ${signed1(capacityChangePercent)}%")
                append(". Capacity estimates are telemetry-derived, not a manufacturer health certificate.")
            },
            tone = toneFor(score),
        )
    }

    private fun networkScore(runs: List<NetworkQualityRunEntity>): IntelligenceScore? {
        val sample = runs.asSequence().filter { it.attempts > 0 }.take(10).toList()
        if (sample.isEmpty()) return null
        val attempts = sample.sumOf { it.attempts.toLong() }.coerceAtLeast(1L)
        val successes = sample.sumOf { it.successes.coerceIn(0, it.attempts).toLong() }
        val successRatio = (successes.toDouble() / attempts.toDouble()).coerceIn(0.0, 1.0)
        val medianValues = sample.mapNotNull { it.medianLatencyMillis }.map { it.toDouble() }
        val jitterValues = sample.mapNotNull { it.jitterMillis?.takeIf(Double::isFinite) }
        val median = medianValues.takeIf { it.isNotEmpty() }?.average()
        val jitter = jitterValues.takeIf { it.isNotEmpty() }?.average()
        var score = (successRatio * 100.0).roundToInt()
        score -= when {
            median == null -> 8
            median >= 250 -> 30
            median >= 120 -> 18
            median >= 70 -> 8
            else -> 0
        }
        score -= when {
            jitter == null -> 0
            jitter >= 80 -> 18
            jitter >= 35 -> 10
            jitter >= 15 -> 4
            else -> 0
        }
        score = score.coerceIn(0, 100)
        return IntelligenceScore(
            name = "Network",
            score = score,
            summary = "${(successRatio * 100).roundToInt()}% probes successful${median?.let { " · ${it.roundToInt()} ms median" } ?: ""}",
            explanation = "Based on recent user-run Network Quality Lab probes, including reachability, latency and jitter.",
            tone = toneFor(score),
        )
    }

    private fun performanceScore(samples: List<DeviceHealthSampleEntity>): IntelligenceScore? {
        if (samples.isEmpty()) return null
        val memoryValues = samples.mapNotNull { it.memoryUsedPercent.takeIf(Double::isFinite) }
            .map { it.coerceIn(0.0, 100.0) }
        val cpus = samples.mapNotNull { it.cpuUsagePercent?.takeIf(Double::isFinite) }
            .map { it.coerceIn(0.0, 100.0) }
        if (memoryValues.isEmpty() && cpus.isEmpty()) return null
        val memory = memoryValues.takeIf { it.isNotEmpty() }?.average()
        val cpu = cpus.takeIf { it.isNotEmpty() }?.average()
        val memoryScore = when {
            memory == null -> null
            memory <= 70 -> 100
            memory <= 80 -> 88
            memory <= 90 -> 68
            else -> 45
        }
        val cpuScore = when {
            cpu == null -> null
            cpu <= 60 -> 100
            cpu <= 75 -> 88
            cpu <= 90 -> 68
            else -> 48
        }
        val score = listOfNotNull(memoryScore, cpuScore).average().roundToInt()
        return IntelligenceScore(
            name = "Performance",
            score = score,
            summary = buildString {
                memory?.let { append("Memory ${format1(it)}%") }
                if (memory != null && cpu != null) append(" · ")
                cpu?.let { append("CPU ${format1(it)}%") }
            },
            explanation = "This is a pressure/stability indicator from sampled memory and CPU usage, not a synthetic benchmark score.",
            tone = toneFor(score),
        )
    }

    private fun buildFindings(
        input: Input,
        latest: DeviceHealthSampleEntity?,
        last24: List<DeviceHealthSampleEntity>,
        previous24: List<DeviceHealthSampleEntity>,
        scores: List<IntelligenceScore>,
    ): List<IntelligenceFinding> = buildList {
        scores.filter { it.tone == IntelligenceTone.Attention }.sortedBy { it.score }.forEach { score ->
            val destination = when (score.name) {
                "Storage" -> IntelligenceDestination.Optimize
                "Battery", "Thermal" -> IntelligenceDestination.Battery
                "Network" -> IntelligenceDestination.Network
                "Performance" -> IntelligenceDestination.Memory
                else -> null
            }
            add(IntelligenceFinding("${score.name} deserves review", score.explanation, IntelligenceTone.Attention, destination))
        }

        compareAverage(
            "Memory pressure",
            last24.mapNotNull { it.memoryUsedPercent.takeIf(Double::isFinite) },
            previous24.mapNotNull { it.memoryUsedPercent.takeIf(Double::isFinite) },
            higherIsWorse = true,
        )?.let {
            add(IntelligenceFinding(it.first, it.second, it.third, IntelligenceDestination.Memory))
        }
        compareAverage(
            "Battery temperature",
            last24.mapNotNull { it.batteryTemperatureCelsius?.takeIf(Double::isFinite) },
            previous24.mapNotNull { it.batteryTemperatureCelsius?.takeIf(Double::isFinite) },
            higherIsWorse = true,
            unit = " °C",
        )?.let { add(IntelligenceFinding(it.first, it.second, it.third, IntelligenceDestination.Battery)) }

        latest?.let {
            if (!it.networkValidated) add(IntelligenceFinding("Network validation is unavailable", "Android does not currently report the active network as internet-validated.", IntelligenceTone.Attention, IntelligenceDestination.Network))
        }

        val completedCharging = input.chargingSessions.filter { it.endedAtEpochMillis != null }.take(10)
        val warmSessions = completedCharging.count { it.maxTemperatureCelsius?.takeIf(Double::isFinite)?.let { temp -> temp >= 42.0 } == true }
        if (warmSessions > 0) {
            add(IntelligenceFinding("Warm charging observed", "$warmSessions of the last ${completedCharging.size} recorded charging sessions reached at least 42 °C.", IntelligenceTone.Attention, IntelligenceDestination.Battery))
        }

        input.gameSessions.firstOrNull()?.let { game ->
            val startBatteryLevel = game.startBatteryLevelPercent
            val endBatteryLevel = game.endBatteryLevelPercent
            val drained = if (startBatteryLevel != null && endBatteryLevel != null) startBatteryLevel - endBatteryLevel else null
            val peakTemperature = game.peakBatteryTemperatureCelsius?.takeIf(Double::isFinite)
            if (peakTemperature != null && peakTemperature >= 42.0) {
                add(IntelligenceFinding("Recent game session ran warm", "${game.appLabel} peaked at ${format1(peakTemperature)} °C${drained?.let { " with ${it.coerceAtLeast(0)}% battery change" } ?: ""}.", IntelligenceTone.Informational, IntelligenceDestination.GameBooster))
            }
        }

        if (isEmpty()) {
            add(IntelligenceFinding("No significant anomalies detected", "Available recent evidence does not show a strong deterioration signal. Keep collecting history for a stronger personal baseline.", IntelligenceTone.Healthy))
        }
    }

    private fun buildActivities(input: Input, health: List<DeviceHealthSampleEntity>): List<ActivityImpact> {
        val optimizationItems = input.optimizations.map { record ->
            val observation = correlateAround(record.createdAtEpochMillis, health)
            ActivityImpact(
                title = humanize(record.actionType),
                timestampEpochMillis = record.createdAtEpochMillis,
                outcome = record.outcome,
                detail = buildString {
                    append(if (record.scope.isBlank()) "Device action" else humanize(record.scope))
                    if (record.bytesChanged != 0L) append(" · ${formatBytes(abs(record.bytesChanged))} changed")
                },
                observation = observation,
                reversibleUntilEpochMillis = record.reversibleUntilEpochMillis,
            )
        }
        val automationItems = input.automationEvents.map { event ->
            ActivityImpact(
                title = "Automation · ${event.ruleName}",
                timestampEpochMillis = event.createdAtEpochMillis,
                outcome = event.outcome,
                detail = event.detail,
                observation = correlateAround(event.createdAtEpochMillis, health),
            )
        }
        return (optimizationItems + automationItems).sortedByDescending { it.timestampEpochMillis }
    }

    private fun correlateAround(timestamp: Long, health: List<DeviceHealthSampleEntity>): String? {
        val before = health.asReversed().firstOrNull { it.capturedAtEpochMillis <= timestamp && timestamp - it.capturedAtEpochMillis <= IMPACT_WINDOW_MILLIS }
        val after = health.firstOrNull { it.capturedAtEpochMillis > timestamp && it.capturedAtEpochMillis - timestamp <= IMPACT_WINDOW_MILLIS }
        if (before == null || after == null) return null
        val parts = buildList {
            val storageDelta = after.internalStorageAvailableBytes - before.internalStorageAvailableBytes
            if (abs(storageDelta) >= 10L * 1024L * 1024L) add("storage ${if (storageDelta >= 0) "+" else "-"}${formatBytes(abs(storageDelta))}")
            val beforeMemory = before.memoryUsedPercent.takeIf(Double::isFinite)
            val afterMemory = after.memoryUsedPercent.takeIf(Double::isFinite)
            if (beforeMemory != null && afterMemory != null) {
                val memoryDelta = afterMemory - beforeMemory
                if (abs(memoryDelta) >= 2.0) add("memory ${signed1(memoryDelta)} pp")
            }
            val beforeTemp = before.batteryTemperatureCelsius?.takeIf(Double::isFinite)
            val afterTemp = after.batteryTemperatureCelsius?.takeIf(Double::isFinite)
            if (beforeTemp != null && afterTemp != null && abs(afterTemp - beforeTemp) >= 0.5) add("battery temp ${signed1(afterTemp - beforeTemp)} °C")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Observed around this action: ", separator = " · ")
    }

    private fun compareAverage(
        name: String,
        current: List<Double>,
        previous: List<Double>,
        higherIsWorse: Boolean,
        unit: String = "%",
    ): Triple<String, String, IntelligenceTone>? {
        val validCurrent = current.filter(Double::isFinite)
        val validPrevious = previous.filter(Double::isFinite)
        if (validCurrent.size < 3 || validPrevious.size < 3) return null
        val now = validCurrent.average()
        val before = validPrevious.average()
        val delta = now - before
        val threshold = if (unit.contains("°C")) 1.5 else 5.0
        if (abs(delta) < threshold) return null
        val worse = if (higherIsWorse) delta > 0 else delta < 0
        val title = if (worse) "$name increased versus prior day" else "$name improved versus prior day"
        val detail = "24-hour average ${format1(now)}$unit vs ${format1(before)}$unit (${signed1(delta)}$unit). This is an observed trend, not proof of a cause."
        return Triple(title, detail, if (worse) IntelligenceTone.Attention else IntelligenceTone.Healthy)
    }

    private fun toneFor(score: Int) = when {
        score >= 85 -> IntelligenceTone.Healthy
        score >= 70 -> IntelligenceTone.Informational
        else -> IntelligenceTone.Attention
    }

    private fun humanize(value: String): String = value
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token -> token.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) } }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun signed1(value: Double): String = String.format(Locale.US, "%+.1f", value)

    private fun formatBytes(bytes: Long): String {
        val value = bytes.toDouble()
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
            else -> "$bytes B"
        }
    }
}
