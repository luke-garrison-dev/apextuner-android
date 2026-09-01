package com.apextuner.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.CpuUsageAvailability
import com.apextuner.core.model.PluggedSource
import com.apextuner.core.model.ThermalStatus
import com.apextuner.core.ui.ApexBrandMark
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.apexAccentPalette
import com.apextuner.core.util.ByteSizeFormatter
import com.apextuner.feature.dashboard.model.DashboardChartMetric
import com.apextuner.feature.dashboard.model.DashboardData
import com.apextuner.feature.dashboard.model.DashboardHistoryPoint
import com.apextuner.feature.dashboard.model.DashboardRecommendation
import com.apextuner.feature.dashboard.model.DashboardUiState
import com.apextuner.feature.dashboard.model.HealthTimelineRange
import com.apextuner.feature.dashboard.model.HealthTimelineData
import com.apextuner.feature.dashboard.model.RecommendationSeverity
import com.apextuner.feature.dashboard.model.RecommendationType
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max


@Composable
fun DashboardRoute(
    onRecommendation: (RecommendationType) -> Unit = {},
    onOpenIntelligence: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onRetry = viewModel::retry,
        onTimelineRangeSelected = viewModel::selectTimelineRange,
        onRecommendation = onRecommendation,
        onOpenIntelligence = onOpenIntelligence,
    )
}

@Composable
internal fun DashboardScreen(
    state: DashboardUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onTimelineRangeSelected: (HealthTimelineRange) -> Unit = {},
    onRecommendation: (RecommendationType) -> Unit = {},
    onOpenIntelligence: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (state) {
            DashboardUiState.Loading -> DashboardLoading()
            DashboardUiState.Error -> DashboardError(onRetry = onRetry)
            is DashboardUiState.Ready -> DashboardContent(
                data = state.data,
                history = state.history,
                timeline = state.timeline,
                onTimelineRangeSelected = onTimelineRangeSelected,
                onRecommendation = onRecommendation,
                onOpenIntelligence = onOpenIntelligence,
            )
        }
    }
}

@Composable
private fun DashboardLoading() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DashboardHero() }
        item {
            Text(
                text = stringResource(R.string.dashboard_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        items(4) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(118.dp))
            }
        }
    }
}

@Composable
private fun DashboardError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dashboard_error_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.dashboard_error_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.dashboard_retry)) }
    }
}

@Composable
private fun DashboardContent(
    data: DashboardData,
    history: List<DashboardHistoryPoint>,
    timeline: HealthTimelineData,
    onTimelineRangeSelected: (HealthTimelineRange) -> Unit,
    onRecommendation: (RecommendationType) -> Unit,
    onOpenIntelligence: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DashboardHero() }
        item { DashboardHeader(data) }
        item { IntelligenceEntryCard(onOpenIntelligence) }
        item { MetricGrid(data) }
        data.batteryTemperatureCelsius?.let { temperature ->
            item { TemperatureCard(temperature = temperature, thermalStatus = data.thermalStatus) }
        }
        item { LiveChartCard(data = data, history = history) }
        item { HealthTimelineCard(timeline, onTimelineRangeSelected) }
        item { SystemSignalsCard(data) }
        if (data.recommendations.isNotEmpty()) {
            item { RecommendationsCard(data.recommendations, onRecommendation) }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun DashboardHero() {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ApexBrandMark(
                    modifier = Modifier.height(if (compact) 48.dp else 62.dp),
                )
                Text(
                    text = "ApexTuner",
                    style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Optimize at a ") }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append("glance") }
                },
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Live CPU, memory, battery and storage insight.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun IntelligenceEntryCard(onOpenIntelligence: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.98f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dashboard_intelligence_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.dashboard_intelligence_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenIntelligence) { Text(stringResource(R.string.dashboard_intelligence_open)) }
        }
    }
}

