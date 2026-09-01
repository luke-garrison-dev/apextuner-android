package com.apextuner.feature.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.ui.ApexMetricRow
import com.apextuner.core.model.BatteryHealth
import com.apextuner.core.model.SystemProfile
import com.apextuner.feature.battery.model.BatteryUiState
import com.apextuner.feature.battery.model.ChargingSessionInsight
import com.apextuner.feature.battery.model.EstimateConfidence
import com.apextuner.feature.battery.model.ChargingHistorySummary
import com.apextuner.feature.battery.model.RecentAppActivity
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
fun BatteryRoute(
    onBack: (() -> Unit)? = null,
    viewModel: BatteryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        var leftForExternalSettings = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> leftForExternalSettings = true
                Lifecycle.Event.ON_RESUME -> if (leftForExternalSettings) {
                    leftForExternalSettings = false
                    viewModel.retry()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BatteryScreen(
        state = state,
        onRetry = viewModel::retry,
        onApplySaver = viewModel::applyBatteryProfile,
        onRestore = viewModel::restoreBalanced,
        onOpenWriteSettings = { context.launchSettingsSafely(writeSettingsIntent(context)) },
        onOpenBatterySaver = { context.launchSettingsSafely(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
        onOpenUsageAccess = { context.launchSettingsSafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        onBack = onBack,
    )
}

@Composable
private fun BatteryScreen(
    state: BatteryUiState,
    onRetry: () -> Unit,
    onApplySaver: () -> Unit,
    onRestore: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenBatterySaver: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onBack: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (state) {
            BatteryUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.battery_loading))
            }
            is BatteryUiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.battery_retry)) }
            }
            is BatteryUiState.Ready -> {
                val data = state.insights
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        if (onBack != null) OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                        Text(
                            text = stringResource(R.string.battery_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.battery_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.message?.let { message -> item { InfoCard(message) } }
                    item {
                        val b = data.battery
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.ui_live_battery), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                MetricRow(stringResource(R.string.battery_charge), b.levelPercent?.let { "$it%" } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_health), formatBatteryHealth(b.health))
                                MetricRow(stringResource(R.string.battery_temperature), b.temperatureCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_voltage), b.voltageMillivolts?.let { "${it} mV" } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_current), b.currentMicroamps?.let { formatCurrent(it, b.charging) } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_average_current), b.averageCurrentMicroamps?.let { formatCurrent(it, b.charging) } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_charge_counter), b.chargeCounterMicroampHours?.let { String.format(Locale.getDefault(), "%.0f mAh", it / 1000.0) } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_energy_counter), b.energyCounterNanowattHours?.let { String.format(Locale.getDefault(), "%.0f mWh", it / 1_000_000.0) } ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_side_power), formatBatteryPower(b.currentMicroamps, b.voltageMillivolts, b.charging, stringResource(R.string.battery_unavailable)))
                                MetricRow(stringResource(R.string.battery_cycles), b.cycleCount?.toString() ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_technology), b.technology ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_remaining), data.predictedRemainingMillis?.let(::formatDuration) ?: stringResource(R.string.battery_unavailable))
                                MetricRow(stringResource(R.string.battery_power_save), if (data.powerSaveMode) stringResource(R.string.battery_on) else stringResource(R.string.battery_off))
                                MetricRow(stringResource(R.string.battery_standby_bucket), data.apexTunerStandbyBucket ?: stringResource(R.string.battery_unavailable))
                            }
                        }
                    }
                    item { BatteryHealthTrendCard(data.healthTrend) }
                    item { ChargingIntelligenceCard(data.chargingHistory) }
                    item { ChargingSessionsCard(data.chargingSessions) }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.BatterySaver, contentDescription = null)
                                    Text(stringResource(R.string.battery_power_profile), style = MaterialTheme.typography.titleLarge)
                                }
                                Text(stringResource(R.string.battery_profile_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val statusText = when {
                                    !data.profileMatchesSystem && data.hasProfileRestorePoint ->
                                        stringResource(R.string.battery_profile_status_changed_externally, systemProfileLabel(data.activeProfile))
                                    data.activeProfile == SystemProfile.Balanced ->
                                        stringResource(R.string.battery_profile_status_balanced)
                                    else -> stringResource(R.string.battery_profile_status_active, systemProfileLabel(data.activeProfile))
                                }
                                Text(statusText, fontWeight = FontWeight.Medium)
                                FilledTonalButton(onClick = onApplySaver, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.battery_apply_saver))
                                }
                                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.Restore, contentDescription = null)
                                    Text(stringResource(R.string.battery_restore_balanced), modifier = Modifier.padding(start = 8.dp))
                                }
                                OutlinedButton(onClick = onOpenWriteSettings, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.Settings, contentDescription = null)
                                    Text(stringResource(R.string.battery_write_access), modifier = Modifier.padding(start = 8.dp))
                                }
                                OutlinedButton(onClick = onOpenBatterySaver, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.battery_open_saver))
                                }
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.battery_recommendations), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    items(data.recommendations) { recommendation -> InfoCard(recommendation) }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.battery_recent_activity), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.battery_recent_activity_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!data.usageAccessGranted) {
                                    Text(stringResource(R.string.battery_usage_access))
                                    OutlinedButton(onClick = onOpenUsageAccess) { Text(stringResource(R.string.battery_open_usage_access)) }
                                }
                            }
                        }
                    }
                    if (data.usageAccessGranted) {
                        items(data.recentActivity, key = { it.packageName }) { ActivityRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargingIntelligenceCard(summary: ChargingHistorySummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battery_charging_intelligence), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.battery_charging_intelligence_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MetricRow(stringResource(R.string.battery_completed_sessions), summary.completedSessions.toString())
            MetricRow(
                stringResource(R.string.battery_typical_rate),
                summary.typicalChargeRatePercentPerHour?.let { String.format(Locale.getDefault(), "%.1f %%/h", it) } ?: stringResource(R.string.battery_unavailable),
            )
            MetricRow(
                stringResource(R.string.battery_latest_rate),
                summary.latestChargeRatePercentPerHour?.let { String.format(Locale.getDefault(), "%.1f %%/h", it) } ?: stringResource(R.string.battery_unavailable),
            )
            MetricRow(
                stringResource(R.string.battery_average_peak_temp),
                summary.averagePeakTemperatureCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: stringResource(R.string.battery_unavailable),
            )
            Text(summary.insight, style = MaterialTheme.typography.bodyMedium, color = if (summary.hotSessionCount > 0 || summary.latestSlowerThanBaseline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChargingSessionsCard(sessions: List<ChargingSessionInsight>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.battery_charging_sessions), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.battery_charging_sessions_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.battery_charging_sessions_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sessions.take(5).forEachIndexed { index, session ->
                    if (index > 0) androidx.compose.material3.HorizontalDivider()
                    val start = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAtEpochMillis))
                    val end = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(session.endedAtEpochMillis))
                    Text(stringResource(R.string.battery_session_period, start, end), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.battery_session_level, session.startLevelPercent?.let { "$it%" } ?: "—", session.endLevelPercent?.let { "$it%" } ?: "—"), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.battery_session_added, session.estimatedAddedMah?.let { String.format(Locale.getDefault(), "%.0f mAh", it) } ?: "Unavailable"), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.battery_session_temperature, session.averageTemperatureCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "—", session.peakTemperatureCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "—"), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.battery_session_confidence, estimateConfidenceLabel(session.confidence), session.sampleCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BatteryHealthTrendCard(trend: BatteryHealthTrend) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_battery_health_trend), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when (trend) {
                is BatteryHealthTrend.TelemetryUnavailable -> {
                    Text(
                        stringResource(R.string.battery_health_telemetry_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is BatteryHealthTrend.InsufficientHistory -> {
                    Text(
                        stringResource(R.string.battery_insufficient_history, trend.daysAvailable, trend.minimumDays),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is BatteryHealthTrend.Ready -> {
                    val capacityPoints = trend.points.mapNotNull { point ->
                        point.estimatedFullChargeCapacityMah?.let { point.epochDay to it }
                    }
                    trend.capacityChangePercent?.let {
                        MetricRow("Estimated capacity change", String.format(Locale.getDefault(), "%+.1f%%", it))
                    } ?: MetricRow("Estimated capacity change", "Unavailable")
                    val latestCycle = trend.points.mapNotNull { it.cycleCount }.lastOrNull()
                    MetricRow("Latest cycle count", latestCycle?.toString() ?: "Unavailable")
                    if (capacityPoints.size >= 2) {
                        val lineColor = MaterialTheme.colorScheme.primary
                        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                            val values = capacityPoints.map { it.second }
                            val min = values.minOrNull() ?: return@Canvas
                            val max = values.maxOrNull() ?: return@Canvas
                            val span = (max - min).takeIf { it > 0.0 } ?: 1.0
                            val step = if (values.size <= 1) size.width else size.width / (values.size - 1).toFloat()
                            val points = values.mapIndexed { index, value ->
                                Offset(
                                    x = index * step,
                                    y = size.height - (((value - min) / span).toFloat() * size.height),
                                )
                            }
                            for (index in 0 until points.lastIndex) {
                                drawLine(lineColor, points[index], points[index + 1], strokeWidth = 3.dp.toPx())
                            }
                            points.forEach { drawCircle(lineColor, radius = 3.dp.toPx(), center = it) }
                            drawRect(
                                color = lineColor.copy(alpha = 0.25f),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                        Text(stringResource(R.string.ui_full_charge_capacity_is_an_estimate_derived_locally_fro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(stringResource(R.string.ui_capacity_estimate_is_unavailable_on_enough_days_to_draw),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    ApexMetricRow(label = label, value = value)
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(text, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActivityRow(activity: RecentAppActivity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(activity.label, fontWeight = FontWeight.SemiBold)
            Text(activity.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.battery_foreground_last_24h, formatDuration(activity.foregroundMillis)))
        }
    }
}

private fun formatCurrent(microamps: Long, charging: Boolean): String {
    val milliamps = abs(microamps) / 1000.0
    return String.format(Locale.getDefault(), "%.0f mA (%s)", milliamps, batteryCurrentDirection(microamps, charging))
}

private fun formatBatteryPower(
    currentMicroamps: Long?,
    voltageMillivolts: Int?,
    charging: Boolean,
    unavailable: String,
): String {
    if (currentMicroamps == null || voltageMillivolts == null || voltageMillivolts <= 0) return unavailable
    val watts = abs(currentMicroamps.toDouble()) * voltageMillivolts.toDouble() / 1_000_000_000.0
    val direction = when {
        currentMicroamps == 0L -> "idle"
        charging -> "into battery"
        else -> "from battery"
    }
    return String.format(Locale.getDefault(), "%.2f W (%s)", watts, direction)
}

private fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = minutes / 60
    val remaining = minutes % 60
    return if (hours > 0) "${hours}h ${remaining}m" else "${remaining}m"
}

private fun writeSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_WRITE_SETTINGS,
    Uri.parse("package:${context.packageName}"),
)

private fun Context.launchSettingsSafely(primary: Intent) {
    val fallback = Intent(Settings.ACTION_SETTINGS)
    val target = primary.takeIf { it.resolveActivity(packageManager) != null } ?: fallback
    runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            if (target.action != fallback.action) {
                runCatching { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
}

@Composable
private fun formatBatteryHealth(health: BatteryHealth): String = when (health) {
    BatteryHealth.Good -> stringResource(R.string.battery_health_good)
    BatteryHealth.Cold -> stringResource(R.string.battery_health_cold)
    BatteryHealth.Dead -> stringResource(R.string.battery_health_dead)
    BatteryHealth.Overheat -> stringResource(R.string.battery_health_overheat)
    BatteryHealth.OverVoltage -> stringResource(R.string.battery_health_over_voltage)
    BatteryHealth.Failure -> stringResource(R.string.battery_health_failure)
    BatteryHealth.Unknown -> stringResource(R.string.battery_health_unknown)
}

@Composable
private fun systemProfileLabel(profile: SystemProfile): String = stringResource(
    when (profile) {
        SystemProfile.Balanced -> R.string.system_profile_balanced
        SystemProfile.Battery -> R.string.system_profile_battery
        SystemProfile.Performance -> R.string.system_profile_performance
        SystemProfile.Gaming -> R.string.system_profile_gaming
    },
)

@Composable
private fun estimateConfidenceLabel(confidence: EstimateConfidence): String = stringResource(
    when (confidence) {
        EstimateConfidence.Low -> R.string.battery_confidence_low
        EstimateConfidence.Medium -> R.string.battery_confidence_medium
        EstimateConfidence.High -> R.string.battery_confidence_high
    },
)
