package com.apextuner.feature.tools.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGeometryTest {
    @Test fun landscapeIsBoundedAndEven() {
        val result = RecordingGeometry.fitWithin(2560, 1440)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertEquals(0, result.width % 2)
        assertEquals(0, result.height % 2)
    }

    @Test fun portraitPreservesOrientationAndBound() {
        val result = RecordingGeometry.fitWithin(1440, 3120)
        assertTrue(result.height <= 1920)
        assertTrue(result.height > result.width)
        assertEquals(0, result.width % 2)
        assertEquals(0, result.height % 2)
    }

    @Test fun bitrateIsStrictlyBounded() {
        assertEquals(2_500_000, RecordingGeometry.bitrate(320, 240))
        assertEquals(12_000_000, RecordingGeometry.bitrate(3840, 2160))
        val mid = RecordingGeometry.bitrate(1280, 720)
        assertTrue(mid in 2_500_000..12_000_000)
    }
}
