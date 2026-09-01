package com.apextuner.core.system

import com.apextuner.core.model.CpuUsageAvailability

internal sealed interface CpuCounterRead {
    data class Success(
        val counters: CpuCounters,
        val uptimeMillis: Long,
    ) : CpuCounterRead

    data object RestrictedByPlatform : CpuCounterRead
    data object Unavailable : CpuCounterRead
}

internal data class CpuUsageReading(
    val percent: Double?,
    val availability: CpuUsageAvailability,
)

internal class CpuUsageTracker(
    private val minSampleGapMillis: Long = DEFAULT_MIN_SAMPLE_GAP_MILLIS,
    private val maxSampleGapMillis: Long = DEFAULT_MAX_SAMPLE_GAP_MILLIS,
) {
    private var previous: CpuCounterRead.Success? = null
    private var lastAvailable: TimedCpuUsage? = null

    init {
        require(minSampleGapMillis >= 0L) { "Minimum sample gap must not be negative." }
        require(maxSampleGapMillis > minSampleGapMillis) { "Maximum sample gap must exceed minimum sample gap." }
    }

    @Synchronized
    fun update(read: CpuCounterRead): CpuUsageReading = when (read) {
        CpuCounterRead.RestrictedByPlatform -> {
            previous = null
            lastAvailable = null
            CpuUsageReading(percent = null, availability = CpuUsageAvailability.RestrictedByPlatform)
        }

        CpuCounterRead.Unavailable -> {
            previous = null
            lastAvailable = null
            CpuUsageReading(percent = null, availability = CpuUsageAvailability.Unavailable)
        }

        is CpuCounterRead.Success -> updateSuccessfulRead(read)
    }

    private fun updateSuccessfulRead(current: CpuCounterRead.Success): CpuUsageReading {
        val baseline = previous
        if (baseline == null) {
            previous = current
            return CpuUsageReading(percent = null, availability = CpuUsageAvailability.Sampling)
        }

        val sampleGapMillis = current.uptimeMillis - baseline.uptimeMillis
        if (sampleGapMillis < 0L) {
            previous = current
            lastAvailable = null
            return CpuUsageReading(percent = null, availability = CpuUsageAvailability.Sampling)
        }
        if (sampleGapMillis < minSampleGapMillis) {
            val cached = lastAvailable
                ?.takeIf { current.uptimeMillis - it.uptimeMillis <= maxSampleGapMillis }
                ?.percent
            return if (cached == null) {
                CpuUsageReading(percent = null, availability = CpuUsageAvailability.Sampling)
            } else {
                CpuUsageReading(percent = cached, availability = CpuUsageAvailability.Available)
            }
        }

        previous = current
        if (sampleGapMillis > maxSampleGapMillis) {
            return CpuUsageReading(percent = null, availability = CpuUsageAvailability.Sampling)
        }

        val percent = TelemetryParsers.cpuUsagePercent(baseline.counters, current.counters)
            ?: return CpuUsageReading(percent = null, availability = CpuUsageAvailability.Unavailable)

        lastAvailable = TimedCpuUsage(
            percent = percent,
            uptimeMillis = current.uptimeMillis,
        )
        return CpuUsageReading(percent = percent, availability = CpuUsageAvailability.Available)
    }

    private data class TimedCpuUsage(
        val percent: Double,
        val uptimeMillis: Long,
    )

    private companion object {
        const val DEFAULT_MIN_SAMPLE_GAP_MILLIS = 250L
        const val DEFAULT_MAX_SAMPLE_GAP_MILLIS = 15_000L
    }
}
