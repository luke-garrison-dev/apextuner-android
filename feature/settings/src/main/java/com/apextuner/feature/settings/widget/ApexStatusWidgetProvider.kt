package com.apextuner.feature.settings.widget

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.StatFs
import android.widget.RemoteViews
import com.apextuner.core.billing.EncryptedEntitlementCache
import com.apextuner.core.security.AndroidKeystoreSecureKeyValueStore
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.settings.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ApexStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { updateIds(context.applicationContext, manager, appWidgetIds) } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) requestUpdate(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.apextuner.action.REFRESH_WIDGET"

        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ApexStatusWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val intent = Intent(context, ApexStatusWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }

        private fun updateIds(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val premium = runCatching {
                EncryptedEntitlementCache(AndroidKeystoreSecureKeyValueStore(context))
                    .loadOfflineGrace(System.currentTimeMillis())
                    ?.isPremium == true
            }.getOrDefault(false)
            val views = if (premium) buildPremiumViews(context) else buildLockedViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildPremiumViews(context: Context): RemoteViews {
            val battery = context.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 }
            val memoryInfo = ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
            }
            val ramUsed = if (memoryInfo.totalMem > 0L) {
                ((memoryInfo.totalMem - memoryInfo.availMem).toDouble() / memoryInfo.totalMem * 100.0).toInt().coerceIn(0, 100)
            } else null
            val stat = StatFs(File(context.filesDir.absolutePath).absolutePath)
            val free = runCatching { stat.availableBytes }.getOrNull()
            return RemoteViews(context.packageName, R.layout.apex_status_widget).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.status_widget_apextuner))
                setTextViewText(R.id.widget_status, context.getString(R.string.status_widget_summary, battery?.let { context.getString(R.string.status_widget_percent, it) } ?: context.getString(R.string.status_widget_unavailable), ramUsed?.let { context.getString(R.string.status_widget_percent, it) } ?: context.getString(R.string.status_widget_unavailable)))
                setTextViewText(R.id.widget_storage, context.getString(R.string.status_widget_internal_free, free?.let(ByteSizeFormatter::format) ?: context.getString(R.string.status_widget_unavailable)))
                setTextViewText(R.id.widget_open, context.getString(R.string.status_widget_open))
                setTextViewText(R.id.widget_refresh, context.getString(R.string.status_widget_refresh))
                setOnClickPendingIntent(R.id.widget_open, launchPendingIntent(context, 7401))
                setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
            }
        }

        private fun buildLockedViews(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.apex_status_widget).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.status_widget_premium_title))
                setTextViewText(R.id.widget_status, context.getString(R.string.status_widget_locked))
                setTextViewText(R.id.widget_storage, context.getString(R.string.status_widget_restore_premium))
                setTextViewText(R.id.widget_open, context.getString(R.string.status_widget_open))
                setTextViewText(R.id.widget_refresh, context.getString(R.string.status_widget_refresh))
                setOnClickPendingIntent(R.id.widget_open, launchPendingIntent(context, 7402))
                setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
            }

        private fun launchPendingIntent(context: Context, requestCode: Int): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        private fun refreshPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            7403,
            Intent(context, ApexStatusWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
