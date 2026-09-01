package com.apextuner.feature.settings.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.StatFs
import android.widget.RemoteViews
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.settings.R
import java.io.File

class StorageDonutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val stat = StatFs(File(context.filesDir.absolutePath).absolutePath)
        val total = runCatching { stat.totalBytes }.getOrDefault(0L)
        val free = runCatching { stat.availableBytes }.getOrDefault(0L).coerceIn(0L, total.coerceAtLeast(0L))
        val usedFraction = StorageWidgetMath.usedFraction(total, free)
        val premium = WidgetSupport.isPremium(context)

        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.apex_storage_widget).apply {
                setTextViewText(R.id.storage_widget_title, context.getString(R.string.storage_widget_title))
                if (premium) {
                    setImageViewBitmap(R.id.storage_widget_donut, storageDonut(usedFraction))
                    setTextViewText(
                        R.id.storage_widget_value,
                        context.getString(R.string.storage_widget_free, ByteSizeFormatter.format(free)),
                    )
                } else {
                    setImageViewBitmap(R.id.storage_widget_donut, storageDonut(0.0))
                    setTextViewText(R.id.storage_widget_value, context.getString(R.string.widget_premium_required))
                }
                setOnClickPendingIntent(R.id.storage_widget_root, WidgetSupport.launchPendingIntent(context, 7601))
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun storageDonut(usedFraction: Double): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 18f
            strokeCap = Paint.Cap.ROUND
        }
        val inset = 14f
        paint.color = 0x334A8DFF
        canvas.drawArc(inset, inset, size - inset, size - inset, 0f, 360f, false, paint)
        paint.color = 0xFF4A8DFF.toInt()
        canvas.drawArc(
            inset, inset, size - inset, size - inset, -90f,
            (360.0 * usedFraction.coerceIn(0.0, 1.0)).toFloat(), false, paint,
        )
        return bitmap
    }
}
