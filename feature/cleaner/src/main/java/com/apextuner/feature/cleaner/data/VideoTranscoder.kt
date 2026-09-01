package com.apextuner.feature.cleaner.data

import android.content.ContentResolver
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.view.Surface
import com.apextuner.feature.cleaner.domain.MediaReencodeEstimator
import com.apextuner.feature.cleaner.model.MediaReencodePreset
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal fun mapExtractorSampleFlagsToCodecFlags(sampleFlags: Int): Int {
    var codecFlags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return codecFlags
}

internal class VideoTranscoder(
    private val resolver: ContentResolver,
) {
    fun capabilityIssue(
        sourceUri: Uri,
        requestedWidth: Int,
        requestedHeight: Int,
        preset: MediaReencodePreset,
    ): String? {
        val pfd = resolver.openFileDescriptor(sourceUri, "r")
            ?: return "Android could not open the source video for capability checks."
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(pfd.fileDescriptor)
            val hasDrmMetadata = runCatching { extractor.drmInitData != null }.getOrDefault(false) ||
                !runCatching { extractor.psshInfo }.getOrNull().isNullOrEmpty()
            val hasConditionalAccess = (0 until extractor.trackCount).any { index ->
                runCatching { extractor.getCasInfo(index) != null }.getOrDefault(false)
            }
            if (hasDrmMetadata || hasConditionalAccess) {
                return "Encrypted or DRM-protected video cannot be re-encoded."
            }

            val videoTracks = trackIndices(extractor, "video/")
            val audioTracks = trackIndices(extractor, "audio/")
            val auxiliaryTracks = (0 until extractor.trackCount).filter { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.isNotBlank() &&
                    !mime.startsWith("video/", ignoreCase = true) &&
                    !mime.startsWith("audio/", ignoreCase = true)
            }
            if (videoTracks.size != 1) {
                return "ApexTuner safely supports videos with exactly one video track."
            }
            if (audioTracks.size > 1 || auxiliaryTracks.isNotEmpty()) {
                return "This media contains multiple or auxiliary tracks that ApexTuner cannot preserve safely."
            }

            val videoTrack = videoTracks.single()
            val sourceFormat = extractor.getTrackFormat(videoTrack)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: return "The source video track does not expose a MIME type."
            val colorTransfer = sourceFormat.intOrDefault(MediaFormat.KEY_COLOR_TRANSFER, -1)
            if (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
            ) {
                return "HDR video re-encoding is unavailable because this path cannot guarantee preservation of HDR color characteristics."
            }

            extractor.selectTrack(videoTrack)
            val encryptedVideo = extractor.sampleTime >= 0L &&
                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0
            extractor.unselectTrack(videoTrack)
            if (encryptedVideo) {
                return "Encrypted or DRM-protected video cannot be re-encoded."
            }

            val hasDecoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codecInfo ->
                !codecInfo.isEncoder &&
                    codecInfo.supportedTypes.any { it.equals(sourceMime, ignoreCase = true) }
            }
            if (!hasDecoder) {
                return "This device does not expose a decoder for $sourceMime."
            }

            val audioTrack = audioTracks.singleOrNull()
            if (audioTrack != null) {
                val audioMime = extractor.getTrackFormat(audioTrack)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                if (!audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)) {
                    return "This video uses $audioMime audio. ApexTuner preserves audio only when it can pass AAC through safely."
                }
            }

            val frameRate = sourceFormat.intOrDefault(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                .coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE)
            runCatching {
                selectEncoderPlan(
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                    requestedBitrate = MediaReencodeEstimator.targetVideoBitrate(
                        requestedWidth,
                        requestedHeight,
                        preset,
                    ),
                    frameRate = frameRate,
                )
            }.exceptionOrNull()?.message
        } catch (error: Exception) {
            error.message ?: "Android could not validate this video's codec capabilities."
        } finally {
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }
    }

    suspend fun transcode(
        sourceUri: Uri,
        destination: File,
        requestedWidth: Int,
        requestedHeight: Int,
        preset: MediaReencodePreset,
        durationMillis: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        require(requestedWidth > 0 && requestedHeight > 0) { "Target dimensions must be positive." }
        require(durationMillis > 0L) { "Video duration must be known before transcoding." }

        val videoPfd = resolver.openFileDescriptor(sourceUri, "r")
            ?: error("Android could not open the source video.")
        val videoExtractor = MediaExtractor()
        var audioPassthrough: AudioPassthrough? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var renderer: CodecSurfaceRenderer? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(videoPfd.fileDescriptor)
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: error("No decodable video track was found.")
            val sourceFormat = videoExtractor.getTrackFormat(videoTrack)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The source video track does not expose a MIME type.")
            val rotation = sourceFormat.intOrDefault(MediaFormat.KEY_ROTATION, 0)
            val sourceFrameRate = sourceFormat.intOrDefault(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                .coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE)
            val plan = selectEncoderPlan(
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
                requestedBitrate = MediaReencodeEstimator.targetVideoBitrate(
                    requestedWidth,
                    requestedHeight,
                    preset,
                ),
                frameRate = sourceFrameRate,
            )

            audioPassthrough = createAudioPassthrough(sourceUri)

            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                if (rotation in VALID_ROTATIONS) it.setOrientationHint(rotation)
            }
            val audioTrackIndex = audioPassthrough?.let { muxer.addTrack(it.format) } ?: -1

            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                plan.width,
                plan.height,
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            encoder = MediaCodec.createByCodecName(plan.codecName)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            renderer = CodecSurfaceRenderer(inputSurface, plan.width, plan.height)
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(sourceFormat, renderer.decoderSurface, null, 0)
            decoder.start()

            videoExtractor.selectTrack(videoTrack)
            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderOutputDone = false
            var encoderEosSignaled = false
            var videoMuxerTrack = -1
            var videoSamplesWritten = 0L
            var lastVideoPresentationUs = -1L

            while (!encoderOutputDone) {
                coroutineContext.ensureActive()

                if (!decoderInputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: error("Decoder did not expose its requested input buffer.")
                        val sampleSize = videoExtractor.readSampleData(input, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            decoderInputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                videoExtractor.sampleTime.coerceAtLeast(0L),
                                mapExtractorSampleFlagsToCodecFlags(videoExtractor.sampleFlags),
                            )
                            videoExtractor.advance()
                        }
                    }
                }

                if (!decoderOutputDone) {
                    val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            val render = decoderInfo.size > 0
                            val eos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val minimumPresentationUs = if (lastVideoPresentationUs == Long.MAX_VALUE) {
                                Long.MAX_VALUE
                            } else {
                                lastVideoPresentationUs + 1L
                            }
                            val presentationUs = decoderInfo.presentationTimeUs.coerceAtLeast(minimumPresentationUs)
                            decoder.releaseOutputBuffer(outputIndex, render)
                            if (render) {
                                renderer.renderFrame(presentationUs)
                                lastVideoPresentationUs = presentationUs
                                val fraction = (presentationUs.toDouble() / (durationMillis * 1_000.0))
                                    .toFloat()
                                    .coerceIn(0f, 0.995f)
                                onProgress(fraction)
                            }
                            if (eos) {
                                decoderOutputDone = true
                                if (!encoderEosSignaled) {
                                    encoder.signalEndOfInputStream()
                                    encoderEosSignaled = true
                                }
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    }
                }

                while (true) {
                    coroutineContext.ensureActive()
                    val outputIndex = encoder.dequeueOutputBuffer(encoderInfo, if (decoderOutputDone) CODEC_TIMEOUT_US else 0L)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(videoMuxerTrack < 0) { "Encoder output format changed more than once." }
                            videoMuxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val output = encoder.getOutputBuffer(outputIndex)
                                ?: error("Encoder did not expose its requested output buffer.")
                            val codecConfig = encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (codecConfig) encoderInfo.size = 0
                            if (encoderInfo.size > 0) {
                                check(muxerStarted && videoMuxerTrack >= 0) {
                                    "Encoder produced media before the muxer was ready."
                                }
                                output.position(encoderInfo.offset)
                                output.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(videoMuxerTrack, output, encoderInfo)
                                videoSamplesWritten++
                                audioPassthrough?.writeThrough(
                                    muxer = muxer,
                                    trackIndex = audioTrackIndex,
                                    maxPresentationTimeUs = encoderInfo.presentationTimeUs + AUDIO_INTERLEAVE_LEAD_US,
                                )
                            }
                            encoderOutputDone = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (encoderOutputDone) break
                        }
                    }
                }
            }

            check(muxerStarted && videoSamplesWritten > 0L) {
                "The encoder did not produce a valid video stream."
            }
            audioPassthrough?.writeThrough(
                muxer = muxer,
                trackIndex = audioTrackIndex,
                maxPresentationTimeUs = Long.MAX_VALUE,
            )
            onProgress(1f)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { renderer?.release() }
            runCatching { inputSurface?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            audioPassthrough?.release()
            runCatching { videoExtractor.release() }
            runCatching { videoPfd.close() }
        }
    }

    private fun createAudioPassthrough(sourceUri: Uri): AudioPassthrough? {
        val pfd = resolver.openFileDescriptor(sourceUri, "r")
            ?: return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(pfd.fileDescriptor)
            val audioTrack = findTrack(extractor, "audio/") ?: run {
                extractor.release()
                pfd.close()
                return null
            }
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)) {
                extractor.release()
                pfd.close()
                throw UnsupportedOperationException(
                    "This video uses $mime audio. ApexTuner preserves audio only when it can pass AAC through safely.",
                )
            }
            extractor.selectTrack(audioTrack)
            AudioPassthrough(extractor, pfd, format)
        } catch (error: Throwable) {
            runCatching { extractor.release() }
            runCatching { pfd.close() }
            throw error
        }
    }

    private fun selectEncoderPlan(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedBitrate: Long,
        frameRate: Int,
    ): EncoderPlan {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (codecInfo in candidates) {
            if (!codecInfo.isEncoder ||
                codecInfo.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            ) {
                continue
            }
            val capabilities = runCatching {
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }.getOrNull() ?: continue
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) continue
            val video = capabilities.videoCapabilities ?: continue
            val width = alignDown(requestedWidth, video.widthAlignment).coerceAtLeast(video.supportedWidths.lower)
            val height = alignDown(requestedHeight, video.heightAlignment).coerceAtLeast(video.supportedHeights.lower)
            if (!video.isSizeSupported(width, height)) continue
            val chosenFrameRate = when {
                runCatching { video.areSizeAndRateSupported(width, height, frameRate.toDouble()) }.getOrDefault(false) ->
                    frameRate
                runCatching { video.areSizeAndRateSupported(width, height, DEFAULT_FRAME_RATE.toDouble()) }.getOrDefault(false) ->
                    DEFAULT_FRAME_RATE
                else -> continue
            }
            val bitrate = requestedBitrate
                .coerceIn(video.bitrateRange.lower.toLong(), video.bitrateRange.upper.toLong())
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
            return EncoderPlan(
                codecName = codecInfo.name,
                width = width,
                height = height,
                bitrate = bitrate,
                frameRate = chosenFrameRate,
            )
        }
        error("No compatible H.264 surface encoder was found for the requested video size.")
    }

    private fun trackIndices(extractor: MediaExtractor, mimePrefix: String): List<Int> =
        (0 until extractor.trackCount).filter { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                .orEmpty()
                .startsWith(mimePrefix, ignoreCase = true)
        }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        trackIndices(extractor, mimePrefix).firstOrNull()

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private fun alignDown(value: Int, alignment: Int): Int {
        val safeAlignment = alignment.coerceAtLeast(1)
        return (value / safeAlignment) * safeAlignment
    }

    private data class EncoderPlan(
        val codecName: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val frameRate: Int,
    )

    private class AudioPassthrough(
        private val extractor: MediaExtractor,
        private val pfd: android.os.ParcelFileDescriptor,
        val format: MediaFormat,
    ) {
        private val buffer = ByteBuffer.allocateDirect(
            format.intOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_AUDIO_BUFFER_BYTES)
                .coerceIn(MIN_AUDIO_BUFFER_BYTES, MAX_AUDIO_BUFFER_BYTES),
        )
        private val info = MediaCodec.BufferInfo()
        private var finished = false

        suspend fun writeThrough(
            muxer: MediaMuxer,
            trackIndex: Int,
            maxPresentationTimeUs: Long,
        ) {
            if (finished || trackIndex < 0) return
            while (!finished) {
                coroutineContext.ensureActive()
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L) {
                    finished = true
                    break
                }
                if (sampleTime > maxPresentationTimeUs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    finished = true
                    break
                }
                info.set(
                    0,
                    size,
                    sampleTime,
                    mapExtractorSampleFlagsToCodecFlags(extractor.sampleFlags),
                )
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(trackIndex, buffer, info)
                if (!extractor.advance()) finished = true
            }
        }

        fun release() {
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }

        private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default
    }

    private class CodecSurfaceRenderer(
        encoderSurface: Surface,
        private val width: Int,
        private val height: Int,
    ) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val windowSurface: android.opengl.EGLSurface
        private val program: Int
        private val textureId: Int
        private val surfaceTexture: SurfaceTexture
        val decoderSurface: Surface

        @Volatile private var frameAvailable = false

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display was unavailable." }
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "EGL initialization failed." }
            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "No recordable EGL configuration was found."
            }
            val config = requireNotNull(configs[0])
            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed." }
            windowSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                encoderSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(windowSurface != EGL14.EGL_NO_SURFACE) { "Encoder EGL surface creation failed." }
            makeCurrent()

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            textureId = createExternalTexture()
            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    frameAvailable = true
                }
            }
            decoderSurface = Surface(surfaceTexture)
        }

        suspend fun renderFrame(presentationTimeUs: Long) {
            val deadline = SystemClock.elapsedRealtime() + FRAME_WAIT_TIMEOUT_MILLIS
            while (!frameAvailable) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) {
                    error("Timed out waiting for a decoded video frame.")
                }
                delay(FRAME_WAIT_POLL_MILLIS)
            }
            frameAvailable = false
            makeCurrent()
            surfaceTexture.updateTexImage()
            val textureMatrix = FloatArray(16)
            surfaceTexture.getTransformMatrix(textureMatrix)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            val textureLocation = GLES20.glGetAttribLocation(program, "aTextureCoord")
            val matrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                FULL_RECTANGLE_VERTICES,
            )
            GLES20.glEnableVertexAttribArray(textureLocation)
            GLES20.glVertexAttribPointer(
                textureLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                FULL_RECTANGLE_TEX_COORDS,
            )
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError()

            check(EGLExt.eglPresentationTimeANDROID(display, windowSurface, presentationTimeUs * 1_000L)) {
                "Could not set encoder presentation time."
            }
            check(EGL14.eglSwapBuffers(display, windowSurface)) {
                "Could not submit a frame to the video encoder."
            }
        }

        fun release() {
            runCatching {
                makeCurrent()
                decoderSurface.release()
                surfaceTexture.release()
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                GLES20.glDeleteProgram(program)
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, windowSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        }

        private fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                "Could not make the encoder EGL context current."
            }
        }

        private fun createExternalTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val id = textures[0]
            check(id != 0) { "Could not allocate an external video texture." }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR.toFloat(),
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat(),
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            checkGlError()
            return id
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val created = GLES20.glCreateProgram()
            check(created != 0) { "Could not create the OpenGL program." }
            GLES20.glAttachShader(created, vertex)
            GLES20.glAttachShader(created, fragment)
            GLES20.glLinkProgram(created)
            val status = IntArray(1)
            GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(created)
                GLES20.glDeleteProgram(created)
                error("Could not link the video shader: $log")
            }
            return created
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Could not create an OpenGL shader." }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Could not compile the video shader: $log")
            }
            return shader
        }

        private fun checkGlError() {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "OpenGL error 0x${error.toString(16)}." }
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
            private const val FRAME_WAIT_TIMEOUT_MILLIS = 2_000L
            private const val FRAME_WAIT_POLL_MILLIS = 2L

            private val FULL_RECTANGLE_VERTICES: FloatBuffer = floatBufferOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            )
            private val FULL_RECTANGLE_TEX_COORDS: FloatBuffer = floatBufferOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            )

            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                uniform mat4 uTextureMatrix;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = aPosition;
                    vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """

            private fun floatBufferOf(vararg values: Float): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(values)
                        position(0)
                    }
        }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val DEFAULT_FRAME_RATE = 30
        const val MIN_FRAME_RATE = 12
        const val MAX_FRAME_RATE = 60
        const val AUDIO_INTERLEAVE_LEAD_US = 500_000L
        const val DEFAULT_AUDIO_BUFFER_BYTES = 256 * 1024
        const val MIN_AUDIO_BUFFER_BYTES = 64 * 1024
        const val MAX_AUDIO_BUFFER_BYTES = 2 * 1024 * 1024
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    }
}
