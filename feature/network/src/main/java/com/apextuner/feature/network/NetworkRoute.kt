package com.apextuner.feature.network

import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.system.ForegroundServiceLaunchResult
import com.apextuner.core.system.ForegroundServiceLauncher
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.network.firewall.ApexFirewallVpnService
import com.apextuner.feature.network.firewall.FirewallRuntimeRegistry

@Composable
fun NetworkRoute(
    onBack: (() -> Unit)? = null,
    premiumEnabled: Boolean = false,
    onUpgrade: () -> Unit = {},
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var firewallSearch by rememberSaveable { mutableStateOf("") }
    var dataCapSearch by rememberSaveable { mutableStateOf("") }
    var pendingFirewallStart by remember { mutableStateOf(false) }
    var showFirewallDisclosure by rememberSaveable { mutableStateOf(false) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (pendingFirewallStart) {
            pendingFirewallStart = false
            if (result.resultCode == Activity.RESULT_OK) startFirewallSafely(context)
            else FirewallRuntimeRegistry.update(FirewallRuntimeState.Error, error = "Android VPN permission was not granted.")
        }
    }
    val requestVpnStart = {
        try {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent == null) {
                startFirewallSafely(context)
            } else {
                pendingFirewallStart = true
                vpnConsent.launch(prepareIntent)
            }
        } catch (error: Throwable) {
            FirewallRuntimeRegistry.update(FirewallRuntimeState.Error, error = error.message ?: "Android could not open VPN consent.")
        }
    }

    if (showFirewallDisclosure) {
        AlertDialog(
            onDismissRequest = { showFirewallDisclosure = false },
            title = { Text(stringResource(R.string.ui_how_the_local_firewall_works)) },
            text = {
                Text(stringResource(R.string.ui_apextuner_creates_a_local_android_vpn_only_for_apps_you),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(onClick = {
                    showFirewallDisclosure = false
                    requestVpnStart()
                }) { Text(stringResource(R.string.ui_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showFirewallDisclosure = false }) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (val current = state) {
            NetworkUiState.Loading -> CenteredStatus("Reading network state…", showProgress = true)
            is NetworkUiState.Error -> CenteredStatus(current.message, action = "Retry", onAction = viewModel::refresh)
            is NetworkUiState.Ready -> {
                val snapshot = current.snapshot
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        if (onBack != null) OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Lan, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ui_network_firewall), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.ui_connectivity_truth_historical_usage_and_a_local_per_app),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = viewModel::refresh, enabled = !current.refreshing) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Text(if (current.refreshing) "Refreshing…" else "Refresh", Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    current.message?.let { item { InfoCard(it) } }
                    item { ActiveNetworkCard(snapshot.activeNetwork) }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Dns, contentDescription = null)
                                    Text(stringResource(R.string.ui_privacy_networking), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                Metric("Private DNS", when (snapshot.activeNetwork.privateDnsActive) {
                                    true -> snapshot.activeNetwork.privateDnsServerName ?: "Active (automatic/provider not disclosed)"
                                    false -> "Not active on this network"
                                    null -> "Unavailable on this Android version"
                                })
                                Metric("ApexTuner Data Saver status", when (snapshot.apexTunerDataSaverRestricted) {
                                    true -> "Background data restricted"
                                    false -> "Not restricted"
                                    null -> "Unavailable"
                                })
                                Text(stringResource(R.string.ui_the_data_saver_value_applies_to_apextuner_itself_androi),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = { context.openSettingsSafely(Settings.ACTION_DATA_USAGE_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_open_data_usage_settings)) }
                                OutlinedButton(onClick = { context.openSettingsSafely(Settings.ACTION_WIRELESS_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_open_network_settings)) }
                            }
                        }
                    }
                    item { HistoricalUsageCard(snapshot, context) }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.ui_monthly_per_app_data_alerts), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.ui_set_local_alert_thresholds_workmanager_checks_bounded_m),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!snapshot.usageAccessGranted) {
                                    Text(
                                        if (snapshot.monthlyDataCaps.isNotEmpty()) {
                                            stringResource(R.string.data_usage_alerts_paused_usage_access)
                                        } else {
                                            stringResource(R.string.ui_usage_access_is_required_before_android_exposes_cross_a)
                                        },
                                        color = if (snapshot.monthlyDataCaps.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = { context.openSettingsSafely(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.ui_grant_optional_usage_access)) }
                                } else {
                                    OutlinedTextField(
                                        value = dataCapSearch,
                                        onValueChange = { dataCapSearch = it.take(80) },
                                        label = { Text(stringResource(R.string.ui_search_installed_apps)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    if (snapshot.usageAccessGranted) {
                        val capApps = snapshot.firewallApps.asSequence()
                            .filter { app ->
                                app.packageName in snapshot.monthlyDataCaps ||
                                    (dataCapSearch.isNotBlank() &&
                                        (app.label.contains(dataCapSearch, true) || app.packageName.contains(dataCapSearch, true)))
                            }
                            .take(30)
                            .toList()
                        if (capApps.isEmpty()) {
                            item {
                                InfoCard(
                                    if (dataCapSearch.isBlank()) "Search for an app to create a monthly data alert."
                                    else "No matching launcher app was found.",
                                )
                            }
                        } else {
                            items(capApps, key = { "cap:${it.packageName}" }) { app ->
                                DataCapAppCard(
                                    app = app,
                                    capBytes = snapshot.monthlyDataCaps[app.packageName],
                                    usageBytes = snapshot.monthlyDataCapUsage[app.packageName],
                                    onSave = { megabytes -> viewModel.setMonthlyDataCap(app.packageName, megabytes) },
                                )
                            }
                        }
                    }
                    snapshot.usage?.topApps?.takeIf { it.isNotEmpty() }?.let { rows ->
                        item { Text(stringResource(R.string.ui_top_historical_data_users), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                        items(rows, key = { it.uid }) { row -> AppUsageCard(row) }
                        item {
                            InfoCard("Shared Linux UIDs can combine traffic from multiple packages. ApexTuner labels visible members instead of pretending Android reported package-level attribution when it did not.")
                        }
                    }
                    if (!premiumEnabled) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(stringResource(R.string.ui_local_per_app_firewall_premium), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.ui_block_network_access_for_selected_apps_with_apextuner_s))
                                    Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_unlock_firewall)) }
                                }
                            }
                        }
                    } else {
                        item {
                            FirewallHeader(
                                snapshot = snapshot,
                                context = context,
                                onStart = {
                                    val selected = snapshot.firewallStatus.selectedPackages
                                    if (selected.isEmpty()) {
                                        FirewallRuntimeRegistry.update(FirewallRuntimeState.Error, error = "Select at least one app before starting the firewall.")
                                    } else {
                                        showFirewallDisclosure = true
                                    }
                                },
                                onStop = { stopFirewallSafely(context) },
                                onProfile = viewModel::setFirewallProfile,
                                search = firewallSearch,
                                onSearchChanged = { firewallSearch = it.take(80) },
                            )
                        }
                        val matchingFirewallApps = snapshot.firewallApps.asSequence()
                            .filter { firewallSearch.isBlank() || it.label.contains(firewallSearch, true) || it.packageName.contains(firewallSearch, true) }
                            .sortedWith(compareByDescending<FirewallApp> { it.selected }.thenBy { it.label.lowercase() })
                            .toList()
                        items(matchingFirewallApps, key = { it.packageName }) { app ->
                            FirewallAppCard(
                                app = app,
                                enabled = snapshot.firewallStatus.runtimeState !in setOf(FirewallRuntimeState.Active, FirewallRuntimeState.Starting),
                                onToggle = { selected -> viewModel.setFirewallPackageSelected(app.packageName, selected) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActiveNetworkCard(data: ActiveNetworkInsight) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_active_connection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Metric("Status", if (data.connected) "Connected" else "No active network")
            Metric("Transport", data.transports.joinToString().ifBlank { "Unavailable" })
            Metric("Internet validated", if (data.validated) "Yes" else "No")
            Metric("Captive portal", if (data.captivePortal) "Detected" else "No")
            Metric("Metered", if (data.metered) "Yes" else "No")
            Metric("VPN transport", if (data.vpnTransport) "Yes" else "No")
            Metric("Interface", data.interfaceName ?: "Unavailable")
            Metric("MTU", data.mtu?.toString() ?: "Unavailable")
            Metric("DNS", data.dnsServers.joinToString().ifBlank { "Unavailable" })
        }
    }
}

@Composable
private fun HistoricalUsageCard(snapshot: NetworkSnapshot, context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ui_historical_data_usage), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val usage = snapshot.usage
            if (usage == null) {
                Text(snapshot.usageDiagnostic ?: "Usage history is unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!snapshot.usageAccessGranted) {
                    Button(onClick = { context.openSettingsSafely(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_grant_optional_usage_access)) }
                }
            } else {
                Metric("Window", "Last ${usage.periodDays} days")
                Metric("Wi‑Fi", "↓ ${ByteSizeFormatter.format(usage.wifiReceivedBytes)}  ↑ ${ByteSizeFormatter.format(usage.wifiSentBytes)}")
                Metric("Mobile", "↓ ${ByteSizeFormatter.format(usage.mobileReceivedBytes)}  ↑ ${ByteSizeFormatter.format(usage.mobileSentBytes)}")
                Metric("Observed total", ByteSizeFormatter.format(usage.totalBytes))
                Text(stringResource(R.string.ui_android_reports_usage_in_coarse_historical_buckets_thes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppUsageCard(row: AppNetworkUsageRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(ByteSizeFormatter.format(row.totalBytes), fontWeight = FontWeight.Medium)
            }
            if (row.packages.isNotEmpty()) {
                Text(
                    row.packages.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.network_usage_row_bytes, ByteSizeFormatter.format(row.receivedBytes), ByteSizeFormatter.format(row.sentBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DataCapAppCard(
    app: FirewallApp,
    capBytes: Long?,
    usageBytes: Long?,
    onSave: (String) -> Unit,
) {
    var capMegabytes by rememberSaveable(app.packageName, capBytes) {
        mutableStateOf(capBytes?.div(1024L * 1024L)?.toString().orEmpty())
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(app.label, fontWeight = FontWeight.SemiBold)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (capBytes != null) {
                val usageText = if (usageBytes == null) {
                    "Current-month usage unavailable"
                } else {
                    val percent = dataCapUsagePercent(usageBytes, capBytes) ?: 0.0
                    "This month: ${ByteSizeFormatter.format(usageBytes)} of ${ByteSizeFormatter.format(capBytes)} (${String.format(java.util.Locale.getDefault(), "%.0f%%", percent)})"
                }
                Text(usageText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = capMegabytes,
                onValueChange = { capMegabytes = it.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(R.string.ui_monthly_threshold_mb)) },
                supportingText = { Text(stringResource(R.string.ui_leave_blank_and_save_to_remove)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onSave(capMegabytes) }) {
                Text(if (capBytes == null) "Save alert" else "Update / remove alert")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirewallHeader(
    snapshot: NetworkSnapshot,
    context: Context,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProfile: (FirewallProfile) -> Unit,
    search: String,
    onSearchChanged: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Security, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_local_per_app_firewall), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ui_selected_apps_only_packets_dropped_locally), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Metric("State", snapshot.firewallStatus.runtimeState.name)
            Metric("Selected apps", snapshot.firewallStatus.selectedPackages.size.toString())
            Text(stringResource(R.string.firewall_profiles), fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FirewallProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = snapshot.firewallStatus.profile == profile,
                        onClick = { onProfile(profile) },
                        enabled = snapshot.firewallStatus.runtimeState !in setOf(FirewallRuntimeState.Active, FirewallRuntimeState.Starting),
                        label = { Text(profile.displayName()) },
                    )
                }
            }
            Text(stringResource(R.string.firewall_profiles_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.firewallStatus.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.ui_apextuner_s_firewall_is_a_user_approved_local_vpn_sink),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.firewallStatus.runtimeState == FirewallRuntimeState.Active || snapshot.firewallStatus.runtimeState == FirewallRuntimeState.Starting) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_stop_firewall)) }
            } else {
                Button(onClick = onStart, enabled = snapshot.firewallStatus.selectedPackages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_start_firewall)) }
            }
            OutlinedButton(onClick = { context.openSettingsSafely(Settings.ACTION_VPN_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_android_vpn_settings)) }
            androidx.compose.material3.OutlinedTextField(
                value = search,
                onValueChange = onSearchChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.ui_filter_visible_apps)) },
            )
        }
    }
}

