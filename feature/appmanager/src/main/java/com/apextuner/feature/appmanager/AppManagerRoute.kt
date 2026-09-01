package com.apextuner.feature.appmanager

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.util.ByteSizeFormatter
import java.text.DateFormat
import java.util.Date

@Composable
fun AppManagerRoute(viewModel: AppManagerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAfterExternalSettings()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val pendingExport = remember { arrayOfNulls<AppDetail>(1) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val detail = pendingExport[0]
        pendingExport[0] = null
        if (uri != null && detail != null) viewModel.exportApkBackup(detail.summary.packageName, uri)
    }
    AppManagerScreen(
        state = state,
        exportState = exportState,
        onRetry = viewModel::refresh,
        onQuery = viewModel::setQuery,
        onFilter = viewModel::setFilter,
        onInsightFilter = viewModel::setInsightFilter,
        onSort = viewModel::setSort,
        onClearFilters = viewModel::clearFilters,
        onSelect = viewModel::selectApp,
        onDismissDetail = viewModel::dismissDetail,
        onUsageAccess = { context.safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        onLaunch = { app -> context.launchApp(app) },
        onAppSettings = { pkg -> context.openAppSettings(pkg) },
        onNotificationSettings = { pkg -> context.openNotificationSettings(pkg) },
        onUninstall = { pkg -> context.safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) },
        onExport = { detail ->
            pendingExport[0] = detail
            val safeLabel = detail.summary.label.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "app" }
            exportLauncher.launch("${safeLabel}-${detail.summary.versionName ?: detail.summary.versionCode}-apk-backup.zip")
        },
        onDismissExport = viewModel::dismissExportMessage,
        ownPackageName = context.packageName,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppManagerScreen(
    state: AppManagerUiState,
    exportState: ApkExportUiState,
    onRetry: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (AppKindFilter) -> Unit,
    onInsightFilter: (AppInsightFilter) -> Unit,
    onSort: (AppSort) -> Unit,
    onClearFilters: () -> Unit,
    onSelect: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onUsageAccess: () -> Unit,
    onLaunch: (AppSummary) -> Unit,
    onAppSettings: (String) -> Unit,
    onNotificationSettings: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onExport: (AppDetail) -> Unit,
    onDismissExport: () -> Unit,
    ownPackageName: String,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (state) {
            AppManagerUiState.Loading -> CenteredStatus(stringResource(R.string.apps_loading))
            is AppManagerUiState.Error -> CenteredStatus(state.message, action = stringResource(R.string.apps_retry), onAction = onRetry)
            is AppManagerUiState.Ready -> {
                val visible = filterAndSortApps(
                    state.snapshot.apps,
                    state.query,
                    state.kindFilter,
                    state.insightFilter,
                    state.sort,
                    usageAccessGranted = state.snapshot.usageAccessGranted,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.11f),
                                ) {
                                    Icon(
                                        Icons.Outlined.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(9.dp),
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.apps_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item { InfoCard(state.snapshot.inventoryScopeNote) }
                    item {
                        val insights = summarizeAppInventory(state.snapshot.apps, state.snapshot.usageAccessGranted)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.apps_inventory_insights_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.apps_inventory_counts, insights.totalApps, insights.userApps, insights.systemApps))
                                Text(
                                    stringResource(R.string.apps_inventory_attention, insights.permissionHeavy, insights.legacyTarget, insights.unknownInstaller),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.apps_inventory_recent, insights.recentlyInstalled, insights.recentlyUpdated),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    insights.unused30Days?.let { stringResource(R.string.apps_inventory_unused, it) }
                                        ?: stringResource(R.string.apps_inventory_unused_locked),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.apps_inventory_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item {
                        val reviewApps = state.snapshot.apps
                            .map { app -> app to appReviewAssessment(app, state.snapshot.usageAccessGranted) }
                            .filter { (_, assessment) -> assessment.recommended }
                            .sortedWith(compareByDescending<Pair<AppSummary, AppReviewAssessment>> { it.second.priority }.thenBy { it.first.label.lowercase() })
                            .take(4)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.apps_review_center_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.apps_review_center_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (reviewApps.isEmpty()) {
                                    Text(stringResource(R.string.apps_review_center_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    reviewApps.forEachIndexed { index, (app, assessment) ->
                                        if (index > 0) HorizontalDivider()
                                        TextButton(onClick = { onSelect(app.packageName) }, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(app.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                                Text(assessment.signals.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!state.snapshot.usageAccessGranted) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.ui_deeper_app_insight_is_optional), fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.ui_usage_access_unlocks_last_used_selected_app_storage_and))
                                    OutlinedButton(onClick = onUsageAccess) { Text(stringResource(R.string.apps_usage_access)) }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQuery,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.apps_search)) },
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_filter), fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppKindFilter.entries.forEach { filter ->
                                    FilterChip(selected = state.kindFilter == filter, onClick = { onFilter(filter) }, label = { Text(filter.name) })
                                }
                            }
                            Text(stringResource(R.string.apps_insight_filter), fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppInsightFilter.entries.forEach { insight ->
                                    FilterChip(
                                        selected = state.insightFilter == insight,
                                        onClick = { onInsightFilter(insight) },
                                        enabled = state.snapshot.usageAccessGranted || insight != AppInsightFilter.Unused30Days,
                                        label = { Text(insight.displayName()) },
                                    )
                                }
                            }
                            Text(stringResource(R.string.ui_sort), fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppSort.entries.forEach { sort ->
                                    FilterChip(
                                        selected = state.sort == sort,
                                        onClick = { onSort(sort) },
                                        enabled = state.snapshot.usageAccessGranted || sort != AppSort.LastUsed,
                                        label = { Text(sort.displayName()) },
                                    )
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.apps_visible_count, visible.size), style = MaterialTheme.typography.titleMedium) }
                    if (visible.isEmpty()) item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(stringResource(R.string.apps_no_results))
                                TextButton(onClick = onClearFilters) {
                                    Text(stringResource(R.string.apps_clear_filters))
                                }
                            }
                        }
                    }
                    items(visible, key = AppSummary::packageName) { app -> AppCard(app, onClick = { onSelect(app.packageName) }) }
                }

                if (state.selectedPackage != null) {
                    AppDetailDialog(
                        detail = state.selectedDetail,
                        loading = state.detailLoading,
                        fallbackMessage = state.message,
                        onDismiss = onDismissDetail,
                        onLaunch = onLaunch,
                        onSettings = onAppSettings,
                        onNotifications = onNotificationSettings,
                        onUninstall = onUninstall,
                        onExport = onExport,
                        exportState = exportState,
                        onDismissExport = onDismissExport,
                        ownPackageName = ownPackageName,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: AppSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (app.isSystem) "System" else "User", style = MaterialTheme.typography.labelMedium)
            }
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val usage = app.lastUsedTimeMillis?.let(::formatDateTime) ?: stringResource(R.string.apps_usage_unavailable)
            Text(stringResource(R.string.apps_permission_summary, usage, app.grantedDangerousPermissionCount, app.dangerousPermissionCount), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    R.string.apps_inspector_summary,
                    app.targetSdk,
                    app.installerPackage ?: stringResource(R.string.apps_installer_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppDetailDialog(
    detail: AppDetail?,
    loading: Boolean,
    fallbackMessage: String?,
    onDismiss: () -> Unit,
    onLaunch: (AppSummary) -> Unit,
    onSettings: (String) -> Unit,
    onNotifications: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onExport: (AppDetail) -> Unit,
    exportState: ApkExportUiState,
    onDismissExport: () -> Unit,
    ownPackageName: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } },
        title = { Text(detail?.summary?.label ?: "App details") },
        text = {
            when {
                loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                detail == null -> Text(fallbackMessage ?: "Details are unavailable.")
                else -> Column(
                    modifier = Modifier.widthIn(max = 620.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(detail.summary.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MetricRow("Version", "${detail.summary.versionName ?: "?"} (${detail.summary.versionCode})")
                    MetricRow("Target / min SDK", "${detail.targetSdk} / ${detail.minSdk}")
                    MetricRow("Installed", formatDateTime(detail.summary.firstInstallTimeMillis))
                    MetricRow("Updated", formatDateTime(detail.summary.lastUpdateTimeMillis))
                    MetricRow("Installer", detail.installerPackage ?: "Unavailable")
                    detail.storage?.let {
                        Text(stringResource(R.string.ui_storage), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MetricRow("App", ByteSizeFormatter.format(it.appBytes))
                        MetricRow("Data", ByteSizeFormatter.format(it.dataBytes))
                        MetricRow("Cache", ByteSizeFormatter.format(it.cacheBytes))
                    } ?: detail.storageDiagnostic?.let { InfoCard(it) }
                    detail.network?.let {
                        Text(stringResource(R.string.apps_network_period, it.periodDays), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MetricRow(stringResource(R.string.apps_wifi), stringResource(R.string.apps_network_received_sent, ByteSizeFormatter.format(it.wifiReceivedBytes), ByteSizeFormatter.format(it.wifiSentBytes)))
                        MetricRow(stringResource(R.string.apps_mobile), stringResource(R.string.apps_network_received_sent, ByteSizeFormatter.format(it.mobileReceivedBytes), ByteSizeFormatter.format(it.mobileSentBytes)))
                    } ?: detail.networkDiagnostic?.let { InfoCard(it) }
                    Text(stringResource(R.string.ui_dangerous_permissions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (detail.requestedDangerousPermissions.isEmpty()) Text(stringResource(R.string.ui_none_declared_or_visible_to_android))
                    detail.requestedDangerousPermissions.forEach { permission ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Security, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(permission.shortName, fontWeight = FontWeight.Medium)
                                Text(permission.groupLabel ?: permission.permissionName, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (permission.granted) "Granted" else "Not granted", textAlign = TextAlign.End)
                        }
                    }
                    Text(stringResource(R.string.ui_android_authorized_actions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (detail.summary.mainActivityClassName != null) {
                        OutlinedButton(onClick = { onLaunch(detail.summary) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_launch))
                        }
                    }
                    OutlinedButton(onClick = { onSettings(detail.summary.packageName) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Settings, contentDescription = null); Text(stringResource(R.string.ui_app_settings), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { onNotifications(detail.summary.packageName) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Notifications, contentDescription = null); Text(stringResource(R.string.ui_notification_settings), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { onExport(detail) },
                        enabled = exportState !is ApkExportUiState.Working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Archive, contentDescription = null)
                        Text(if (exportState is ApkExportUiState.Working) "Exporting APK backup…" else "Export APK backup", Modifier.padding(start = 8.dp))
                    }
                    when (exportState) {
                        ApkExportUiState.Idle, ApkExportUiState.Working -> Unit
                        is ApkExportUiState.Message -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(exportState.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onDismissExport) { Text(stringResource(R.string.ui_dismiss)) }
                        }
                    }
                    if (!detail.summary.isSystem && detail.summary.packageName != ownPackageName) {
                        OutlinedButton(onClick = { onUninstall(detail.summary.packageName) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null); Text(stringResource(R.string.ui_request_uninstall), Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(stringResource(R.string.ui_apextuner_cannot_silently_clear_another_app_s_data_cach),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun CenteredStatus(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(text, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    ApexMetricRow(label = label, value = value)
}

private fun AppSort.displayName(): String = when (this) {
    AppSort.Name -> "Name"
    AppSort.ReviewPriority -> "Review priority"
    AppSort.LastUsed -> "Last used"
    AppSort.RecentlyUpdated -> "Updated"
    AppSort.PermissionExposure -> "Permissions"
    AppSort.TargetSdk -> "Target SDK"
}

private fun AppInsightFilter.displayName(): String = when (this) {
    AppInsightFilter.All -> "All"
    AppInsightFilter.ReviewRecommended -> "Review"
    AppInsightFilter.Unused30Days -> "Unused 30d"
    AppInsightFilter.PermissionHeavy -> "Permission-heavy"
    AppInsightFilter.RecentlyInstalled -> "Installed 30d"
    AppInsightFilter.RecentlyUpdated -> "Updated 30d"
    AppInsightFilter.LegacyTarget -> "Legacy target"
    AppInsightFilter.InstallerUnknown -> "Installer unknown"
}

private fun formatDateTime(value: Long): String = if (value <= 0L) "Unavailable" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

private fun Context.launchApp(app: AppSummary) {
    val cls = app.mainActivityClassName ?: return
    val launcher = getSystemService(android.content.pm.LauncherApps::class.java)
    runCatching { launcher.startMainActivity(ComponentName(app.packageName, cls), android.os.Process.myUserHandle(), null, Bundle.EMPTY) }
        .onFailure {
            packageManager.getLaunchIntentForPackage(app.packageName)?.let(::safeStart)
        }
}

private fun Context.openAppSettings(packageName: String) = safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

private fun Context.openNotificationSettings(packageName: String) = safeStart(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
)

private fun Context.safeStart(intent: Intent) {
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    } catch (_: SecurityException) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