@Composable
private fun DashboardHeader(data: DashboardData) {
    val timeFormatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val formattedTime = remember(data.capturedAtEpochMillis) {
        timeFormatter.format(Date(data.capturedAtEpochMillis))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.98f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Text(stringResource(R.string.dashboard_live).uppercase(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            modifier = Modifier.padding(9.dp).size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Live data is sampled locally on this device.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Your privacy stays protected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.dashboard_updated, formattedTime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(data: DashboardData) {
    val fontScale = LocalDensity.current.fontScale
    val compactLandscape = ApexLayout.isCompactLandscape()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fourColumns = maxWidth >= 900.dp && fontScale <= 1.15f && !compactLandscape
        val twoColumns = maxWidth >= 620.dp && !compactLandscape
        val phonePortraitTwoColumns = maxWidth >= 352.dp && fontScale <= 1.18f && !compactLandscape
        when {
            fourColumns -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) { CpuMetricCard(data) }
                Box(Modifier.weight(1f)) { MemoryMetricCard(data) }
                Box(Modifier.weight(1f)) { BatteryMetricCard(data) }
                Box(Modifier.weight(1f)) { StorageMetricCard(data) }
            }
            twoColumns || phonePortraitTwoColumns -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.weight(1f)) { CpuMetricCard(data) }
                    Box(Modifier.weight(1f)) { MemoryMetricCard(data) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.weight(1f)) { BatteryMetricCard(data) }
                    Box(Modifier.weight(1f)) { StorageMetricCard(data) }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CpuMetricCard(data)
                MemoryMetricCard(data)
                BatteryMetricCard(data)
                StorageMetricCard(data)
            }
        }
    }
}

@Composable
private fun CpuMetricCard(data: DashboardData) {
    val accent = apexAccentPalette().cyan
    val coreText = stringResource(R.string.dashboard_cores, data.cpuCoreCount)
    val frequencyText = data.cpuAverageFrequencyMhz?.let(::formatFrequency)
    val statusDetail = when (data.cpuUsageAvailability) {
        CpuUsageAvailability.RestrictedByPlatform -> stringResource(R.string.dashboard_cpu_restricted)
        CpuUsageAvailability.Unavailable -> stringResource(R.string.dashboard_cpu_unavailable_detail)
        CpuUsageAvailability.Available,
        CpuUsageAvailability.Sampling -> null
    }
    val secondary = when {
        statusDetail != null && frequencyText != null -> stringResource(
            R.string.dashboard_cpu_status_with_frequency,
            frequencyText,
            statusDetail,
        )
        statusDetail != null -> statusDetail
        frequencyText != null -> stringResource(
            R.string.dashboard_cores_frequency,
            data.cpuCoreCount,
            frequencyText,
        )
        else -> coreText
    }
    val primary = data.cpuUsagePercent?.let(::formatPercent) ?: when (data.cpuUsageAvailability) {
        CpuUsageAvailability.Sampling -> stringResource(R.string.dashboard_cpu_sampling)
        else -> coreText
    }
    MetricRingCard(
        title = stringResource(R.string.dashboard_cpu),
        primary = primary,
        secondary = secondary,
        progress = data.cpuUsagePercent?.div(100.0),
        icon = Icons.Outlined.DeveloperBoard,
        accent = accent,
    )
}

@Composable
private fun MemoryMetricCard(data: DashboardData) {
    val accent = apexAccentPalette().violet
    MetricRingCard(
        title = stringResource(R.string.dashboard_memory),
        primary = formatPercent(data.memoryUsedFraction * 100.0),
        secondary = stringResource(
            R.string.dashboard_used_of_total,
            ByteSizeFormatter.format(data.memoryUsedBytes),
            ByteSizeFormatter.format(data.memoryTotalBytes),
        ),
        progress = data.memoryUsedFraction,
        icon = Icons.Outlined.Memory,
        accent = accent,
    )
}

@Composable
private fun StorageMetricCard(data: DashboardData) {
    val accent = apexAccentPalette().blue
    MetricRingCard(
        title = stringResource(R.string.dashboard_storage),
        primary = formatPercent(data.internalStorageUsedFraction * 100.0),
        secondary = stringResource(
            R.string.dashboard_used_of_total,
            ByteSizeFormatter.format(data.internalStorageUsedBytes),
            ByteSizeFormatter.format(data.internalStorageTotalBytes),
        ),
        progress = data.internalStorageUsedFraction,
        icon = Icons.Outlined.Storage,
        accent = accent,
    )
}

