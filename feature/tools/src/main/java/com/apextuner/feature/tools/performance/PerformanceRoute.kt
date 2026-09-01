package com.apextuner.feature.tools.performance

import androidx.compose.ui.res.stringResource
import com.apextuner.feature.tools.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.model.SystemProfile
import java.util.Locale

@Composable
fun PerformanceRoute(
    onBack: (() -> Unit)? = null,
    viewModel: PerformanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var writeSettingsRefreshToken by remember { mutableIntStateOf(0) }
    val canWriteSystemSettings = remember(context, writeSettingsRefreshToken) { Settings.System.canWrite(context) }
    DisposableEffect(lifecycleOwner) {
        var leftForExternalSettings = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> leftForExternalSettings = true
                Lifecycle.Event.ON_RESUME -> if (leftForExternalSettings) {
                    leftForExternalSettings = false
                    writeSettingsRefreshToken += 1
                    viewModel.refresh()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (val currentState = state) {
            PerformanceUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.ui_reading_cpu_and_kernel_signals), modifier = Modifier.padding(top = 16.dp))
            }
            is PerformanceUiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(currentState.message)
                Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.ui_retry)) }
            }
            is PerformanceUiState.Ready -> {
                val data = currentState.insights
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        if (onBack != null) OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Speed, contentDescription = null)
                            Column {
                                Text(stringResource(R.string.ui_cpu_performance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.ui_read_only_hardware_truth_first_privileged_tuning_only_a), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    currentState.message?.let { message -> item { InfoCard(message) } }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Metric("CPU usage", data.cpuUsagePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Sampling…")
                                Metric("GPU utilization", data.gpuUsagePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Unavailable")
                                Metric("Thermal status", data.thermalStatus.name)
                                Metric("TCP congestion", data.tcpCongestionAlgorithm ?: "Unavailable")
                                Metric("Root binary detected", if (data.rootPotentiallyAvailable) "Potentially — not authorized" else "No")
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.ui_safe_profiles), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.ui_battery_applies_reversible_stock_android_settings_perfo),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SystemProfile.entries.chunked(2).forEach { rowProfiles ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        rowProfiles.forEach { profile ->
                                            FilterChip(
                                                selected = data.activeProfile == profile,
                                                onClick = { viewModel.applyProfile(profile) },
                                                label = { Text(profile.name) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                if (canWriteSystemSettings) {
                                    Text(
                                        stringResource(R.string.ui_modify_system_settings_granted),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = { context.launchSafely(writeSettingsIntent(context)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.ui_modify_system_settings_access)) }
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.ui_cpu_cores), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                    items(data.cores, key = { it.core }) { core ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.performance_core, core.core), fontWeight = FontWeight.SemiBold)
                                Metric(stringResource(R.string.performance_current), core.currentKhz?.let { stringResource(R.string.performance_mhz, it / 1000) } ?: stringResource(R.string.performance_unavailable))
                                Metric(stringResource(R.string.performance_range), if (core.minKhz != null && core.maxKhz != null) stringResource(R.string.performance_mhz_range, core.minKhz / 1000, core.maxKhz / 1000) else stringResource(R.string.performance_unavailable))
                                Metric("Governor", core.governor ?: "Unavailable")
                            }
                        }
                    }
                    if (data.ioSchedulers.isNotEmpty()) {
                        item { Text(stringResource(R.string.ui_i_o_schedulers), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                        items(data.ioSchedulers) { scheduler -> InfoCard(scheduler) }
                    }
                    item { Text(stringResource(R.string.ui_guidance), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                    items(data.recommendations) { recommendation -> InfoCard(recommendation) }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    ApexMetricRow(label = label, value = value)
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(text, modifier = Modifier.weight(1f))
        }
    }
}

private fun writeSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_WRITE_SETTINGS,
    Uri.parse("package:${context.packageName}"),
)

private fun Context.launchSafely(intent: Intent) {
    val fallback = Intent(Settings.ACTION_SETTINGS)
    val target = intent.takeIf { it.resolveActivity(packageManager) != null } ?: fallback
    runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            if (target.action != fallback.action) {
                runCatching { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
}
