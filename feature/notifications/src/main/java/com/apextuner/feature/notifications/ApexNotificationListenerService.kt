package com.apextuner.feature.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.EntitlementVerification
import com.apextuner.core.model.PremiumFeature
import com.apextuner.core.time.TimeProvider
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ApexNotificationListenerService : NotificationListenerService() {
    @Inject lateinit var preferences: NotificationHistoryPreferences
    @Inject lateinit var repository: NotificationHistoryRepository
    @Inject lateinit var entitlementRepository: EntitlementRepository
    @Inject lateinit var timeProvider: TimeProvider
    @Inject
    @field:IoDispatcher
    lateinit var io: CoroutineDispatcher

    private lateinit var scope: CoroutineScope
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureCounter = AtomicInteger(0)

    @Volatile
    private var currentSettings = NotificationHistorySettings()

    @Volatile
    private var premiumAccess = false

    @Volatile
    private var connected = false

    @Volatile
    private var collectionReady = false

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + io)
        premiumAccess = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory)

        scope.launch {
            preferences.settings.collectLatest { settings ->
                currentSettings = settings
                if (!settings.enabled) {
                    collectionReady = false
                    requestUnbindOnMainThread()
                }
            }
        }
        scope.launch {
            entitlementRepository.entitlement.collectLatest { entitlement ->
                premiumAccess = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory)
                if (
                    currentSettings.enabled &&
                    entitlement.verification != EntitlementVerification.NotChecked &&
                    !premiumAccess
                ) {
                    collectionReady = false
                    disableCollectionComponent()
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        collectionReady = false
        scope.launch {
            try {
                val settings = preferences.settings.first()
                currentSettings = settings
                entitlementRepository.refresh("notification_history_listener")
                premiumAccess = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory)
                if (!settings.enabled) {
                    requestUnbindOnMainThread()
                    return@launch
                }
                if (!premiumAccess) {
                    disableCollectionComponent()
                    return@launch
                }
                repository.prune(timeProvider.nowEpochMillis(), settings.retentionDays)
                repository.enforceHardLimit()
                collectionReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!premiumAccess) requestUnbindOnMainThread()
            }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        collectionReady = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val settings = currentSettings
        if (!settings.enabled || !premiumAccess || !collectionReady) return
        if (notification.packageName == packageName || notification.packageName in settings.mutedPackages) return

        val capture = runCatching { notification.toCapture() }.getOrNull() ?: return
        scope.launch {
            try {
                val inserted = repository.record(capture)
                if (inserted && captureCounter.incrementAndGet() % HARD_LIMIT_CHECK_INTERVAL == 0) {
                    repository.enforceHardLimit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    override fun onDestroy() {
        connected = false
        collectionReady = false
        if (::scope.isInitialized) scope.cancel()
        super.onDestroy()
    }

    private fun StatusBarNotification.toCapture(): NotificationCapture? {
        val packageName = NotificationHistoryPolicy.sanitizePackageName(packageName) ?: return null
        val title = NotificationHistoryPolicy.sanitizeTitle(notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        val body = NotificationHistoryPolicy.sanitizeBody(
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        if (title.isBlank() && body.isBlank()) return null
        return NotificationCapture(
            packageName = packageName,
            title = title,
            text = body,
            postedAtEpochMillis = postTime.coerceAtLeast(0L),
        )
    }

    private fun requestUnbindOnMainThread() {
        if (!connected) return
        mainHandler.post {
            if (connected) runCatching { requestUnbind() }
        }
    }

    private fun disableCollectionComponent() {
        requestUnbindOnMainThread()
        val component = ComponentName(this, ApexNotificationListenerService::class.java)
        runCatching {
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private companion object {
        const val HARD_LIMIT_CHECK_INTERVAL = 128
    }
}
