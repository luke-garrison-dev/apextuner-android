package com.apextuner.feature.settings.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.apextuner.core.billing.EncryptedEntitlementCache
import com.apextuner.core.security.AndroidKeystoreSecureKeyValueStore

internal object WidgetSupport {
    fun isPremium(context: Context): Boolean = runCatching {
        EncryptedEntitlementCache(AndroidKeystoreSecureKeyValueStore(context))
            .loadOfflineGrace(System.currentTimeMillis())
            ?.isPremium == true
    }.getOrDefault(false)

    fun launchPendingIntent(context: Context, requestCode: Int): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context,
                requestCode,
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
}