@Composable
private fun FirewallAppCard(app: FirewallApp, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (app.selected) Icons.Outlined.CloudOff else Icons.Outlined.Lan, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = app.selected, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

private fun FirewallProfile.displayName(): String = when (this) {
    FirewallProfile.HomeWifi -> "Home Wi‑Fi"
    FirewallProfile.MobileData -> "Mobile data"
    FirewallProfile.PublicWifi -> "Public Wi‑Fi"
}

@Composable
private fun Metric(label: String, value: String) {
    ApexMetricRow(label = label, value = value)
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
private fun CenteredStatus(text: String, showProgress: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (showProgress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun startFirewallSafely(context: Context) {
    val intent = Intent(context, ApexFirewallVpnService::class.java).setAction(ApexFirewallVpnService.ACTION_START)
    if (ForegroundServiceLauncher.start(context, intent) != ForegroundServiceLaunchResult.Started) {
        FirewallRuntimeRegistry.update(
            FirewallRuntimeState.Error,
            error = "Android could not start the firewall service. Reopen ApexTuner and try again.",
        )
    }
}

private fun stopFirewallSafely(context: Context) {
    val intent = Intent(context, ApexFirewallVpnService::class.java).setAction(ApexFirewallVpnService.ACTION_STOP)
    runCatching { context.startService(intent) }
        .onFailure {
            runCatching { context.stopService(Intent(context, ApexFirewallVpnService::class.java)) }
            FirewallRuntimeRegistry.update(FirewallRuntimeState.Stopped)
        }
}

private fun Context.openSettingsSafely(action: String) {
    val requested = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        if (requested.resolveActivity(packageManager) != null) startActivity(requested)
        else startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    } catch (_: SecurityException) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
