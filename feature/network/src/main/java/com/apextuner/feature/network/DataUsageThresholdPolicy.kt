package com.apextuner.feature.network

object DataUsageThresholdPolicy {
    fun crossed(
        thresholdBytes: Long,
        currentBytes: Long,
        previous: DataUsageObservation?,
        currentPeriodKey: String,
    ): Boolean {
        if (thresholdBytes <= 0L || currentBytes < 0L) return false
        if (currentBytes < thresholdBytes) return false
        if (previous == null || previous.periodKey != currentPeriodKey) return true
        return previous.bytes < thresholdBytes
    }
}
