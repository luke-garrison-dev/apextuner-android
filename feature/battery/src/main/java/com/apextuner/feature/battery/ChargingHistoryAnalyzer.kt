package com.apextuner.feature.battery

import com.apextuner.feature.battery.model.ChargingHistorySummary
import com.apextuner.feature.battery.model.ChargingSessionInsight
import kotlin.math.max

internal object ChargingHistoryAnalyzer {
    private const val HOUR_MILLIS = 60.0 * 60.0 * 1000.0

    fun analyze(sessions: List<ChargingSessionInsight>): ChargingHistorySummary {
        val completed = sessions.filter { it.durationMillis > 0L }
        val rates = completed.mapNotNull(::ratePercentPerHour)
        val latestRate = completed.firstOrNull()?.let(::ratePercentPerHour)
        val baselineRates = completed.drop(1).mapNotNull(::ratePercentPerHour)
        val typical = median(rates)
        val baseline = median(baselineRates)
        val peaks = completed.mapNotNull { it.peakTemperatureCelsius?.takeIf(Double::isFinite) }
        val warm = peaks.count { it >= 40.0 }
        val hot = peaks.count { it >= 42.0 }
        val latest = completed.firstOrNull()
        val slower = latestRate != null && baseline != null && baselineRates.size >= 3 &&
            latestRate < baseline * 0.65 &&
            ((latest?.endLevelPercent ?: 0) - (latest?.startLevelPercent ?: 0)) >= 10 &&
            (latest?.durationMillis ?: 0L) >= 20L * 60L * 1000L

        val insight = when {
            completed.size < 3 -> "More completed charging sessions are needed for a personal baseline."
            hot > 0 -> "$hot of the last ${completed.size} recorded sessions reached at least 42 °C. Heat can affect charging behavior and long-term battery wear."
            slower -> "The latest observed charging rate was substantially below your recent personal baseline. Cable, adapter, workload, temperature, and Android charge management can all contribute."
            warm > 0 -> "$warm of the last ${completed.size} recorded sessions reached at least 40 °C. Consider reducing workload while charging if this happens frequently."
            else -> "Recent charging sessions are thermally stable and do not show a strong rate anomaly against the available personal baseline."
        }
        return ChargingHistorySummary(
            completedSessions = completed.size,
            typicalChargeRatePercentPerHour = typical,
            latestChargeRatePercentPerHour = latestRate,
            averagePeakTemperatureCelsius = peaks.takeIf { it.isNotEmpty() }?.average(),
            warmSessionCount = warm,
            hotSessionCount = hot,
            latestSlowerThanBaseline = slower,
            insight = insight,
        )
    }

    private fun ratePercentPerHour(session: ChargingSessionInsight): Double? {
        val start = session.startLevelPercent ?: return null
        val end = session.endLevelPercent ?: return null
        val gain = end - start
        if (gain <= 0 || session.durationMillis <= 0L) return null
        val hours = max(session.durationMillis / HOUR_MILLIS, 1.0 / 60.0)
        return gain / hours
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
