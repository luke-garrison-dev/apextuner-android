package com.apextuner.core.system

internal data class CpuCounters(
    val idle: Long,
    val total: Long,
)

internal object TelemetryParsers {
    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")

    fun parseCpuCounters(line: String): CpuCounters? {
        if (!line.startsWith("cpu ")) return null
        val values = line.trim()
            .split(Regex("\\s+"))
            .drop(1)
            .map { it.toLongOrNull() ?: return null }
        if (values.size < 4) return null

        // Linux /proc/stat: idle is field 4 and iowait field 5. Guest values are
        // already included in user/nice, so only the first eight counters belong
        // in the total to avoid double-counting them.
        val idleBase = values.getOrElse(3) { 0L }
        val ioWait = values.getOrElse(4) { 0L }
        if (idleBase < 0L || ioWait < 0L || Long.MAX_VALUE - idleBase < ioWait) return null
        val idle = idleBase + ioWait
        val total = values.take(8).fold(0L) { sum, value ->
            if (value < 0L || Long.MAX_VALUE - sum < value) return null
            sum + value
        }
        if (total <= 0L || idle < 0L || idle > total) return null
        return CpuCounters(idle = idle, total = total)
    }

    fun cpuUsagePercent(previous: CpuCounters, current: CpuCounters): Double? {
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L || idleDelta > totalDelta) return null
        return ((totalDelta - idleDelta).toDouble() / totalDelta.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
    }

    fun parseGpuUtilizationPercent(fileName: String, rawValue: String): Double? {
        val values = numberRegex.findAll(rawValue.trim())
            .mapNotNull { it.value.toDoubleOrNull() }
            .filter { it.isFinite() && it >= 0.0 }
            .toList()
        if (values.isEmpty()) return null

        return when {
            fileName == "gpubusy" -> {
                if (values.size < 2 || values[1] <= 0.0) null
                else (values[0] / values[1] * 100.0).takeIf { it in 0.0..100.0 }
            }
            values[0] in 0.0..100.0 -> values[0]
            else -> null
        }
    }
}
