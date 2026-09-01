package com.apextuner.feature.network.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.navigation.AppLaunchContract
import com.apextuner.core.system.ForegroundServiceLaunchResult
import com.apextuner.core.system.ForegroundServiceLauncher
import com.apextuner.feature.network.FirewallRuntimeState
import com.apextuner.feature.network.firewall.ApexFirewallVpnService
import com.apextuner.feature.network.firewall.FirewallPreferences
import com.apextuner.feature.network.firewall.FirewallRuntimeRegistry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ApexFirewallTileService : TileService() {
    @Inject lateinit var entitlementRepository: EntitlementRepository
    @Inject lateinit var firewallPreferences: FirewallPreferences

    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val active = FirewallRuntimeRegistry.runtime.value.state in
                setOf(FirewallRuntimeState.Starting, FirewallRuntimeState.Active)
            if (active) {
                stopService(Intent(this, ApexFirewallVpnService::class.java).setAction(ApexFirewallVpnService.ACTION_STOP))
                refreshTile()
                return@unlockAndRun
            }
            val worker = scope ?: return@unlockAndRun
            worker.launch {
                val configured = runCatching { firewallPreferences.blockedPackages.first().isNotEmpty() }.getOrDefault(false)
                if (!entitlementRepository.entitlement.value.isPremium || !configured || VpnService.prepare(this@ApexFirewallTileService) != null) {
                    openAppFromTile()
                    return@launch
                }
                val result = ForegroundServiceLauncher.start(
                    this@ApexFirewallTileService,
                    Intent(this@ApexFirewallTileService, ApexFirewallVpnService::class.java)
                        .setAction(ApexFirewallVpnService.ACTION_START),
                )
                if (result != ForegroundServiceLaunchResult.Started) {
                    FirewallRuntimeRegistry.update(
                        FirewallRuntimeState.Error,
                        error = "Android could not start the firewall service. Open ApexTuner and try again.",
                    )
                    refreshTile()
                    openAppFromTile()
                    return@launch
                }
                refreshTile()
            }
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun refreshTile() {
        val active = FirewallRuntimeRegistry.runtime.value.state in
            setOf(FirewallRuntimeState.Starting, FirewallRuntimeState.Active)
        qsTile?.apply {
            label = getString(com.apextuner.feature.network.R.string.firewall_tile_label)
            val status = when {
                !entitlementRepository.entitlement.value.isPremium -> getString(com.apextuner.feature.network.R.string.premium_required)
                active -> getString(com.apextuner.feature.network.R.string.tile_active)
                FirewallRuntimeRegistry.runtime.value.state == FirewallRuntimeState.Error ->
                    getString(com.apextuner.feature.network.R.string.tile_attention)
                else -> getString(com.apextuner.feature.network.R.string.tile_off)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = status
            contentDescription = getString(com.apextuner.feature.network.R.string.firewall_tile_description, label, status)
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun openAppFromTile() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.putExtra(AppLaunchContract.EXTRA_DESTINATION, AppLaunchContract.DESTINATION_NETWORK)
            ?.putExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN, System.currentTimeMillis())
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 7401, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, ApexFirewallTileService::class.java))
        }
    }
}
