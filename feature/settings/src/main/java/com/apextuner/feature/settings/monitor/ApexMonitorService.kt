package com.apextuner.feature.settings.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.feature.settings.R
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.settings.tile.ApexMonitorTileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class ApexMonitorService : Service() {
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var entitlementRepository: EntitlementRepository
    @Inject lateinit var preferencesRepository: PreferencesRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlay: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var samplingJob: Job? = null
    private var entitlementJob: Job? = null
    private var baseline: NetworkRateBaseline? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote synchronously before any asynchronous entitlement/telemetry work.
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.monitor_notification_starting)), foregroundType)
        MonitorRuntimeRegistry.update(MonitorRuntimeState.Starting)
        if (!entitlementRepository.entitlement.value.isPremium) return failAndStop("ApexTuner Premium is required for the real-time monitor.")
        if (!Settings.canDrawOverlays(this)) return failAndStop("Display-over-other-apps permission is required.")
        if (overlay == null) runCatching { attachOverlay() }.getOrElse { return failAndStop(it.message ?: "Overlay could not be created.") }
        startEntitlementGuard()
        startSampling()
        MonitorRuntimeRegistry.update(MonitorRuntimeState.Active)
        ApexMonitorTileService.requestRefresh(this)
        updateNotification("CPU, RAM, battery and network monitor active")
        return START_NOT_STICKY
    }

    private fun attachOverlay() {
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "ApexTuner\nReading device…"
            setTextColor(0xFFE7F8FF.toInt())
            textSize = 13f
            setLineSpacing(0f, 1.08f)
            maxWidth = (resources.displayMetrics.widthPixels - (24 * density).toInt()).coerceAtLeast(1)
            setPadding((13*density).toInt(), (10*density).toInt(), (13*density).toInt(), (10*density).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF20A1620.toInt(), 0xF2143040.toInt(), 0xF226183A.toInt()),
            ).apply {
                cornerRadius = 16f * density
                setStroke((1*density).toInt().coerceAtLeast(1), 0x8054E2FF.toInt())
            }
            elevation = 10f * density
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (100 * density).toInt()
        }
        installDragHandler(view, lp)
        windowManager.addView(view, lp)
        overlay = view
        params = lp
    }

    private fun installDragHandler(view: TextView, lp: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    clampOverlayToDisplay(view, lp)
                    true
                }
                else -> false
            }
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = overlay ?: return
        val lp = params ?: return
        val density = resources.displayMetrics.density
        view.maxWidth = (resources.displayMetrics.widthPixels - (24 * density).toInt()).coerceAtLeast(1)
        // Wait for TextView to remeasure against the new display width before clamping.
        view.post { clampOverlayToDisplay(view, lp) }
    }

    private fun clampOverlayToDisplay(view: TextView, lp: WindowManager.LayoutParams) {
        val display = resources.displayMetrics
        val maxX = (display.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (display.heightPixels - view.height).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun startEntitlementGuard() {
        entitlementJob?.cancel()
        entitlementJob = serviceScope.launch {
            entitlementRepository.entitlement
                .map { it.isPremium }
                .distinctUntilChanged()
                .collect { premium -> if (!premium) stopSelf() }
        }
    }

    private fun startSampling() {
        samplingJob?.cancel()
        baseline = null
        samplingJob = serviceScope.launch {
            preferencesRepository.preferences
                .map { it.telemetryRefreshMillis }
                .distinctUntilChanged()
                .flatMapLatest { refreshMillis ->
                    baseline = null
                    deviceRepository.observeSnapshots(refreshMillis)
                }
                .retryWhen { error, attempt ->
                    if (error is CancellationException) return@retryWhen false
                    // A transient OEM/sysfs/provider failure must not strand an apparently-active
                    // foreground service with a permanently completed sampling flow. Reset the
                    // network baseline and retry with bounded exponential backoff to avoid spinning.
                    baseline = null
                    overlay?.text = "ApexTuner\nTelemetry temporarily unavailable • retrying"
                    delay(MonitorRetryPolicy.delayMillis(attempt))
                    true
                }
                .collectLatest { snapshot -> render(snapshot) }
        }
    }

    private fun render(snapshot: DeviceSnapshot) {
        val (nextBaseline, rates) = MonitorRateCalculator.rates(
            baseline,
            snapshot.uptimeMillis,
            snapshot.network.totalRxBytes,
            snapshot.network.totalTxBytes,
        )
        baseline = nextBaseline
        val cpu = snapshot.cpu.totalUsagePercent?.let { "%.0f%%".format(it) } ?: "—"
        val ram = "%.0f%%".format(snapshot.memory.usedFraction * 100.0)
        val battery = snapshot.battery.levelPercent?.let { "$it%" } ?: "—"
        val temp = snapshot.battery.temperatureCelsius?.let { "%.1f°C".format(it) } ?: "—"
        val down = rates.rxBytesPerSecond?.let(ByteSizeFormatter::format) ?: "—"
        val up = rates.txBytesPerSecond?.let(ByteSizeFormatter::format) ?: "—"
        overlay?.text = "ApexTuner  CPU $cpu  RAM $ram\nBattery $battery  Temp $temp\n↓ $down/s   ↑ $up/s"
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = Intent(this, ApexMonitorService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 7202, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 7201, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .addAction(0, getString(R.string.monitor_notification_stop), stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.monitor_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.monitor_channel_description)
            },
        )
    }

    private fun failAndStop(message: String): Int {
        MonitorRuntimeRegistry.update(MonitorRuntimeState.Error, message)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        entitlementJob?.cancel()
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        params = null
        baseline = null
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (MonitorRuntimeRegistry.state.value.state != MonitorRuntimeState.Error) {
            MonitorRuntimeRegistry.update(MonitorRuntimeState.Stopped)
        }
        ApexMonitorTileService.requestRefresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.apextuner.action.START_MONITOR"
        const val ACTION_STOP = "com.apextuner.action.STOP_MONITOR"
        private const val CHANNEL_ID = "apextuner_monitor"
        private const val NOTIFICATION_ID = 7200
    }
}
