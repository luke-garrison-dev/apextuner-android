package com.apextuner.feature.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.memory.model.MemoryUiState
import com.apextuner.feature.memory.model.ProcessInsight
import java.util.Locale

@Composable
fun MemoryRoute(
    onBack: (() -> Unit)? = null,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (val currentState = state) {
            MemoryUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.ui_reading_android_memory_signals))
            }
            is MemoryUiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(currentState.message)
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.ui_retry)) }
            }
            is MemoryUiState.Ready -> {
                val data = currentState.insights
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        if (onBack != null) OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Memory, contentDescription = null)
                            Column {
                                Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.memory_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.ui_system_ram), style = MaterialTheme.typography.titleLarge)
                                Metric("Used", "${ByteSizeFormatter.format(data.usedBytes)} / ${ByteSizeFormatter.format(data.totalBytes)}")
                                Metric("Available", ByteSizeFormatter.format(data.availableBytes))
                                Metric("Low-memory threshold", ByteSizeFormatter.format(data.thresholdBytes))
                                Metric("Android low-memory flag", if (data.lowMemory) "Yes" else "No")
                                Metric("Swap", if (data.swapTotalBytes != null && data.swapFreeBytes != null) {
                                    "${ByteSizeFormatter.format(data.swapTotalBytes - data.swapFreeBytes)} / ${ByteSizeFormatter.format(data.swapTotalBytes)}"
                                } else "Unavailable")
                                Metric("Memory pressure avg10", data.pressureSomeAvg10?.let { String.format(Locale.US, "%.2f%%", it) } ?: "Unavailable")
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.ui_apextuner_process), style = MaterialTheme.typography.titleLarge)
                                Metric("PSS", data.apexTunerPssBytes?.let(ByteSizeFormatter::format) ?: "Unavailable")
                                Metric("Private dirty", data.apexTunerPrivateDirtyBytes?.let(ByteSizeFormatter::format) ?: "Unavailable")
                                Metric("Native heap", ByteSizeFormatter.format(data.nativeHeapBytes))
                                Metric("Java heap", "${ByteSizeFormatter.format(data.javaHeapUsedBytes)} / ${ByteSizeFormatter.format(data.javaHeapMaxBytes)}")
                                Metric("Process importance", data.apexTunerImportance)
                                Metric("Last trim signal", data.apexTunerTrimLevel)
                            }
                        }
                    }
                    item { Text(stringResource(R.string.ui_memory_guidance), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                    items(data.recommendations) { recommendation -> InfoCard(recommendation) }
                    item {
                        Text(stringResource(R.string.ui_processes_reported_by_android), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ui_this_process_list_is_diagnostic_and_may_be_filtered_by),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(data.processes, key = { "${it.pid}:${it.processName}" }) { ProcessCard(it) }
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

@Composable
private fun ProcessCard(process: ProcessInsight) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val displayName = if (process.processName.isBlank()) stringResource(R.string.memory_unnamed_process) else process.processName
            val apexSuffix = if (process.isApexTuner) stringResource(R.string.memory_apextuner_suffix) else ""
            Text(displayName, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.memory_process_summary, process.pid, process.importance, apexSuffix))
            if (process.packageNames.isNotEmpty()) {
                Text(process.packageNames.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
