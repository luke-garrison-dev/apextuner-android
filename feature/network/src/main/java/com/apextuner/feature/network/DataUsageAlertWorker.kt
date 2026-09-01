package com.apextuner.feature.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.apextuner.core.util.ByteSizeFormatter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@HiltWorker
class DataUsageAlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: NetworkRepository,
    private val preferences: DataUsageCapPreferences,
    private val scheduler: DataUsageAlertScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val caps = preferences.caps.first()
        if (caps.isEmpty()) {
            preferences.setUsageAccessWarningSent(false)
            scheduler.sync(false)
            return Result.success()
        }
        return try {
            val usage = repository.loadMonthlyUsageForPackages(caps.keys)
            if (usage == null) {
                val alreadyWarned = preferences.usageAccessWarningSent.first()
                if (!alreadyWarned && notifyUsageAccessRequired()) {
                    preferences.setUsageAccessWarningSent(true)
                }
                return Result.success()
            }
            if (preferences.usageAccessWarningSent.first()) {
                preferences.setUsageAccessWarningSent(false)
            }
            NotificationManagerCompat.from(applicationContext).cancel(USAGE_ACCESS_NOTIFICATION_TAG, USAGE_ACCESS_NOTIFICATION_ID)
            val previous = preferences.observations.first()
            val period = YearMonth.now().toString()
            val next = LinkedHashMap<String, DataUsageObservation>()
            caps.forEach { (packageName, threshold) ->
                val current = usage[packageName]
                if (current == null) {
                    previous[packageName]?.let { next[packageName] = it }
                    return@forEach
                }
                if (DataUsageThresholdPolicy.crossed(threshold, current, previous[packageName], period)) {
                    notifyThreshold(packageName, current, threshold)
                }
                next[packageName] = DataUsageObservation(period, current)
            }
            preferences.replaceObservations(next)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            val alreadyWarned = preferences.usageAccessWarningSent.first()
            if (!alreadyWarned && notifyUsageAccessRequired()) {
                preferences.setUsageAccessWarningSent(true)
            }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }


    private fun notifyUsageAccessRequired(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.data_usage_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = applicationContext.getString(R.string.data_usage_channel_description)
                },
            )
        }
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                USAGE_ACCESS_NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val body = applicationContext.getString(R.string.data_usage_access_revoked_body)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(applicationContext.getString(R.string.data_usage_access_revoked_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(USAGE_ACCESS_NOTIFICATION_TAG, USAGE_ACCESS_NOTIFICATION_ID, notification)
        return true
    }

    private fun notifyThreshold(packageName: String, current: Long, threshold: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.data_usage_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = applicationContext.getString(R.string.data_usage_channel_description)
                },
            )
        }
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                packageName.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val label = runCatching {
            val info = applicationContext.packageManager.getApplicationInfo(packageName, 0)
            applicationContext.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        val body = "$label used ${ByteSizeFormatter.format(current)} this month, crossing your ${ByteSizeFormatter.format(threshold)} alert."
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(applicationContext.getString(R.string.data_usage_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(packageName.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "apextuner_data_usage_caps"
        const val USAGE_ACCESS_NOTIFICATION_ID = 0x415055
        const val USAGE_ACCESS_NOTIFICATION_TAG = "usage_access_required"
    }
}
