package com.apextuner.feature.tools.recording

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordingService : Service() {
    @Volatile private var session: RecordingSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop("Stopped by user")
            ACTION_START -> startRecording(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.requestStop("Service stopped")
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (session != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            ScreenRecordingRuntime.set(ScreenRecordingState.Failed, "Screen-capture consent was not available.")
            stopSelf()
            return
        }

        ScreenRecordingRuntime.set(ScreenRecordingState.Starting)
        try {
            val notification = buildNotification(
                getString(com.apextuner.feature.tools.R.string.recording_notification_preparing),
            )
            val type = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            // Android can reject foreground promotion when launch eligibility changes between
            // MediaProjection consent and service execution. Keep that platform failure inside
            // the same fail-closed startup path as encoder/projection initialization.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

            val created = RecordingSession(this, resultCode, resultData) { result ->
                session = null
                if (result.success) ScreenRecordingRuntime.set(ScreenRecordingState.Idle, "Saved to Movies/ApexTuner")
                else ScreenRecordingRuntime.set(ScreenRecordingState.Failed, result.message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            session = created
            created.start()
            ScreenRecordingRuntime.set(ScreenRecordingState.Recording)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(getString(com.apextuner.feature.tools.R.string.recording_notification_active)))
        } catch (error: Throwable) {
            val message = error.message ?: "Screen recording could not start."
            val created = session
            if (created != null) {
                created.requestStop(message)
            } else {
                ScreenRecordingRuntime.set(ScreenRecordingState.Failed, message)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun requestStop(reason: String) {
        ScreenRecordingRuntime.set(ScreenRecordingState.Stopping)
        session?.requestStop(reason) ?: run { ScreenRecordingRuntime.set(ScreenRecordingState.Idle); stopSelf() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(com.apextuner.feature.tools.R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stop = PendingIntent.getService(
            this,
            5102,
            Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 5101, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(com.apextuner.feature.tools.R.string.recording_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(0, getString(com.apextuner.feature.tools.R.string.recording_notification_stop_save), stop)
            .build()
    }

    private data class SessionResult(val success: Boolean, val message: String)
    private data class EncoderPlan(
        val codecName: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val frameRate: Int,
    )

    private class RecordingSession(
        private val service: ScreenRecordingService,
        resultCode: Int,
        resultData: Intent,
        private val onFinished: (SessionResult) -> Unit,
    ) {
        private val stopRequested = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val projectionManager = service.getSystemService(MediaProjectionManager::class.java)
        // Resolve the user-authorized MediaProjection before starting a callback thread so a
        // rejected/expired consent token cannot strand a HandlerThread during construction.
        private val projection: MediaProjection = requireNotNull(
            projectionManager.getMediaProjection(resultCode, resultData),
        ) { "Android did not return a valid screen-capture session. Request capture permission again." }
        private val callbackThread = HandlerThread("ApexProjectionCallback").apply { start() }
        private val callbackHandler = Handler(callbackThread.looper)
        private var codec: MediaCodec? = null
        private var inputSurface: Surface? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var muxer: MediaMuxer? = null
        private var pfd: ParcelFileDescriptor? = null
        private var outputUri: Uri? = null
        private var trackIndex = -1
        private var muxerStarted = false
        private var drainThread: Thread? = null

        private val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() { requestStop("Android ended the capture session") }
        }

        fun start() {
            projection.registerCallback(projectionCallback, callbackHandler)
            val display = displaySize(service)
            val plan = selectEncoderPlan(display)
            val output = createOutput(service)
            outputUri = output.first
            pfd = output.second
            muxer = MediaMuxer(output.second.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val encoder = MediaCodec.createByCodecName(plan.codecName)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            inputSurface = surface
            encoder.start()
            virtualDisplay = projection.createVirtualDisplay(
                "ApexTunerRecording",
                plan.width,
                plan.height,
                service.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                callbackHandler,
            )
            drainThread = Thread(::drainLoop, "ApexRecordingEncoder").apply { isDaemon = true; start() }
        }

        fun requestStop(reason: String) {
            if (!stopRequested.compareAndSet(false, true)) return
            runCatching { codec?.signalEndOfInputStream() }
            if (drainThread == null) finish(false, reason)
        }

        private fun drainLoop() {
            val encoder = codec ?: return finish(false, "Encoder was unavailable.")
            val info = MediaCodec.BufferInfo()
            var eosDeadline = Long.MAX_VALUE
            var eosSeen = false
            var samplesWritten = 0L
            try {
                while (true) {
                    if (stopRequested.get() && eosDeadline == Long.MAX_VALUE) {
                        eosDeadline = android.os.SystemClock.elapsedRealtime() + ENCODER_EOS_TIMEOUT_MILLIS
                    }
                    val index = encoder.dequeueOutputBuffer(info, 20_000L)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IllegalStateException("Encoder format changed twice.")
                            trackIndex = muxer!!.addTrack(encoder.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (isCodecConfig) info.size = 0
                            val buffer = encoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && muxerStarted && trackIndex >= 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(trackIndex, buffer, info)
                                samplesWritten++
                            }
                            eosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eosSeen) break
                        }
                    }
                    if (stopRequested.get() && android.os.SystemClock.elapsedRealtime() >= eosDeadline) break
                }
                val valid = eosSeen && muxerStarted && samplesWritten > 0
                finish(valid, if (valid) "Recording saved." else "The encoder did not finalize a valid video in time.")
            } catch (error: Throwable) {
                finish(false, error.message ?: "Screen recording failed.")
            }
        }

        private fun finish(success: Boolean, message: String) {
            if (!finished.compareAndSet(false, true)) return
            stopRequested.set(true)
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
            runCatching { pfd?.close() }
            pfd = null
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
            callbackThread.quitSafely()

            var finalSuccess = success
            var finalMessage = message
            val uri = outputUri
            if (uri != null) {
                if (finalSuccess && Build.VERSION.SDK_INT >= 29) {
                    val published = runCatching {
                        service.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }.getOrDefault(0) > 0
                    if (!published) {
                        finalSuccess = false
                        finalMessage = "Android could not publish the completed recording safely."
                    }
                }
                if (!finalSuccess) runCatching { service.contentResolver.delete(uri, null, null) }
            }
            onFinished(SessionResult(finalSuccess, finalMessage))
        }

        private fun displaySize(context: Context): RecordingSize {
            val wm = context.getSystemService(WindowManager::class.java)
            val metrics = context.resources.displayMetrics
            val (width, height) = if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                @Suppress("DEPRECATION")
                DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
            }
            return RecordingGeometry.fitWithin(width.coerceAtLeast(2), height.coerceAtLeast(2))
        }

        private fun selectEncoderPlan(display: RecordingSize): EncoderPlan {
            val targetLongSide = maxOf(display.width, display.height).coerceAtMost(1920)
            val longSideCandidates = listOf(targetLongSide, 1600, 1440, 1280, 1080, 960, 720, 640, 480)
                .filter { it in 320..targetLongSide }
                .distinct()
                .sortedDescending()
            val plans = mutableListOf<EncoderPlan>()
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in codecInfos) {
                if (!info.isEncoder || info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
                val video = caps.videoCapabilities ?: continue
                for (longSide in longSideCandidates) {
                    val raw = RecordingGeometry.fitWithin(display.width, display.height, longSide)
                    val width = alignDown(raw.width, video.widthAlignment).coerceAtLeast(video.supportedWidths.lower)
                    val height = alignDown(raw.height, video.heightAlignment).coerceAtLeast(video.supportedHeights.lower)
                    if (!video.isSizeSupported(width, height)) continue
                    val frameRate = when {
                        runCatching { video.areSizeAndRateSupported(width, height, 30.0) }.getOrDefault(false) -> 30
                        runCatching { video.areSizeAndRateSupported(width, height, 24.0) }.getOrDefault(false) -> 24
                        runCatching { video.areSizeAndRateSupported(width, height, 20.0) }.getOrDefault(false) -> 20
                        else -> continue
                    }
                    val requestedBitrate = RecordingGeometry.bitrate(width, height)
                    val bitrate = requestedBitrate.coerceIn(video.bitrateRange.lower, video.bitrateRange.upper)
                    plans += EncoderPlan(info.name, width, height, bitrate, frameRate)
                    break
                }
            }
            return plans.maxWithOrNull(compareBy<EncoderPlan>({ it.width.toLong() * it.height.toLong() }, { it.frameRate }))
                ?: error("No compatible H.264 surface encoder was found for screen recording.")
        }

        private fun alignDown(value: Int, alignment: Int): Int {
            val safeAlignment = alignment.coerceAtLeast(1)
            return (value / safeAlignment) * safeAlignment
        }

        private fun createOutput(context: Context): Pair<Uri, ParcelFileDescriptor> {
            val resolver = context.contentResolver
            val name = "ApexTuner-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ApexTuner")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: error("Android could not create the video destination.")
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: run { resolver.delete(uri, null, null); error("Android could not open the video destination.") }
            return uri to pfd
        }
    }

    companion object {
        const val ACTION_START = "com.apextuner.action.START_SCREEN_RECORDING"
        const val ACTION_STOP = "com.apextuner.action.STOP_SCREEN_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "apextuner_screen_recording"
        private const val NOTIFICATION_ID = 5100
        private const val ENCODER_EOS_TIMEOUT_MILLIS = 5_000L
    }
}
