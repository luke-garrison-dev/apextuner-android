package com.apextuner.feature.network.diagnostics

import androidx.compose.ui.res.stringResource
import com.apextuner.feature.network.R
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: NetworkDiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("example.com") }
    var port by remember { mutableStateOf("443") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_network_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.ui_back)) }
                },
                actions = {
                    IconButton(onClick = viewModel::cancelAll) {
                        Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.ui_cancel_diagnostics))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.ui_diagnostics_run_only_when_requested_host_operations_are),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.take(253) },
                            label = { Text(stringResource(R.string.ui_host)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.ui_tcp_port)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.runPing(host) }) { Text(stringResource(R.string.ui_ping)) }
                            Button(onClick = { viewModel.resolveDns(host) }) { Text(stringResource(R.string.ui_dns)) }
                            Button(onClick = { viewModel.testTcp(host, port) }) { Text(stringResource(R.string.ui_tcp)) }
                        }
                        DiagnosticText(stringResource(R.string.ui_ping), state.ping)
                        DiagnosticText(stringResource(R.string.ui_dns), state.dns)
                        DiagnosticText(stringResource(R.string.ui_tcp), state.tcp)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.network_quality_lab), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.network_quality_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { viewModel.runQualityTest(host, port) }) { Text(stringResource(R.string.network_quality_run)) }
                        when (val quality = state.quality) {
                            DiagnosticRunState.Idle -> Unit
                            is DiagnosticRunState.Running -> Text(quality.message)
                            is DiagnosticRunState.Error -> Text(quality.message, color = MaterialTheme.colorScheme.error)
                            is DiagnosticRunState.Ready -> {
                                val value = quality.value
                                Text(stringResource(
                                    R.string.network_quality_summary,
                                    value.successes,
                                    value.attempts,
                                    value.medianLatencyMillis?.let { "$it ms" } ?: "—",
                                    value.p95LatencyMillis?.let { "$it ms" } ?: "—",
                                    value.jitterMillis?.let { String.format(Locale.getDefault(), "%.1f ms", it) } ?: "—",
                                    value.failurePercent,
                                ))
                                Text(stringResource(
                                    R.string.network_quality_dns,
                                    value.dnsLatencyMillis?.let { "$it ms" } ?: "—",
                                    value.ipv4Count,
                                    value.ipv6Count,
                                    if (value.networkMetered) "metered" else "unmetered",
                                ), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                value.ipv4Quality?.let { family ->
                                    Text(
                                        stringResource(
                                            R.string.network_quality_family,
                                            "IPv4",
                                            family.successes,
                                            family.attempts,
                                            family.medianLatencyMillis?.let { "$it ms" } ?: "—",
                                            family.p95LatencyMillis?.let { "$it ms" } ?: "—",
                                            family.jitterMillis?.let { String.format(Locale.getDefault(), "%.1f ms", it) } ?: "—",
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                value.ipv6Quality?.let { family ->
                                    Text(
                                        stringResource(
                                            R.string.network_quality_family,
                                            "IPv6",
                                            family.successes,
                                            family.attempts,
                                            family.medianLatencyMillis?.let { "$it ms" } ?: "—",
                                            family.p95LatencyMillis?.let { "$it ms" } ?: "—",
                                            family.jitterMillis?.let { String.format(Locale.getDefault(), "%.1f ms", it) } ?: "—",
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (value.preferredAddressFamily != null && value.endpointAddress != null) {
                                    Text(
                                        stringResource(R.string.network_quality_preferred, value.preferredAddressFamily, value.endpointAddress),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                value.diagnostic?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        if (state.qualityHistory.isNotEmpty()) {
                            Text(stringResource(R.string.network_quality_recent), style = MaterialTheme.typography.titleSmall)
                            state.qualityHistory.take(5).forEach { item ->
                                val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.capturedAtEpochMillis))
                                Text(
                                    whenText + " • " + stringResource(
                                        R.string.network_quality_history,
                                        item.host,
                                        item.port,
                                        item.successes,
                                        item.attempts,
                                        item.medianLatencyMillis?.let { "$it ms" } ?: "—",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.network_throughput_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.network_throughput_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = viewModel::runThroughputTest) { Text(stringResource(R.string.network_throughput_run)) }
                        when (val throughput = state.throughput) {
                            DiagnosticRunState.Idle -> Unit
                            is DiagnosticRunState.Running -> Text(throughput.message)
                            is DiagnosticRunState.Error -> Text(throughput.message, color = MaterialTheme.colorScheme.error)
                            is DiagnosticRunState.Ready -> {
                                val value = throughput.value
                                Text(
                                    stringResource(
                                        R.string.network_throughput_result,
                                        value.downloadMbps?.let { String.format(Locale.getDefault(), "%.1f Mbps", it) } ?: "—",
                                        value.uploadMbps?.let { String.format(Locale.getDefault(), "%.1f Mbps", it) } ?: "—",
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(value.assessment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.network_throughput_provider, value.provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                value.diagnostic?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Router, contentDescription = null)
                            Text(stringResource(R.string.ui_local_subnet_discovery), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.ui_apextuner_performs_a_conservative_reachability_sweep_an),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = viewModel::scanSubnet) { Text(stringResource(R.string.ui_scan_local_subnet)) }
                        when (val subnet = state.subnet) {
                            DiagnosticRunState.Idle -> Unit
                            is DiagnosticRunState.Running -> Text(subnet.message)
                            is DiagnosticRunState.Error -> Text(subnet.message, color = MaterialTheme.colorScheme.error)
                            is DiagnosticRunState.Ready -> {
                                Text(stringResource(R.string.diagnostics_subnet_summary, subnet.value.networkLabel, subnet.value.devices.size, subnet.value.hostsAttempted))
                                subnet.value.diagnostic?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            val devices = (state.subnet as? DiagnosticRunState.Ready)?.value?.devices.orEmpty()
            items(devices, key = { it.address }) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        device.latencyMillis?.let { latency ->
                            stringResource(R.string.diagnostics_device_latency, device.address, latency)
                        } ?: stringResource(R.string.diagnostics_device_reachable, device.address),
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticText(label: String, state: DiagnosticRunState<*>) {
    val text = when (state) {
        DiagnosticRunState.Idle -> return
        is DiagnosticRunState.Running -> "$label: ${state.message}"
        is DiagnosticRunState.Error -> "$label: ${state.message}"
        is DiagnosticRunState.Ready -> when (val value = state.value) {
            is PingResult -> "$label: ${if (value.reachable) "reachable" else "unreachable"} • ${value.elapsedMillis} ms"
            is DnsResult -> "$label: ${value.addresses.joinToString().ifBlank { value.diagnostic ?: "no result" }}"
            is TcpPortResult -> "$label: ${if (value.reachable) "open/reachable" else "not reachable"} • ${value.elapsedMillis} ms"
            else -> "$label: complete"
        }
    }
    Text(
        text,
        color = if (state is DiagnosticRunState.Error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
