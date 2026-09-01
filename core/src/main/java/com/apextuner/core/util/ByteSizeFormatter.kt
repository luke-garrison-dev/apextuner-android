package com.apextuner.core.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object ByteSizeFormatter {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val pattern = when {
            value >= 100 || unitIndex == 0 -> "0"
            value >= 10 -> "0.0"
            else -> "0.00"
        }
        val formatter = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale))
        return "${formatter.format(value)} ${units[unitIndex]}"
    }
}
