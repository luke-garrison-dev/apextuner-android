package com.apextuner.core.system

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

enum class ForegroundServiceLaunchResult {
    Started,
    PlatformRestricted,
    SecurityRejected,
    Failed,
}

/** Keeps platform foreground-service start rejections recoverable at user-triggered entry points. */
object ForegroundServiceLauncher {
    fun start(context: Context, intent: Intent): ForegroundServiceLaunchResult =
        attempt {
            ContextCompat.startForegroundService(context, intent)
            Unit
        }

    internal fun attempt(start: () -> Unit): ForegroundServiceLaunchResult =
        try {
            start()
            ForegroundServiceLaunchResult.Started
        } catch (_: SecurityException) {
            ForegroundServiceLaunchResult.SecurityRejected
        } catch (_: IllegalStateException) {
            ForegroundServiceLaunchResult.PlatformRestricted
        } catch (_: RuntimeException) {
            ForegroundServiceLaunchResult.Failed
        }
}
