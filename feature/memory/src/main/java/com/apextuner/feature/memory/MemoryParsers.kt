package com.apextuner.feature.memory

internal data class SwapSnapshot(val totalBytes: Long, val freeBytes: Long)

internal object MemoryParsers {
    fun parseSwap(memInfo: String): SwapSnapshot? {
        var totalKb: Long? = null
        var freeKb: Long? = null
        memInfo.lineSequence().forEach { line ->
            when {
                line.startsWith("SwapTotal:") -> totalKb = parseKb(line)
                line.startsWith("SwapFree:") -> freeKb = parseKb(line)
            }
        }
        val total = totalKb ?: return null
        val free = freeKb ?: return null
        if (total < 0L || free < 0L || free > total) return null
        return SwapSnapshot(safeKbToBytes(total), safeKbToBytes(free))
    }

    fun parsePressureSomeAvg10(pressure: String): Double? {
        val some = pressure.lineSequence().firstOrNull { it.startsWith("some ") } ?: return null
        val token = some.split(' ').firstOrNull { it.startsWith("avg10=") } ?: return null
        return token.substringAfter('=').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun parseKb(line: String): Long? = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
    private fun safeKbToBytes(kb: Long): Long = if (kb > Long.MAX_VALUE / 1024L) Long.MAX_VALUE else kb * 1024L
}
