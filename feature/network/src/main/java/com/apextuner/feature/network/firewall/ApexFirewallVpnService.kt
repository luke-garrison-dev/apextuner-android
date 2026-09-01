package com.apextuner.feature.network.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.feature.network.FirewallRuntimeState
import com.apextuner.feature.network.sanitizeFirewallPackages
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class ApexFirewallVpnService : VpnService() {
    @Inject lateinit var preferences: FirewallPreferences
    @Inject lateinit var entitlementRepository: EntitlementRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tunnelSlot = CloseableResourceSlot<ParcelFileDescriptor>()
    private var discardJob: Job? = null
    private var entitlementJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopFirewall()
            ACTION_START -> {
                // startForegroundService() must be promoted promptly; do this before any DataStore/I/O suspension.
                promoteToForeground(0)
                if (!entitlementRepository.entitlement.value.isPremium) {
                    FirewallRuntimeRegistry.update(FirewallRuntimeState.Error, error = "ApexTuner Premium is required for the local firewall.")
                    stopAfterError()
                } else {
                    startEntitlementGuard()
                    startFirewall()
                }
            }
            else -> stopSelf(startId)
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        stopFirewall()
        super.onRevoke()
    }

    override fun onDestroy() {
        discardJob?.cancel()
        entitlementJob?.cancel()
        tunnelSlot.close()
        serviceScope.cancel()
        if (FirewallRuntimeRegistry.runtime.value.state != FirewallRuntimeState.Error) {
            FirewallRuntimeRegistry.update(FirewallRuntimeState.Stopped)
        }
        super.onDestroy()
    }


    private fun startEntitlementGuard() {
        entitlementJob?.cancel()
        entitlementJob = serviceScope.launch {
            entitlementRepository.entitlement
                .map { it.isPremium }
                .distinctUntilChanged()
                .collect { premium ->
                    if (!premium) {
                        FirewallRuntimeRegistry.update(FirewallRuntimeState.Error, error = "Premium access ended; the local firewall was stopped safely.")
                        stopAfterError()
                    }
                }
        }
    }

    private fun startFirewall() {
        discardJob?.cancel()
        discardJob = serviceScope.launch {
            try {
                val requested = sanitizeFirewallPackages(preferences.blockedPackages.first(), packageName)
                if (requested.isEmpty()) {
                    FirewallRuntimeRegistry.update(FirewallRuntimeState.Error, error = "Select at least one app before starting the firewall.")
                    stopAfterError()
                    return@launch
                }
                FirewallRuntimeRegistry.update(FirewallRuntimeState.Starting, requested)
                updateNotification(requested.size)
                establishSinkTunnel(requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                tunnelSlot.close()
                FirewallRuntimeRegistry.update(
                    FirewallRuntimeState.Error,
                    error = error.message ?: "The local firewall could not start.",
                )
                stopAfterError()
            }
        }
    }

    private suspend fun establishSinkTunnel(requested: Set<String>) = withContext(Dispatchers.IO) {
        tunnelSlot.close()
        val builder = Builder()
            .setSession("ApexTuner Local Firewall")
            .setMtu(TUN_MTU)
            .addAddress("10.73.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd73:6170:6578::1", 128)
            .addRoute("::", 0)
            .setBlocking(true)

        val accepted = LinkedHashSet<String>()
        requested.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
                accepted += packageName
            } catch (_: PackageManager.NameNotFoundException) {
                // The app may have been uninstalled between selection and establishment.
            }
        }
        if (accepted != requested) {
            // Prune packages Android itself confirmed are no longer installed/eligible.
            preferences.setBlockedPackages(accepted)
        }
        if (accepted.isEmpty()) {
            throw IllegalStateException("None of the selected applications is currently available.")
        }

        val established = builder.establish() ?: throw IllegalStateException("Android did not establish the VPN interface.")
        tunnelSlot.replace(established)
        FirewallRuntimeRegistry.update(FirewallRuntimeState.Active, accepted)
        updateNotification(accepted.size)

        try {
            FileInputStream(established.fileDescriptor).use { input ->
                val buffer = ByteArray(TUN_MTU)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    // Intentional sink: selected applications' packets are consumed locally and never forwarded.
                }
            }
        } catch (_: IOException) {
            // Closing the descriptor while stopping the service is expected to wake the blocking read.
        } finally {
            tunnelSlot.close(established)
            if (FirewallRuntimeRegistry.runtime.value.state == FirewallRuntimeState.Active && tunnelSlot.currentOrNull() == null) {
                FirewallRuntimeRegistry.update(FirewallRuntimeState.Stopped)
            }
        }
    }

    private fun stopFirewall() = terminate(preserveError = false)

    private fun stopAfterError() = terminate(preserveError = true)

    private fun terminate(preserveError: Boolean) {
        discardJob?.cancel()
        discardJob = null
        entitlementJob?.cancel()
        entitlementJob = null
        tunnelSlot.close()
        if (!preserveError) FirewallRuntimeRegistry.update(FirewallRuntimeState.Stopped)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }


    private fun promoteToForeground(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(count), type)
    }

    private fun updateNotification(count: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(count))
    }

    private fun notification(count: Int): Notification {
        val stopIntent = Intent(this, ApexFirewallVpnService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(com.apextuner.feature.network.R.string.firewall_notification_title))
            .setContentText(
                if (count > 0) {
                    val appLabel = getString(
                        if (count == 1) com.apextuner.feature.network.R.string.firewall_notification_app_singular
                        else com.apextuner.feature.network.R.string.firewall_notification_app_plural,
                    )
                    getString(com.apextuner.feature.network.R.string.firewall_notification_blocking, count, appLabel)
                } else {
                    getString(com.apextuner.feature.network.R.string.firewall_notification_starting)
                },
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(com.apextuner.feature.network.R.string.firewall_notification_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(com.apextuner.feature.network.R.string.firewall_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(com.apextuner.feature.network.R.string.firewall_channel_description) },
        )
    }

    companion object {
        const val ACTION_START = "com.apextuner.feature.network.firewall.START"
        const val ACTION_STOP = "com.apextuner.feature.network.firewall.STOP"
        const val NOTIFICATION_CHANNEL_ID = "apextuner_local_firewall"
        const val NOTIFICATION_ID = 2301
        const val TUN_MTU = 1_500
    }
}
