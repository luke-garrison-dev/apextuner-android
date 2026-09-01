package com.apextuner.feature.cleaner.data

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTranscoderFlagsTest {

    @Test
    fun `sync sample maps to codec key frame flag`() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            mapExtractorSampleFlagsToCodecFlags(MediaExtractor.SAMPLE_FLAG_SYNC),
        )
    }

    @Test
    fun `partial extractor sample maps to codec partial frame flag`() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            mapExtractorSampleFlagsToCodecFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME),
        )
    }

    @Test
    fun `combined supported extractor flags are translated without numeric reuse`() {
        val extractorFlags = MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
        val expected = MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME

        assertEquals(expected, mapExtractorSampleFlagsToCodecFlags(extractorFlags))
    }

    @Test
    fun `encrypted extractor bit is never forwarded as a codec buffer flag`() {
        assertEquals(
            0,
            mapExtractorSampleFlagsToCodecFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED),
        )
    }
}
