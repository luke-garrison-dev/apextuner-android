package com.apextuner.feature.tools.performance

internal object PerformanceParsers {
    fun selectedScheduler(raw: String): String? {
        val selected = Regex("\\[([^]]+)]").find(raw)?.groupValues?.getOrNull(1)?.trim()
        return selected?.takeIf { it.isNotBlank() }
    }

    fun safeKernelToken(raw: String): String? = raw.trim().takeIf {
        it.isNotBlank() && it.length <= 64 && it.all { ch -> ch.isLetterOrDigit() || ch in "_-+." }
    }
}
