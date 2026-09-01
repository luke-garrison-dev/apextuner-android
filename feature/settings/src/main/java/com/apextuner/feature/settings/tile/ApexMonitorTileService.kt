package com.apextuner.feature.settings.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.navigation.AppLaunchContract
import com.apextuner.core.system.ForegroundServiceLaunchResult
import com.apextuner.core.system.ForegroundServiceLauncher
import com.apextuner.feature.settings.monitor.ApexMonitorService
import com.apextuner.feature.settings.monitor.MonitorRuntimeRegistry
import com.apextuner.feature.settings.monitor.MonitorRuntimeState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ApexMonitorTileService : TileService() {
    @Inject lateinit var entitlementRepository: EntitlementRepository

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val active = MonitorRuntimeRegistry.state.value.state in setOf(MonitorRuntimeState.Starting, MonitorRuntimeState.Active)
            if (active) {
                stopService(Intent(this, ApexMonitorService::class.java))
                refreshTile()
                return@unlockAndRun
            }
            if (!entitlementRepository.entitlement.value.isPremium || !Settings.canDrawOverlays(this)) {
                openAppFromTile()
                return@unlockAndRun
            }
            val result = ForegroundServiceLauncher.start(
                this,
                Intent(this, ApexMonitorService::class.java).setAction(ApexMonitorService.ACTION_START),
            )
            if (result != ForegroundServiceLaunchResult.Started) {
                MonitorRuntimeRegistry.update(
                    MonitorRuntimeState.Error,
                    "Android could not start the monitor service. Open ApexTuner and try again.",
                )
                refreshTile()
                openAppFromTile()
                return@unlockAndRun
            }
            refreshTile()
        }
    }

    private fun refreshTile() {
        val active = MonitorRuntimeRegistry.state.value.state in setOf(MonitorRuntimeState.Starting, MonitorRuntimeState.Active)
        qsTile?.apply {
            label = getString(com.apextuner.feature.settings.R.string.monitor_tile_label)
            val statusText = when {
                !entitlementRepository.entitlement.value.isPremium -> getString(com.apextuner.feature.settings.R.string.monitor_tile_premium_required)
                active -> getString(com.apextuner.feature.settings.R.string.monitor_tile_active)
                !Settings.canDrawOverlays(this@ApexMonitorTileService) -> getString(com.apextuner.feature.settings.R.string.monitor_tile_overlay_needed)
                MonitorRuntimeRegistry.state.value.state == MonitorRuntimeState.Error ->
                    getString(com.apextuner.feature.settings.R.string.monitor_tile_attention)
                else -> getString(com.apextuner.feature.settings.R.string.monitor_tile_off)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = statusText
            contentDescription = getString(com.apextuner.feature.settings.R.string.monitor_tile_description, label, statusText)
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun openAppFromTile() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.putExtra(AppLaunchContract.EXTRA_DESTINATION, AppLaunchContract.DESTINATION_SETTINGS)
            ?.putExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN, System.currentTimeMillis())
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                7301,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, ApexMonitorTileService::class.java))
        }
    }

}
