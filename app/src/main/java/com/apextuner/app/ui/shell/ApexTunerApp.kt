package com.apextuner.app.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apextuner.app.AppLaunchRequest
import com.apextuner.app.R
import com.apextuner.app.navigation.TopLevelDestination
import com.apextuner.core.model.EntitlementState
import com.apextuner.core.navigation.AppLaunchContract
import com.apextuner.core.ui.ApexBrandMark
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexNavigationPresentation
import com.apextuner.feature.appmanager.AppManagerRoute
import com.apextuner.feature.battery.BatteryRoute
import com.apextuner.feature.billing.BillingRoute
import com.apextuner.feature.cleaner.OptimizeRoute
import com.apextuner.feature.contacts.ContactMergeRoute
import com.apextuner.feature.files.FileManagerRoute
import com.apextuner.feature.dashboard.DashboardRoute
import com.apextuner.feature.dashboard.intelligence.IntelligenceDestination
import com.apextuner.feature.dashboard.intelligence.IntelligenceRoute
import com.apextuner.feature.dashboard.model.RecommendationType
import com.apextuner.feature.memory.MemoryRoute
import com.apextuner.feature.network.NetworkRoute
import com.apextuner.feature.network.diagnostics.NetworkDiagnosticsRoute
import com.apextuner.feature.notifications.NotificationHistoryRoute
import com.apextuner.feature.settings.SettingsRoute
import com.apextuner.feature.settings.SettingsSection
import com.apextuner.feature.tools.ToolsRoute
import com.apextuner.feature.tools.advanced.AdvancedToolsRoute
import com.apextuner.feature.tools.game.GameBoosterRoute
import com.apextuner.feature.tools.diagnostics.DiagnosticReportRoute
import com.apextuner.feature.tools.performance.PerformanceRoute
import com.apextuner.feature.tools.security.SecurityRoute
import com.apextuner.feature.tools.systeminfo.SystemInfoRoute

private const val PREMIUM_ROUTE = "premium"
private const val SETTINGS_AUTOMATION_ROUTE = "settings/automation"
private const val SETTINGS_NOTIFICATIONS_ROUTE = "settings/notifications"

