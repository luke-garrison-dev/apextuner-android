package com.apextuner.feature.notifications

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexLayout
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationHistoryRoute(
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onUpgrade: () -> Unit,
    viewModel: NotificationHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var confirmClearAll by rememberSaveable { mutableStateOf(false) }
    var clearPackage by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ApexLayout.horizontalPadding(),
                vertical = 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Notifications, contentDescription = null)
                    Column {
                        Text(stringResource(R.string.ui_notification_history),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.ui_private_on_device_review_of_notifications_you_explicitl),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                StatusCard(
                    state = state,
                    onOpenSettings = onOpenSettings,
                    onUpgrade = onUpgrade,
                )
            }

            if (state.items.isNotEmpty()) {
                item {
                    NotificationIntelligenceCard(
                        context = context,
                        intelligence = analyzeNotificationHistory(state.items),
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.ui_retention),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.ui_older_entries_are_deleted_locally_by_workmanager_changi),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NotificationHistoryPolicy.allowedRetentionDays.forEach { days ->
                                FilterChip(
                                    selected = state.settings.retentionDays == days,
                                    onClick = { viewModel.setRetentionDays(days) },
                                    label = { Text(stringResource(R.string.notification_retention_days_short, days)) },
                                )
                            }
                        }
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = { confirmClearAll = true },
                            enabled = state.items.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ui_clear_all_notification_history))
                        }
                    }
                }
            }

            if (state.settings.mutedPackages.isNotEmpty()) {
                item {
                    MutedAppsCard(
                        context = context,
                        packages = state.settings.mutedPackages,
                        onUnmute = { viewModel.setMuted(it, false) },
                    )
                }
            }

            if (state.items.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            if (state.collectionActive) {
                                "No notification entries have been recorded yet."
                            } else {
                                "No stored notification history."
                            },
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.items, key = { it.id }) { item ->
                    NotificationHistoryCard(
                        context = context,
                        item = item,
                        muted = item.packageName in state.settings.mutedPackages,
                        onMute = { viewModel.setMuted(item.packageName, true) },
                        onUnmute = { viewModel.setMuted(item.packageName, false) },
                        onClearPackage = { clearPackage = item.packageName },
                    )
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.ui_clear_notification_history)) },
            text = { Text(stringResource(R.string.ui_this_permanently_deletes_all_notification_history_rows)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearAll = false
                        viewModel.clearAll()
                    },
                ) { Text(stringResource(R.string.ui_clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }

    clearPackage?.let { packageName ->
        val appLabel = remember(packageName) { applicationLabel(context, packageName) }
        AlertDialog(
            onDismissRequest = { clearPackage = null },
            title = { Text(stringResource(R.string.ui_clear_this_app_s_history)) },
            text = {
                Text(stringResource(R.string.notification_delete_app_history_body, appLabel))
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearPackage = null
                        viewModel.clearPackage(packageName)
                    },
                ) { Text(stringResource(R.string.ui_clear_app_history)) }
            },
            dismissButton = {
                TextButton(onClick = { clearPackage = null }) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(stringResource(R.string.ui_notification_history)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.ui_close)) }
            },
        )
    }
}

@Composable
private fun NotificationIntelligenceCard(
    context: Context,
    intelligence: NotificationIntelligence,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.notification_intelligence_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.notification_intelligence_body, intelligence.sampleCount, intelligence.uniqueApps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            intelligence.busiestHour?.let { hour ->
                Text(stringResource(R.string.notification_intelligence_busy_hour, hour, (hour + 1) % 24))
            }
            intelligence.increasingPackage?.let { pkg ->
                val label = remember(pkg) { applicationLabel(context, pkg) }
                Text(stringResource(R.string.notification_intelligence_increasing, label, intelligence.increasingDelta ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (intelligence.topApps.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.notification_intelligence_top_apps), fontWeight = FontWeight.Medium)
                intelligence.topApps.take(3).forEach { row ->
                    val label = remember(row.packageName) { applicationLabel(context, row.packageName) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.notification_intelligence_count, row.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { openAndroidNotificationSettings(context, row.packageName) }) {
                            Text(stringResource(R.string.notification_intelligence_android_settings))
                        }
                    }
                }
            }
            if (intelligence.sampleLimitReached) {
                Text(stringResource(R.string.notification_intelligence_sample_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: NotificationHistoryUiState,
    onOpenSettings: () -> Unit,
    onUpgrade: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_collection_status),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val status = when {
                !state.availability.available ->
                    state.availability.reason ?: "Notification history is unavailable on this Android profile/device."
                !state.premium ->
                    "Paused. ApexTuner Premium is required to collect new notification history. " +
                        "Existing local entries remain available to review and delete."
                !state.settings.enabled ->
                    "Off. Notification history can only be enabled from ApexTuner Settings after the privacy disclosure."
                !state.accessGranted ->
                    "Waiting for Android notification access. No notification content is collected until Android grants access."
                else ->
                    "Active. Title, text, source app package and timestamp are stored only in ApexTuner's local Room database."
            }
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.ui_android_may_redact_sensitive_content_such_as_detected_o),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.premium) {
                Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_view_apextuner_premium))
                }
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_open_notification_history_settings))
            }
        }
    }
}

@Composable
private fun MutedAppsCard(
    context: Context,
    packages: Set<String>,
    onUnmute: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_muted_in_apextuner),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.ui_muted_apps_stop_creating_future_apextuner_history_entri),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            packages.sorted().take(MAX_MUTED_APPS_SHOWN).forEach { packageName ->
                val appLabel = remember(packageName) { applicationLabel(context, packageName) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(appLabel, fontWeight = FontWeight.Medium)
                        Text(
                            packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onUnmute(packageName) }) { Text(stringResource(R.string.ui_unmute)) }
                }
            }
            if (packages.size > MAX_MUTED_APPS_SHOWN) {
                Text(
                    stringResource(R.string.notification_additional_muted_apps, packages.size - MAX_MUTED_APPS_SHOWN),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationHistoryCard(
    context: Context,
    item: NotificationHistoryItem,
    muted: Boolean,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onClearPackage: () -> Unit,
) {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val appLabel = remember(item.packageName) { applicationLabel(context, item.packageName) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                appLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.notification_entry_metadata, item.packageName, formatter.format(Date(item.postedAtEpochMillis))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.title.isNotBlank()) {
                Text(item.title, fontWeight = FontWeight.Medium)
            }
            if (item.text.isNotBlank()) {
                Text(item.text)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = if (muted) onUnmute else onMute) {
                    Text(if (muted) "Unmute in ApexTuner" else "Mute in ApexTuner")
                }
                TextButton(onClick = onClearPackage) {
                    Text(stringResource(R.string.ui_clear_app_history))
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun applicationLabel(context: Context, packageName: String): String =
    runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)


private fun openAndroidNotificationSettings(context: Context, packageName: String) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val MAX_MUTED_APPS_SHOWN = 20
