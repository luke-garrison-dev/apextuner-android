package com.apextuner.feature.notifications

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHistoryAccessController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val componentName: ComponentName
        get() = ComponentName(context, ApexNotificationListenerService::class.java)

    fun availability(): NotificationHistoryAvailability {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && activityManager.isLowRamDevice) {
            return NotificationHistoryAvailability(
                available = false,
                reason = "Android does not bind notification listeners on low-RAM devices running Android 10 or earlier.",
            )
        }

        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager.isManagedProfile) {
            return NotificationHistoryAvailability(
                available = false,
                reason = "Android does not bind notification listeners running inside a work profile.",
            )
        }

        return NotificationHistoryAvailability(available = true)
    }

    fun isAccessGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(componentName)
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }

    fun isComponentEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(componentName) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setCollectionComponentEnabled(enabled: Boolean) {
        val manager = context.packageManager
        val effectiveEnabled = enabled && availability().available
        val targetState = if (effectiveEnabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (manager.getComponentEnabledSetting(componentName) != targetState) {
            manager.setComponentEnabledSetting(
                componentName,
                targetState,
                PackageManager.DONT_KILL_APP,
            )
        }

        if (effectiveEnabled && isAccessGranted()) {
            NotificationListenerService.requestRebind(componentName)
        } else if (!effectiveEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationListenerService.requestUnbind(componentName)
        }
    }

    fun openAccessSettings(makeComponentVisible: Boolean = true): Boolean {
        if (!availability().available) return false
        if (makeComponentVisible && !isComponentEnabled()) {
            setCollectionComponentEnabled(true)
        }

        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            componentName.flattenToString(),
                        ),
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }

        val selected = intents.firstOrNull { intent ->
            intent.resolveActivity(context.packageManager) != null
        } ?: return false

        return runCatching {
            context.startActivity(selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