@Composable
private fun BatteryMetricCard(data: DashboardData) {
    val accent = apexAccentPalette().green
    val health = formatBatteryHealth(data.batteryHealth)
    MetricRingCard(
        title = stringResource(R.string.dashboard_battery),
        primary = data.batteryLevelPercent?.let { "$it%" } ?: stringResource(R.string.dashboard_unavailable),
        secondary = if (data.batteryCharging) stringResource(R.string.dashboard_charging_health, health) else health,
        progress = data.batteryLevelPercent?.div(100.0),
        icon = Icons.Outlined.BatteryChargingFull,
        accent = accent,
    )
}

@Composable
private fun MetricRingCard(
    title: String,
    primary: String,
    secondary: String,
    progress: Double?,
    icon: ImageVector,
    accent: Color,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 126.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.16f)),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).size(21.dp),
                            tint = accent,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = primary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp)) {
                CircularProgressIndicator(
                    progress = { (progress ?: 0.0).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.size(50.dp),
                    color = if (progress == null) MaterialTheme.colorScheme.outlineVariant else accent,
                    trackColor = accent.copy(alpha = 0.12f),
                    strokeWidth = 6.dp,
                )
            }
        }
    }
}

@Composable
private fun TemperatureCard(temperature: Double, thermalStatus: ThermalStatus) {
    val accents = apexAccentPalette()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = accents.cyan.copy(alpha = 0.12f), border = BorderStroke(1.dp, accents.cyan.copy(alpha = 0.16f))) {
                Icon(
                    imageVector = Icons.Outlined.Thermostat,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(24.dp),
                    tint = accents.cyan,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.dashboard_battery_temperature), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = String.format(Locale.getDefault(), "%.1f °C", temperature),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.dashboard_thermal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatThermal(thermalStatus), style = MaterialTheme.typography.labelLarge, color = thermalAccent(thermalStatus))
            }
        }
    }
}