@Composable
fun ApexTunerApp(
    entitlement: EntitlementState,
    showAdvancedTools: Boolean,
    launchRequest: AppLaunchRequest? = null,
    onLaunchRequestConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val openPremium = { navController.navigate(PREMIUM_ROUTE) { launchSingleTop = true } }

    LaunchedEffect(launchRequest?.token) {
        val request = launchRequest ?: return@LaunchedEffect
        navController.navigate(request.destination) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        if (!request.quickScan || request.destination != AppLaunchContract.DESTINATION_OPTIMIZE) {
            onLaunchRequestConsumed()
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        ApexBackdrop()
        val navigationPresentation = ApexLayout.navigationPresentationFor(
            widthDp = maxWidth.value.toInt(),
            heightDp = maxHeight.value.toInt(),
        )
        val useNavigationRail = navigationPresentation != ApexNavigationPresentation.BottomBar
        val fontScale = LocalDensity.current.fontScale
        val compactNavigationRail = ApexLayout.shouldUseCompactNavigationRail(
            presentation = navigationPresentation,
            fontScale = fontScale,
        )
        val showAllBottomLabels = ApexLayout.showAllBottomNavigationLabels(
            widthDp = maxWidth.value.toInt(),
            fontScale = fontScale,
        )
        val bottomNavigationLabelStyle = MaterialTheme.typography.labelSmall.copy(
            fontSize = 13.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        )
        val railItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val barItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                Surface(
                    modifier = Modifier
                        .padding(
                            start = if (compactNavigationRail) 6.dp else 12.dp,
                            top = if (compactNavigationRail) 6.dp else 12.dp,
                            bottom = if (compactNavigationRail) 6.dp else 12.dp,
                        )
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.96f)),
                ) {
                    NavigationRail(
                        modifier = Modifier.padding(horizontal = if (compactNavigationRail) 0.dp else 8.dp),
                        containerColor = Color.Transparent,
                        header = if (compactNavigationRail) null else {
                            {
                                AppIdentityHeader(
                                    compact = true,
                                    isPremium = entitlement.isPremium,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        },
                    ) {
                        TopLevelDestination.all.forEach { destination ->
                            val selected = isTopLevelSelected(
                                destination = destination,
                                currentRoute = currentDestination?.route.orEmpty(),
                                hierarchyMatches = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                            )
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes), modifier = Modifier.size(26.dp)) },
                                label = if (compactNavigationRail) null else ({ Text(stringResource(destination.labelRes), style = MaterialTheme.typography.labelMedium) }),
                                alwaysShowLabel = !compactNavigationRail,
                                colors = railItemColors,
                            )
                        }
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                bottomBar = {
                    if (!useNavigationRail) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.97f),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            shape = MaterialTheme.shapes.extraLarge,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.98f)),
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                windowInsets = NavigationBarDefaults.windowInsets,
                            ) {
                                TopLevelDestination.all.forEach { destination ->
                                    val selected = isTopLevelSelected(
                                        destination = destination,
                                        currentRoute = currentDestination?.route.orEmpty(),
                                        hierarchyMatches = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                                    )
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes), modifier = Modifier.size(27.dp)) },
                                        label = if (showAllBottomLabels) ({ Text(stringResource(destination.labelRes), maxLines = 1, softWrap = false, style = bottomNavigationLabelStyle) }) else null,
                                        alwaysShowLabel = showAllBottomLabels,
                                        colors = barItemColors,
                                    )
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = TopLevelDestination.Dashboard.route,
                        modifier = Modifier
                            .widthIn(max = ApexLayout.MaxContentWidth)
                            .fillMaxSize(),
                    ) {
                        composable(TopLevelDestination.Dashboard.route) {
                            DashboardRoute(
                                onOpenIntelligence = { navController.navigate("dashboard/intelligence") { launchSingleTop = true } },
                                onRecommendation = { type ->
                                    when (type) {
                                        RecommendationType.StorageCritical,
                                        RecommendationType.StorageLow -> navController.navigate(TopLevelDestination.Optimize.route) { launchSingleTop = true }
                                        RecommendationType.MemoryLow,
                                        RecommendationType.MemoryHigh -> navController.navigate("tools/memory") { launchSingleTop = true }
                                        RecommendationType.ThermalCritical,
                                        RecommendationType.ThermalModerate,
                                        RecommendationType.BatteryWarm,
                                        RecommendationType.BatteryLow -> navController.navigate("tools/battery") { launchSingleTop = true }
                                        RecommendationType.NetworkUnvalidated -> navController.navigate("tools/network-diagnostics") { launchSingleTop = true }
                                        RecommendationType.Healthy -> Unit
                                    }
                                },
                            )
                        }
                        composable("dashboard/intelligence") {
                            IntelligenceRoute(
                                onBack = { navController.popBackStack() },
                                onDestination = { destination ->
                                    val route = when (destination) {
                                        IntelligenceDestination.Battery -> "tools/battery"
                                        IntelligenceDestination.Network -> "tools/network-diagnostics"
                                        IntelligenceDestination.Memory -> "tools/memory"
                                        IntelligenceDestination.Optimize -> TopLevelDestination.Optimize.route
                                        IntelligenceDestination.Automation -> SETTINGS_AUTOMATION_ROUTE
                                        IntelligenceDestination.GameBooster -> "tools/game"
                                    }
                                    navController.navigate(route) { launchSingleTop = true }
                                },
                            )
                        }
                        composable(TopLevelDestination.Optimize.route) {
                            OptimizeRoute(
                                premiumEnabled = entitlement.isPremium,
                                onUpgrade = openPremium,
                                autoStartScanToken = launchRequest
                                    ?.takeIf {
                                        it.quickScan &&
                                            it.destination == AppLaunchContract.DESTINATION_OPTIMIZE
                                    }
                                    ?.token,
                                onAutoStartConsumed = onLaunchRequestConsumed,
                            )
                        }
                        composable(TopLevelDestination.Apps.route) { AppManagerRoute() }
                        composable(TopLevelDestination.Tools.route) {
                            ToolsRoute(
                                onBattery = { navController.navigate("tools/battery") { launchSingleTop = true } },
                                onMemory = { navController.navigate("tools/memory") { launchSingleTop = true } },
                                onPerformance = { navController.navigate("tools/performance") { launchSingleTop = true } },
                                onNetwork = { navController.navigate("tools/network") { launchSingleTop = true } },
                                onNetworkDiagnostics = { navController.navigate("tools/network-diagnostics") { launchSingleTop = true } },
                                onFiles = { navController.navigate("tools/files") { launchSingleTop = true } },
                                onContacts = { navController.navigate("tools/contacts") { launchSingleTop = true } },
                                onNotificationHistory = { navController.navigate("tools/notifications") { launchSingleTop = true } },
                                onSecurity = { navController.navigate("tools/security") { launchSingleTop = true } },
                                onGameBooster = { navController.navigate("tools/game") { launchSingleTop = true } },
                                onSystemInfo = { navController.navigate("tools/system-info") { launchSingleTop = true } },
                                onDiagnosticReport = { navController.navigate("tools/diagnostic-report") { launchSingleTop = true } },
                                onAdvanced = {
                                    if (entitlement.isPremium) navController.navigate("tools/advanced") { launchSingleTop = true } else openPremium()
                                },
                                showAdvancedTools = showAdvancedTools,
                            )
                        }
                        composable("tools/battery") { BatteryRoute(onBack = { navController.popBackStack() }) }
                        composable("tools/memory") { MemoryRoute(onBack = { navController.popBackStack() }) }
                        composable("tools/performance") { PerformanceRoute(onBack = { navController.popBackStack() }) }
                        composable("tools/network") {
                            NetworkRoute(
                                onBack = { navController.popBackStack() },
                                premiumEnabled = entitlement.isPremium,
                                onUpgrade = openPremium,
                            )
                        }
                        composable("tools/network-diagnostics") {
                            NetworkDiagnosticsRoute(onBack = { navController.popBackStack() })
                        }
                        composable("tools/files") {
                            FileManagerRoute(onBack = { navController.popBackStack() })
                        }
                        composable("tools/contacts") {
                            ContactMergeRoute(onBack = { navController.popBackStack() })
                        }
                        composable("tools/notifications") {
                            NotificationHistoryRoute(
                                onBack = { navController.popBackStack() },
                                onOpenSettings = {
                                    navController.navigate(TopLevelDestination.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onUpgrade = openPremium,
                            )
                        }
                        composable("tools/security") { SecurityRoute(onBack = { navController.popBackStack() }) }
                        composable("tools/game") {
                            GameBoosterRoute(
                                onBack = { navController.popBackStack() },
                                premiumEnabled = entitlement.isPremium,
                                onUpgrade = openPremium,
                            )
                        }
                        composable("tools/system-info") { SystemInfoRoute(onBack = { navController.popBackStack() }) }
                        composable("tools/diagnostic-report") { DiagnosticReportRoute(onBack = { navController.popBackStack() }) }
                        composable("tools/advanced") {
                            // showAdvancedTools controls discoverability, not entitlement. If a premium
                            // user reaches a retained/deep-linked route after hiding the tile, never
                            // send them to a paywall they already own.
                            if (entitlement.isPremium) {
                                AdvancedToolsRoute(onBack = { navController.popBackStack() })
                            } else {
                                BillingRoute(onBack = { navController.popBackStack() })
                            }
                        }
                        composable(TopLevelDestination.Settings.route) {
                            SettingsRoute(
                                onPremium = openPremium,
                                onNotificationHistory = { navController.navigate(SETTINGS_NOTIFICATIONS_ROUTE) { launchSingleTop = true } },
                            )
                        }
                        composable(SETTINGS_NOTIFICATIONS_ROUTE) {
                            NotificationHistoryRoute(
                                onBack = { navController.popBackStack() },
                                backLabelRes = com.apextuner.feature.notifications.R.string.ui_back,
                                onOpenSettings = {
                                    navController.navigate(TopLevelDestination.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onUpgrade = openPremium,
                            )
                        }
                        composable(SETTINGS_AUTOMATION_ROUTE) {
                            SettingsRoute(
                                onPremium = openPremium,
                                onNotificationHistory = { navController.navigate(SETTINGS_NOTIFICATIONS_ROUTE) { launchSingleTop = true } },
                                initialSection = SettingsSection.SmartAutomation,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(PREMIUM_ROUTE) { BillingRoute(onBack = { navController.popBackStack() }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApexBackdrop() {
    val background = MaterialTheme.colorScheme.background
    val deepest = MaterialTheme.colorScheme.surfaceContainerLowest
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(background, deepest, background),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.03f, size.height * 0.08f),
                radius = size.width * 0.82f,
            ),
            radius = size.width * 0.82f,
            center = Offset(size.width * 0.03f, size.height * 0.08f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.095f), Color.Transparent),
                center = Offset(size.width * 0.98f, size.height * 0.12f),
                radius = size.width * 0.78f,
            ),
            radius = size.width * 0.78f,
            center = Offset(size.width * 0.98f, size.height * 0.12f),
        )

        val stroke = Stroke(width = 0.7.dp.toPx())
        repeat(7) { index ->
            val shift = index * 7.dp.toPx()
            val alpha = (0.085f - index * 0.008f).coerceAtLeast(0.025f)
            drawArc(
                color = primary.copy(alpha = alpha),
                startAngle = 205f,
                sweepAngle = 98f,
                useCenter = false,
                topLeft = Offset(-size.width * 0.62f, size.height * 0.055f + shift),
                size = Size(size.width * 1.20f, size.height * 0.28f),
                style = stroke,
            )
            drawArc(
                color = secondary.copy(alpha = alpha),
                startAngle = 238f,
                sweepAngle = 98f,
                useCenter = false,
                topLeft = Offset(size.width * 0.47f, size.height * 0.055f + shift),
                size = Size(size.width * 1.20f, size.height * 0.28f),
                style = stroke,
            )
        }
    }
}

@Composable
private fun AppIdentityHeader(
    compact: Boolean,
    isPremium: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 6.dp else 0.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ApexBrandMark(
            modifier = Modifier.height(if (compact) 58.dp else 68.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(if (compact) R.string.app_tagline_compact else R.string.app_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.Transparent,
            contentColor = if (isPremium) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            border = BorderStroke(
                1.dp,
                (if (isPremium) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary).copy(alpha = 0.48f),
            ),
        ) {
            Text(
                text = stringResource(if (isPremium) R.string.app_edition_premium else R.string.app_edition_free),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun isTopLevelSelected(
    destination: TopLevelDestination,
    currentRoute: String,
    hierarchyMatches: Boolean,
): Boolean = hierarchyMatches || when (destination) {
    TopLevelDestination.Dashboard -> currentRoute.startsWith("dashboard/")
    TopLevelDestination.Tools -> currentRoute.startsWith("tools/")
    TopLevelDestination.Settings -> currentRoute.startsWith("settings/")
    else -> false
}
