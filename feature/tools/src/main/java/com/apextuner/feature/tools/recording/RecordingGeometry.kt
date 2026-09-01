package com.apextuner.feature.tools.recording

data class RecordingSize(val width: Int, val height: Int)

object RecordingGeometry {
    fun fitWithin(width: Int, height: Int, maxLongSide: Int = 1920): RecordingSize {
        require(width > 0 && height > 0 && maxLongSide >= 320)
        val longest = maxOf(width, height)
        val scale = if (longest <= maxLongSide) 1.0 else maxLongSide.toDouble() / longest.toDouble()
        fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2
        return RecordingSize(even((width * scale).toInt()), even((height * scale).toInt()))
    }

    fun bitrate(width: Int, height: Int): Int {
        val raw = width.toLong() * height.toLong() * 4L
        return raw.coerceIn(2_500_000L, 12_000_000L).toInt()
    }
}
