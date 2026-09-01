package com.apextuner.feature.settings.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.apextuner.core.database.BatteryHealthSnapshotDao
import com.apextuner.feature.settings.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BatteryHealthWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var batteryHealthSnapshotDao: BatteryHealthSnapshotDao

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                update(context.applicationContext, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val premium = WidgetSupport.isPremium(context)
        val snapshots = if (premium) batteryHealthSnapshotDao.recent(31) else emptyList()
        val capacityText: String
        val trendText: String
        if (!premium) {
            capacityText = context.getString(R.string.widget_premium_required)
            trendText = context.getString(R.string.widget_open_apextuner)
        } else {
            when (val trend = BatteryWidgetTrendPolicy.evaluate(snapshots)) {
                BatteryWidgetTrend.Unavailable -> {
                    capacityText = context.getString(R.string.battery_widget_capacity_unavailable)
                    trendText = context.getString(R.string.battery_widget_telemetry_unavailable)
                }
                is BatteryWidgetTrend.Insufficient -> {
                    capacityText = trend.latestCapacityMicroampHours
                        ?.let { context.getString(R.string.battery_widget_capacity, it / 1_000.0) }
                        ?: context.getString(R.string.battery_widget_capacity_unavailable)
                    trendText = context.getString(R.string.battery_widget_insufficient_history, BatteryWidgetTrendPolicy.MIN_DAYS)
                }
                is BatteryWidgetTrend.Ready -> {
                    capacityText = context.getString(
                        R.string.battery_widget_capacity,
                        trend.latestCapacityMicroampHours / 1_000.0,
                    )
                    trendText = context.getString(R.string.battery_widget_trend, trend.percentChange)
                }
            }
        }

        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.apex_battery_health_widget).apply {
                setTextViewText(R.id.battery_widget_title, context.getString(R.string.battery_widget_title))
                setTextViewText(R.id.battery_widget_capacity, capacityText)
                setTextViewText(R.id.battery_widget_trend, trendText)
                setOnClickPendingIntent(R.id.battery_widget_root, WidgetSupport.launchPendingIntent(context, 7602))
            }
            manager.updateAppWidget(id, views)
        }
    }}
