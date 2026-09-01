package com.apextuner.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.apextuner.app.R

sealed class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    data object Dashboard : TopLevelDestination("dashboard", R.string.nav_dashboard, Icons.Outlined.Home)
    data object Optimize : TopLevelDestination("optimize", R.string.nav_optimize, Icons.Outlined.RocketLaunch)
    data object Apps : TopLevelDestination("apps", R.string.nav_apps, Icons.Outlined.GridView)
    data object Tools : TopLevelDestination("tools", R.string.nav_tools, Icons.Outlined.BusinessCenter)
    data object Settings : TopLevelDestination("settings", R.string.nav_settings, Icons.Outlined.Settings)

    companion object {
        val all = listOf(Dashboard, Optimize, Apps, Tools, Settings)
    }
}
