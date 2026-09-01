package com.apextuner.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Dashboard : TopLevelDestination("dashboard", "Dashboard", Icons.Outlined.Home)
    data object Optimize : TopLevelDestination("optimize", "Optimize", Icons.Outlined.RocketLaunch)
    data object Apps : TopLevelDestination("apps", "Apps", Icons.Outlined.GridView)
    data object Tools : TopLevelDestination("tools", "Tools", Icons.Outlined.BusinessCenter)
    data object Settings : TopLevelDestination("settings", "Settings", Icons.Outlined.Settings)

    companion object {
        val all = listOf(Dashboard, Optimize, Apps, Tools, Settings)
    }
}
