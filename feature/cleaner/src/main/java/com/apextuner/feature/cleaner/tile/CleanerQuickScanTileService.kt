package com.apextuner.feature.cleaner.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.apextuner.core.navigation.AppLaunchContract

class CleanerQuickScanTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(com.apextuner.feature.cleaner.R.string.cleaner_quick_scan_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(com.apextuner.feature.cleaner.R.string.cleaner_quick_scan_tile_subtitle)
            }
            contentDescription = getString(com.apextuner.feature.cleaner.R.string.cleaner_quick_scan_tile_description)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun { openQuickScan() }
    }

    private fun openQuickScan() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.putExtra(AppLaunchContract.EXTRA_DESTINATION, AppLaunchContract.DESTINATION_OPTIMIZE)
            ?.putExtra(AppLaunchContract.EXTRA_QUICK_SCAN, true)
            ?.putExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN, System.currentTimeMillis())
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 7501, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

}