@Composable
private fun LiveChartCard(
    data: DashboardData,
    history: List<DashboardHistoryPoint>,
) {
    var selectedMetric by remember { mutableStateOf(DashboardChartMetric.Cpu) }
    val values = history.map { point ->
        when (selectedMetric) {
            DashboardChartMetric.Cpu -> point.cpuUsagePercent
            DashboardChartMetric.Memory -> point.memoryUsedPercent
            DashboardChartMetric.Download -> point.downloadBytesPerSecond?.toDouble()
            DashboardChartMetric.Upload -> point.uploadBytesPerSecond?.toDouble()
        }
    }
    val fixedMax = when (selectedMetric) {
        DashboardChartMetric.Cpu, DashboardChartMetric.Memory -> 100.0
        DashboardChartMetric.Download, DashboardChartMetric.Upload -> null
    }
    val latest = values.lastOrNull { it != null }
    val currentValueAvailable = selectedMetric != DashboardChartMetric.Cpu ||
        (data.cpuUsageAvailability == CpuUsageAvailability.Available && data.cpuUsagePercent != null)
    val displayedLatest = latest.takeIf { currentValueAvailable }
    val availabilityNote = when {
        selectedMetric == DashboardChartMetric.Cpu &&
            data.cpuUsageAvailability == CpuUsageAvailability.RestrictedByPlatform ->
            stringResource(R.string.dashboard_chart_cpu_restricted)
        selectedMetric == DashboardChartMetric.Cpu &&
            data.cpuUsageAvailability == CpuUsageAvailability.Unavailable ->
            stringResource(R.string.dashboard_chart_cpu_unavailable)
        else -> null
    }
    val label = chartMetricLabel(selectedMetric)
    val accent = chartMetricAccent(selectedMetric)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.Outlined.DeveloperBoard, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
                Text(
                    text = stringResource(R.string.dashboard_live_history),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = displayedLatest?.let { formatChartValue(selectedMetric, it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(DashboardChartMetric.entries, key = { it.name }) { metric ->
                    FilterChip(
                        selected = selectedMetric == metric,
                        onClick = { selectedMetric = metric },
                        label = { Text(chartMetricLabel(metric)) },
                    )
                }
            }
            MetricLineChart(
                values = values,
                fixedMaximum = fixedMax,
                description = stringResource(R.string.dashboard_chart_description, label),
                lineColor = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp),
            )
            Text(
                text = availabilityNote ?: if (displayedLatest == null) {
                    stringResource(R.string.dashboard_waiting_sample)
                } else {
                    stringResource(R.string.dashboard_history_note, history.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricLineChart(
    values: List<Double?>,
    fixedMaximum: Double?,
    description: String,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val usable = values.mapNotNull { it?.takeIf { value -> value.isFinite() } }
    val maximum = fixedMaximum ?: max(usable.maxOrNull() ?: 1.0, 1.0)

    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val horizontalPadding = 4.dp.toPx()
        val verticalPadding = 8.dp.toPx()
        val chartWidth = (size.width - horizontalPadding * 2).coerceAtLeast(1f)
        val chartHeight = (size.height - verticalPadding * 2).coerceAtLeast(1f)

        repeat(4) { index ->
            val y = verticalPadding + chartHeight * index / 3f
            drawLine(
                color = gridColor.copy(alpha = 0.72f),
                start = Offset(horizontalPadding, y),
                end = Offset(size.width - horizontalPadding, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            )
        }

        if (values.size < 2) return@Canvas
        var previousPoint: Offset? = null
        values.forEachIndexed { index, raw ->
            val value = raw?.takeIf { it.isFinite() }
            if (value == null) {
                previousPoint = null
                return@forEachIndexed
            }
            val x = horizontalPadding + chartWidth * index.toFloat() / values.lastIndex.coerceAtLeast(1).toFloat()
            val normalized = (value / maximum).coerceIn(0.0, 1.0)
            val y = verticalPadding + chartHeight * (1f - normalized.toFloat())
            val point = Offset(x, y)
            previousPoint?.let { previous ->
                drawLine(
                    color = lineColor.copy(alpha = 0.16f),
                    start = previous,
                    end = point,
                    strokeWidth = 8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = lineColor,
                    start = previous,
                    end = point,
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            previousPoint = point
        }
        previousPoint?.let { point ->
            drawCircle(color = lineColor, radius = 4.5.dp.toPx(), center = point)
        }
    }
}

private data class SignalItem(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val accent: Color,
)

@Composable
private fun HealthTimelineCard(
    timeline: HealthTimelineData,
    onRangeSelected: (HealthTimelineRange) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_health_timeline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.dashboard_health_timeline_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HealthTimelineRange.entries) { range ->
                    FilterChip(
                        selected = timeline.range == range,
                        onClick = { onRangeSelected(range) },
                        label = { Text(range.displayName) },
                    )
                }
            }
            if (timeline.points.isEmpty()) {
                Text(
                    stringResource(R.string.dashboard_health_timeline_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val summary = timeline.summary
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 580.dp
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TimelineMetric(stringResource(R.string.dashboard_timeline_samples), summary.sampleCount.toString(), Modifier.weight(1f))
                            TimelineMetric(stringResource(R.string.dashboard_timeline_avg_cpu), summary.averageCpuUsagePercent?.let(::formatPercent) ?: "—", Modifier.weight(1f))
                            TimelineMetric(stringResource(R.string.dashboard_timeline_avg_memory), summary.averageMemoryUsedPercent?.let(::formatPercent) ?: "—", Modifier.weight(1f))
                            TimelineMetric(stringResource(R.string.dashboard_timeline_peak_battery), summary.maximumBatteryTemperatureCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "—", Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimelineMetric(stringResource(R.string.dashboard_timeline_samples), summary.sampleCount.toString(), Modifier.weight(1f))
                                TimelineMetric(stringResource(R.string.dashboard_timeline_avg_cpu), summary.averageCpuUsagePercent?.let(::formatPercent) ?: "—", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimelineMetric(stringResource(R.string.dashboard_timeline_avg_memory), summary.averageMemoryUsedPercent?.let(::formatPercent) ?: "—", Modifier.weight(1f))
                                TimelineMetric(stringResource(R.string.dashboard_timeline_peak_battery), summary.maximumBatteryTemperatureCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "—", Modifier.weight(1f))
                            }
                        }
                    }
                }
                val temperaturePoints = timeline.points.mapNotNull { point ->
                    point.batteryTemperatureCelsius?.let { point.capturedAtEpochMillis to it }
                }
                if (temperaturePoints.size >= 2) {
                    val minTemp = temperaturePoints.minOf { it.second }
                    val maxTemp = temperaturePoints.maxOf { it.second }
                    val tempSpan = (maxTemp - minTemp).coerceAtLeast(1.0)
                    val accent = MaterialTheme.colorScheme.primary
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(112.dp)
                            .semantics { contentDescription = "Battery temperature trend for ${timeline.range.displayName}" },
                    ) {
                        val count = temperaturePoints.size
                        temperaturePoints.zipWithNext().forEachIndexed { index, pair ->
                            val startX = size.width * index.toFloat() / (count - 1).toFloat()
                            val endX = size.width * (index + 1).toFloat() / (count - 1).toFloat()
                            val startY = size.height * (1f - ((pair.first.second - minTemp) / tempSpan).toFloat())
                            val endY = size.height * (1f - ((pair.second.second - minTemp) / tempSpan).toFloat())
                            drawLine(accent, Offset(startX, startY), Offset(endX, endY), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    Text(
                        stringResource(R.string.dashboard_health_timeline_temperature, minTemp, maxTemp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.dashboard_timeline_insights), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                summary.storageFreeDeltaBytes?.let { delta ->
                    val magnitude = ByteSizeFormatter.format(kotlin.math.abs(delta))
                    Text(
                        stringResource(
                            if (delta >= 0L) R.string.dashboard_timeline_storage_gained else R.string.dashboard_timeline_storage_lost,
                            magnitude,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.elevatedThermalEvents > 0) {
                    Text(
                        stringResource(R.string.dashboard_timeline_thermal_events, summary.elevatedThermalEvents, summary.elevatedThermalSamples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.highCpuHotBatterySamples > 0) {
                    Text(
                        stringResource(R.string.dashboard_timeline_cpu_heat_overlap, summary.highCpuHotBatterySamples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                summary.meteredSamplePercent?.let { meteredPercent ->
                    Text(
                        stringResource(
                            R.string.dashboard_timeline_network_observation,
                            meteredPercent,
                            summary.observedTrafficBytes?.let(ByteSizeFormatter::format) ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                summary.minimumStorageFreeBytes?.let { minimum ->
                    Text(
                        stringResource(R.string.dashboard_health_timeline_storage, ByteSizeFormatter.format(minimum)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SystemSignalsCard(data: DashboardData) {
    val accents = apexAccentPalette()
    val thermalAccent = thermalAccent(data.thermalStatus)
    val signals = buildList {
        add(SignalItem(Icons.Outlined.NetworkCheck, stringResource(R.string.dashboard_network), networkSummary(data), accents.cyan))
        add(SignalItem(Icons.Outlined.Thermostat, stringResource(R.string.dashboard_thermal), formatThermal(data.thermalStatus), thermalAccent))
        add(SignalItem(Icons.Outlined.Download, stringResource(R.string.dashboard_download), data.downloadBytesPerSecond?.let(::formatRate) ?: stringResource(R.string.dashboard_waiting_delta), accents.blue))
        add(SignalItem(Icons.Outlined.Upload, stringResource(R.string.dashboard_upload), data.uploadBytesPerSecond?.let(::formatRate) ?: stringResource(R.string.dashboard_waiting_delta), accents.violet))
        add(SignalItem(Icons.Outlined.Speed, stringResource(R.string.dashboard_gpu_load), data.gpuUsagePercent?.let(::formatPercent) ?: stringResource(R.string.dashboard_gpu_unavailable), accents.green))
        add(SignalItem(Icons.Outlined.AccessTime, stringResource(R.string.dashboard_uptime), formatUptime(data.uptimeMillis), accents.blue))
        data.batteryTemperatureCelsius?.let { add(SignalItem(Icons.Outlined.Thermostat, stringResource(R.string.dashboard_battery_temperature), String.format(Locale.getDefault(), "%.1f °C", it), accents.cyan)) }
        data.batteryVoltageMillivolts?.let { add(SignalItem(Icons.Outlined.Info, stringResource(R.string.dashboard_battery_voltage), String.format(Locale.getDefault(), "%.2f V", it / 1000.0), accents.green)) }
        data.batteryCurrentMicroamps?.let { add(SignalItem(Icons.Outlined.Info, stringResource(R.string.dashboard_battery_current), String.format(Locale.getDefault(), "%+.0f mA", it / 1000.0), accents.violet)) }
        if (data.batteryCharging || data.batteryPluggedSource != PluggedSource.None) {
            add(SignalItem(Icons.Outlined.BatteryChargingFull, stringResource(R.string.dashboard_power_source), formatPluggedSource(data.batteryPluggedSource), accents.green))
        }
        data.sharedStorageTotalBytes?.let { total ->
            val used = data.sharedStorageUsedBytes ?: 0L
            add(SignalItem(Icons.Outlined.Storage, stringResource(R.string.dashboard_shared_storage), stringResource(R.string.dashboard_used_of_total, ByteSizeFormatter.format(used), ByteSizeFormatter.format(total)), accents.blue))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.Speed, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(21.dp))
                Text(stringResource(R.string.dashboard_system_signals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 760.dp && fontScale <= 1.30f -> 4
                    maxWidth >= 352.dp && fontScale <= 1.18f -> 2
                    else -> 1
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    signals.chunked(columns).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { item ->
                                SignalTile(item = item, modifier = Modifier.weight(1f))
                            }
                            repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalTile(item: SignalItem, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 86.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, item.accent.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(item.icon, contentDescription = null, tint = item.accent, modifier = Modifier.size(19.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecommendationsCard(
    recommendations: List<DashboardRecommendation>,
    onRecommendation: (RecommendationType) -> Unit,
) {
    val accents = apexAccentPalette()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = accents.blue, modifier = Modifier.size(21.dp))
                Text(
                    text = stringResource(R.string.dashboard_recommendations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(7.dp))
            recommendations.forEachIndexed { index, recommendation ->
                RecommendationRow(recommendation, onRecommendation)
                if (index != recommendations.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 5.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationRow(
    recommendation: DashboardRecommendation,
    onRecommendation: (RecommendationType) -> Unit,
) {
    val accents = apexAccentPalette()
    val accent = when (recommendation.severity) {
        RecommendationSeverity.Critical -> MaterialTheme.colorScheme.error
        RecommendationSeverity.Attention -> accents.warning
        RecommendationSeverity.Info -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(shape = MaterialTheme.shapes.small, color = accent.copy(alpha = 0.10f)) {
            Icon(
                imageVector = recommendationIcon(recommendation.type),
                contentDescription = null,
                modifier = Modifier.padding(9.dp).size(21.dp),
                tint = accent,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(recommendationTitle(recommendation.type), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(recommendationDescription(recommendation.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            recommendationActionLabel(recommendation.type)?.let { label ->
                TextButton(
                    onClick = { onRecommendation(recommendation.type) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun recommendationActionLabel(type: RecommendationType): String? = when (type) {
    RecommendationType.ThermalCritical,
    RecommendationType.ThermalModerate,
    RecommendationType.BatteryWarm,
    RecommendationType.BatteryLow -> stringResource(R.string.dashboard_action_battery)
    RecommendationType.StorageCritical,
    RecommendationType.StorageLow -> stringResource(R.string.dashboard_action_storage)
    RecommendationType.MemoryLow,
    RecommendationType.MemoryHigh -> stringResource(R.string.dashboard_action_memory)
    RecommendationType.NetworkUnvalidated -> stringResource(R.string.dashboard_action_network)
    RecommendationType.Healthy -> null
}

private fun recommendationIcon(type: RecommendationType): ImageVector = when (type) {
    RecommendationType.ThermalCritical,
    RecommendationType.ThermalModerate,
    RecommendationType.BatteryWarm -> Icons.Outlined.Thermostat
    RecommendationType.StorageCritical,
    RecommendationType.StorageLow -> Icons.Outlined.Storage
    RecommendationType.MemoryLow,
    RecommendationType.MemoryHigh -> Icons.Outlined.Memory
    RecommendationType.BatteryLow -> Icons.Outlined.BatteryChargingFull
    RecommendationType.NetworkUnvalidated -> Icons.Outlined.NetworkCheck
    RecommendationType.Healthy -> Icons.Outlined.Speed
}

@Composable
private fun chartMetricLabel(metric: DashboardChartMetric): String = when (metric) {
    DashboardChartMetric.Cpu -> stringResource(R.string.dashboard_chart_cpu)
    DashboardChartMetric.Memory -> stringResource(R.string.dashboard_chart_ram)
    DashboardChartMetric.Download -> stringResource(R.string.dashboard_chart_download)
    DashboardChartMetric.Upload -> stringResource(R.string.dashboard_chart_upload)
}

@Composable
private fun chartMetricAccent(metric: DashboardChartMetric): Color {
    val accents = apexAccentPalette()
    return when (metric) {
        DashboardChartMetric.Cpu -> accents.cyan
        DashboardChartMetric.Memory -> accents.violet
        DashboardChartMetric.Download -> accents.blue
        DashboardChartMetric.Upload -> accents.green
    }
}

@Composable
private fun recommendationTitle(type: RecommendationType): String = when (type) {
    RecommendationType.ThermalCritical -> stringResource(R.string.dashboard_rec_thermal_critical_title)
    RecommendationType.ThermalModerate -> stringResource(R.string.dashboard_rec_thermal_moderate_title)
    RecommendationType.StorageCritical -> stringResource(R.string.dashboard_rec_storage_critical_title)
    RecommendationType.StorageLow -> stringResource(R.string.dashboard_rec_storage_low_title)
    RecommendationType.MemoryLow -> stringResource(R.string.dashboard_rec_memory_low_title)
    RecommendationType.MemoryHigh -> stringResource(R.string.dashboard_rec_memory_high_title)
    RecommendationType.BatteryWarm -> stringResource(R.string.dashboard_rec_battery_warm_title)
    RecommendationType.BatteryLow -> stringResource(R.string.dashboard_rec_battery_low_title)
    RecommendationType.NetworkUnvalidated -> stringResource(R.string.dashboard_rec_network_title)
    RecommendationType.Healthy -> stringResource(R.string.dashboard_rec_healthy_title)
}

@Composable
private fun recommendationDescription(type: RecommendationType): String = when (type) {
    RecommendationType.ThermalCritical -> stringResource(R.string.dashboard_rec_thermal_critical_body)
    RecommendationType.ThermalModerate -> stringResource(R.string.dashboard_rec_thermal_moderate_body)
    RecommendationType.StorageCritical -> stringResource(R.string.dashboard_rec_storage_critical_body)
    RecommendationType.StorageLow -> stringResource(R.string.dashboard_rec_storage_low_body)
    RecommendationType.MemoryLow -> stringResource(R.string.dashboard_rec_memory_low_body)
    RecommendationType.MemoryHigh -> stringResource(R.string.dashboard_rec_memory_high_body)
    RecommendationType.BatteryWarm -> stringResource(R.string.dashboard_rec_battery_warm_body)
    RecommendationType.BatteryLow -> stringResource(R.string.dashboard_rec_battery_low_body)
    RecommendationType.NetworkUnvalidated -> stringResource(R.string.dashboard_rec_network_body)
    RecommendationType.Healthy -> stringResource(R.string.dashboard_rec_healthy_body)
}

@Composable
private fun networkSummary(data: DashboardData): String = when {
    !data.networkValidated -> stringResource(R.string.dashboard_network_not_validated)
    data.networkMetered -> stringResource(R.string.dashboard_network_metered)
    else -> stringResource(R.string.dashboard_network_unmetered)
}

@Composable
private fun formatThermal(status: ThermalStatus): String = when (status) {
    ThermalStatus.None -> stringResource(R.string.dashboard_thermal_none)
    ThermalStatus.Light -> stringResource(R.string.dashboard_thermal_light)
    ThermalStatus.Moderate -> stringResource(R.string.dashboard_thermal_moderate)
    ThermalStatus.Severe -> stringResource(R.string.dashboard_thermal_severe)
    ThermalStatus.Critical -> stringResource(R.string.dashboard_thermal_critical)
    ThermalStatus.Emergency -> stringResource(R.string.dashboard_thermal_emergency)
    ThermalStatus.Shutdown -> stringResource(R.string.dashboard_thermal_shutdown)
    ThermalStatus.Unknown -> stringResource(R.string.dashboard_unavailable)
}

@Composable
private fun thermalAccent(status: ThermalStatus): Color {
    val accents = apexAccentPalette()
    return when (status) {
        ThermalStatus.None, ThermalStatus.Light -> accents.green
        ThermalStatus.Moderate -> accents.warning
        ThermalStatus.Severe, ThermalStatus.Critical, ThermalStatus.Emergency, ThermalStatus.Shutdown -> accents.critical
        ThermalStatus.Unknown -> accents.muted
    }
}

@Composable
private fun formatPluggedSource(source: PluggedSource): String = when (source) {
    PluggedSource.Ac -> stringResource(R.string.dashboard_power_ac)
    PluggedSource.Usb -> stringResource(R.string.dashboard_power_usb)
    PluggedSource.Wireless -> stringResource(R.string.dashboard_power_wireless)
    PluggedSource.Dock -> stringResource(R.string.dashboard_power_dock)
    PluggedSource.None -> stringResource(R.string.dashboard_power_none)
    PluggedSource.Unknown -> stringResource(R.string.dashboard_power_unknown)
}

@Composable
private fun formatBatteryHealth(health: BatteryHealth): String = when (health) {
    BatteryHealth.Good -> stringResource(R.string.dashboard_health_good)
    BatteryHealth.Cold -> stringResource(R.string.dashboard_health_cold)
    BatteryHealth.Dead -> stringResource(R.string.dashboard_health_dead)
    BatteryHealth.Overheat -> stringResource(R.string.dashboard_health_overheat)
    BatteryHealth.OverVoltage -> stringResource(R.string.dashboard_health_over_voltage)
    BatteryHealth.Failure -> stringResource(R.string.dashboard_health_failure)
    BatteryHealth.Unknown -> stringResource(R.string.dashboard_health_unknown)
}

private fun formatChartValue(metric: DashboardChartMetric, value: Double): String = when (metric) {
    DashboardChartMetric.Cpu, DashboardChartMetric.Memory -> formatPercent(value)
    DashboardChartMetric.Download, DashboardChartMetric.Upload -> formatRate(value.toLong())
}

private fun formatPercent(value: Double): String =
    String.format(Locale.getDefault(), "%.0f%%", value.coerceIn(0.0, 100.0))

private fun formatFrequency(mhz: Double): String = when {
    mhz >= 1_000.0 -> String.format(Locale.getDefault(), "%.2f GHz", mhz / 1_000.0)
    else -> String.format(Locale.getDefault(), "%.0f MHz", mhz)
}

private fun formatRate(bytesPerSecond: Long): String = "${ByteSizeFormatter.format(bytesPerSecond)}/s"

private fun formatUptime(uptimeMillis: Long): String {
    val totalMinutes = (uptimeMillis / 60_000L).coerceAtLeast(0L)
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes / 60L) % 24L
    val minutes = totalMinutes % 60L
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
