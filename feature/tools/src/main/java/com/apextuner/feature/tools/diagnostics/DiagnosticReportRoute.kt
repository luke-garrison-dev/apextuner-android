package com.apextuner.feature.tools.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.tools.R
import java.util.Locale

@Composable
fun DiagnosticReportRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri, DiagnosticReportFormat.Json)
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) viewModel.export(uri, DiagnosticReportFormat.Html)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0f), contentColor = MaterialTheme.colorScheme.onBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Outlined.Assessment, contentDescription = null)
                    Column {
                        Text(stringResource(R.string.diagnostic_report_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.diagnostic_report_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.message?.let { message ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message)
                            TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.ui_dismiss)) }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.diagnostic_sections), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.diagnostic_sections_summary, state.selectedSections.size, DiagnosticReportSection.entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::selectAllSections,
                                enabled = state.selectedSections.size != DiagnosticReportSection.entries.size,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.diagnostic_select_all)) }
                            OutlinedButton(
                                onClick = viewModel::clearSections,
                                enabled = state.selectedSections.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.diagnostic_clear_sections)) }
                        }
                        DiagnosticReportSection.entries.forEach { section ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(section.displayName()) }
                                Checkbox(checked = section in state.selectedSections, onCheckedChange = { viewModel.toggleSection(section) })
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.diagnostic_before_after), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.diagnostic_before_after_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::captureBaseline, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.diagnostic_capture_baseline)) }
                            OutlinedButton(
                                onClick = viewModel::refreshCurrent,
                                enabled = !state.busy && state.baseline != null,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.diagnostic_refresh_after)) }
                        }
                        if (state.baseline == null) {
                            Text(
                                stringResource(R.string.diagnostic_no_baseline),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (state.comparison == null) {
                            Text(
                                stringResource(R.string.diagnostic_baseline_ready),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedButton(onClick = viewModel::clearBaseline, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diagnostic_clear_baseline)) }
                        }
                        state.comparison?.let { comparison ->
                            Text(
                                stringResource(R.string.diagnostic_comparison_window, formatElapsed(comparison.elapsedMillis)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ApexMetricRow(stringResource(R.string.diagnostic_battery_delta), comparison.batteryDeltaPercent?.let { String.format(Locale.US, "%+d%%", it) } ?: stringResource(R.string.security_unavailable))
                            ApexMetricRow(stringResource(R.string.diagnostic_memory_delta), signedBytes(comparison.memoryAvailableDeltaBytes))
                            ApexMetricRow(stringResource(R.string.diagnostic_storage_delta), signedBytes(comparison.storageAvailableDeltaBytes))
                            ApexMetricRow(stringResource(R.string.diagnostic_temperature_delta), comparison.batteryTemperatureDeltaCelsius?.let { String.format(Locale.US, "%+.1f °C", it) } ?: stringResource(R.string.security_unavailable))
                            ApexMetricRow(stringResource(R.string.diagnostic_network_delta), comparison.totalNetworkDeltaBytes()?.let(ByteSizeFormatter::format) ?: stringResource(R.string.security_unavailable))
                            Text(
                                stringResource(R.string.diagnostic_delta_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = viewModel::clearBaseline, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diagnostic_clear_baseline)) }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.diagnostic_export), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.diagnostic_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.selectedSections.isEmpty()) {
                            Text(stringResource(R.string.diagnostic_select_section_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = { jsonLauncher.launch("ApexTuner-diagnostic-report.json") }, enabled = !state.busy && state.selectedSections.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diagnostic_export_json)) }
                        OutlinedButton(onClick = { htmlLauncher.launch("ApexTuner-diagnostic-report.html") }, enabled = !state.busy && state.selectedSections.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diagnostic_export_html)) }
                    }
                }
            }
        }
    }
}

private fun DiagnosticReportSection.displayName(): String = when (this) {
    DiagnosticReportSection.Device -> "Device & thermal"
    DiagnosticReportSection.Battery -> "Battery"
    DiagnosticReportSection.MemoryStorage -> "Memory & storage"
    DiagnosticReportSection.Network -> "Network"
    DiagnosticReportSection.Security -> "Security"
    DiagnosticReportSection.History -> "Local trend history"
}

private fun signedBytes(value: Long): String = (if (value >= 0L) "+" else "−") + ByteSizeFormatter.format(if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value))


private fun formatElapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    return when {
        seconds < 60L -> "${seconds} s"
        seconds < 3_600L -> "${seconds / 60L} min"
        else -> {
            val hours = seconds / 3_600L
            val minutes = (seconds % 3_600L) / 60L
            if (minutes == 0L) "${hours} h" else "${hours} h ${minutes} min"
        }
    }
}
