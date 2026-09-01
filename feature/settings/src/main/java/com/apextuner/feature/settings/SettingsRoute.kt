package com.apextuner.feature.settings

import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import com.apextuner.feature.settings.automation.SmartAutomationEvaluator
import com.apextuner.feature.settings.automation.SmartAutomationPolicy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.model.MaintenanceCadence
import com.apextuner.core.model.ThemeMode
import com.apextuner.feature.settings.monitor.MonitorRuntimeState
import com.apextuner.feature.settings.tile.ApexMonitorTileService

enum class SettingsSection { SmartAutomation }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsRoute(
    onPremium: () -> Unit = {},
    onNotificationHistory: () -> Unit = {},
    initialSection: SettingsSection? = null,
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val monitor by viewModel.monitor.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val smartAutomation by viewModel.smartAutomation.collectAsStateWithLifecycle()
    val smartAutomationMessage by viewModel.smartAutomationMessage.collectAsStateWithLifecycle()
    val notificationHistorySettings by viewModel.notificationHistorySettings.collectAsStateWithLifecycle()
    val notificationHistoryAccessGranted by viewModel.notificationHistoryAccessGranted.collectAsStateWithLifecycle()
    val notificationHistoryAvailability = viewModel.notificationHistoryAvailability
    val settingsActionMessage by viewModel.settingsActionMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var specialAccessRefreshToken by remember { mutableIntStateOf(0) }
    var infoDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showNotificationHistoryDisclosure by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val canWriteSystemSettings = remember(context, specialAccessRefreshToken) { Settings.System.canWrite(context) }
    val overlayGranted = remember(context, specialAccessRefreshToken) { Settings.canDrawOverlays(context) }
    val notificationsGranted = remember(context, specialAccessRefreshToken) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(initialSection) {
        if (initialSection == SettingsSection.SmartAutomation) {
            listState.scrollToItem(SMART_AUTOMATION_ITEM_INDEX)
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                specialAccessRefreshToken += 1
                viewModel.refreshNotificationHistoryAccess()
                viewModel.refreshSmartAutomation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        specialAccessRefreshToken += 1
        viewModel.onAutomationNotificationPermissionChanged(granted)
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspectBackup(uri)
    }
    val scheduledBackupTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setScheduledBackupTree(uri)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onBack != null) {
                            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back)) }
                        }
                        Text(stringResource(R.string.ui_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ui_appearance_premium_controls_automation_privacy_and_diag), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        settingsActionMessage?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::dismissSettingsActionMessage) {
                                Text(stringResource(R.string.ui_close))
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ui_premium), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(if (entitlement.isPremium) R.string.settings_edition_premium else R.string.settings_edition_free))
                        if (!entitlement.isPremium) Button(onClick = onPremium, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_view_apextuner_premium)) }
                        else OutlinedButton(onClick = onPremium, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_manage_restore_purchases)) }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_appearance)) {
                    Text(stringResource(R.string.ui_theme), fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = prefs.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(themeModeLabel(mode)) },
                            )
                        }
                    }
                    SettingSwitch(stringResource(R.string.ui_dynamic_material_color), stringResource(R.string.ui_use_android_s_wallpaper_derived_colors_on_android_12), prefs.dynamicColor, viewModel::setDynamicColor)
                    SettingSwitch(stringResource(R.string.ui_apextuner_haptics), stringResource(R.string.ui_allow_deliberate_haptic_feedback_in_apextuner_controls), prefs.hapticsEnabled, viewModel::setHaptics)
                    SettingSwitch(stringResource(R.string.ui_show_advanced_tools), stringResource(R.string.ui_expose_shizuku_root_tools_privileged_actions_still_requ), prefs.showAdvancedTools, viewModel::setAdvancedTools)
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_notification_history_premium)) {
                    Text(stringResource(R.string.ui_disabled_by_default_apextuner_can_record_notification_t),
                    )
                    val notificationStatus = when {
                        !notificationHistoryAvailability.available ->
                            notificationHistoryAvailability.reason ?: "Unavailable on this Android profile/device"
                        !entitlement.isPremium -> "Paused • Premium required for new collection"
                        !notificationHistorySettings.enabled -> "Off • no notification data is collected"
                        !notificationHistoryAccessGranted -> "Waiting for Android notification access"
                        else -> "Active • local Room storage only"
                    }
                    Text(
                        notificationStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingSwitch(stringResource(R.string.ui_notification_history), stringResource(R.string.ui_keep_a_local_review_log_muting_an_app_affects_only_apex),
                        notificationHistorySettings.enabled,
                        { enabled ->
                            when {
                                !enabled -> viewModel.disableNotificationHistory()
                                !notificationHistoryAvailability.available -> Unit
                                !entitlement.isPremium -> onPremium()
                                else -> showNotificationHistoryDisclosure = true
                            }
                        },
                        enabled = notificationHistoryAvailability.available &&
                            (entitlement.isPremium || notificationHistorySettings.enabled),
                    )
                    OutlinedButton(
                        onClick = viewModel::openNotificationAccessSettings,
                        enabled = notificationHistoryAvailability.available &&
                            notificationHistorySettings.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ui_manage_android_notification_access)) }
                    OutlinedButton(
                        onClick = onNotificationHistory,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ui_review_history_retention)) }
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_real_time_monitor_premium)) {
                    Text(stringResource(R.string.ui_a_draggable_on_device_overlay_with_cpu_ram_battery_temp))
                    Text(stringResource(R.string.monitor_status_summary, monitor.state.name, monitor.lastError?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (!overlayGranted) OutlinedButton(onClick = { openOverlaySettings(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_grant_display_over_other_apps_access)) }
                    if (!notificationsGranted) {
                        OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_allow_monitor_notifications)) }
                    }
                    if (monitor.state in setOf(MonitorRuntimeState.Active, MonitorRuntimeState.Starting)) {
                        Button(onClick = viewModel::stopMonitor, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_stop_monitor)) }
                    } else {
                        Button(
                            onClick = {
                                when (viewModel.startMonitor()) {
                                    MonitorStartOutcome.PremiumRequired -> onPremium()
                                    MonitorStartOutcome.OverlayPermissionRequired -> openOverlaySettings(context)
                                    MonitorStartOutcome.Started,
                                    MonitorStartOutcome.BlockedByAndroid -> Unit
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(if (entitlement.isPremium) R.string.ui_start_monitor else R.string.ui_unlock_monitor)) }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        OutlinedButton(onClick = { requestTile(context) }, enabled = entitlement.isPremium, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_add_apex_monitor_quick_settings_tile)) }
                    } else Text(stringResource(R.string.ui_you_can_add_the_apex_monitor_tile_manually_from_android), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.ui_the_home_screen_apextuner_status_widget_is_also_availab), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_scheduled_automation_premium)) {
                    Text(stringResource(R.string.ui_workmanager_schedules_are_battery_conscious_and_inexact))
                    SettingSwitch(stringResource(R.string.ui_scheduled_storage_check), stringResource(R.string.ui_run_a_bounded_low_storage_check_and_notify_when_interna), prefs.scheduledMaintenanceEnabled, { if (entitlement.isPremium) viewModel.setScheduledMaintenance(it) else onPremium() }, enabled = entitlement.isPremium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaintenanceCadence.entries.forEach { cadence ->
                            FilterChip(selected = prefs.maintenanceCadence == cadence, onClick = { viewModel.setMaintenanceCadence(cadence) }, enabled = entitlement.isPremium && prefs.scheduledMaintenanceEnabled, label = { Text(cadence.name) })
                        }
                    }
                    if (!canWriteSystemSettings) {
                        OutlinedButton(
                            onClick = { openWriteSettings(context) },
                            enabled = entitlement.isPremium,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ui_grant_modify_system_settings)) }
                    }
                    SettingSwitch(stringResource(R.string.ui_night_battery_profile), stringResource(R.string.ui_around_22_00_apply_apextuner_s_reversible_battery_profi),
                        prefs.nightBatteryProfileEnabled,
                        { enabled ->
                            when {
                                !entitlement.isPremium -> onPremium()
                                enabled && !canWriteSystemSettings -> openWriteSettings(context)
                                else -> viewModel.setNightBatteryProfile(enabled)
                            }
                        },
                        enabled = entitlement.isPremium,
                    )
                }
            }
            item {
                SectionCard(stringResource(R.string.smart_automation_title)) {
                    if (onBack != null && initialSection == SettingsSection.SmartAutomation) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ui_back))
                        }
                    }
                    Text(
                        stringResource(R.string.smart_automation_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val enabledRules = smartAutomation.rules.count { it.enabled }
                    val dryRunRules = smartAutomation.rules.count { it.enabled && it.dryRun }
                    val liveRules = enabledRules - dryRunRules
                    Text(
                        stringResource(R.string.smart_automation_summary, enabledRules, liveRules, dryRunRules),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    smartAutomationMessage?.let { message ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(message, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = viewModel::dismissSmartAutomationMessage) { Text(stringResource(R.string.ui_close)) }
                            }
                        }
                    }
                    val hasEnabledNotificationRule = smartAutomation.rules.any {
                        it.enabled && SmartAutomationPolicy.isNotificationRule(it) && !it.dryRun
                    }
                    if (!notificationsGranted) {
                        Text(
                            stringResource(if (hasEnabledNotificationRule) R.string.smart_automation_notifications_blocked else R.string.smart_automation_notifications_optional),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            enabled = entitlement.isPremium,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.smart_automation_allow_notifications)) }
                    }
                    smartAutomation.rules.forEach { rule ->
                        HorizontalDivider()
                        Text(rule.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.smart_automation_condition, SmartAutomationEvaluator.description(rule)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val actionText = when {
                            SmartAutomationPolicy.requiresModifySystemSettings(rule) -> stringResource(R.string.smart_automation_action_battery_live)
                            SmartAutomationPolicy.isBatteryProfileRule(rule) -> stringResource(R.string.smart_automation_action_battery_dry)
                            SmartAutomationPolicy.isDiagnosticCaptureRule(rule) && rule.dryRun -> stringResource(R.string.smart_automation_action_capture_dry)
                            SmartAutomationPolicy.isDiagnosticCaptureRule(rule) -> stringResource(R.string.smart_automation_action_capture_live)
                            rule.dryRun -> stringResource(R.string.smart_automation_action_notification_dry)
                            else -> rule.actionArgument ?: stringResource(R.string.smart_automation_action_notification_live)
                        }
                        Text(
                            stringResource(R.string.smart_automation_action, actionText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SettingSwitch(
                            stringResource(R.string.smart_automation_enabled),
                            stringResource(R.string.smart_automation_enabled_body),
                            rule.enabled,
                            { enabled -> if (entitlement.isPremium) viewModel.setSmartRuleEnabled(rule.id, enabled) else onPremium() },
                            enabled = entitlement.isPremium,
                        )
                        val thresholdOptions = SmartAutomationPolicy.thresholdOptions(rule)
                        if (thresholdOptions.isNotEmpty()) {
                            Text(stringResource(R.string.smart_automation_threshold), fontWeight = FontWeight.Medium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                thresholdOptions.forEach { threshold ->
                                    FilterChip(
                                        selected = rule.thresholdValue == threshold,
                                        onClick = { viewModel.setSmartRuleThreshold(rule.id, threshold) },
                                        enabled = entitlement.isPremium && rule.enabled,
                                        label = { Text(SmartAutomationPolicy.thresholdLabel(rule, threshold)) },
                                    )
                                }
                            }
                        }
                        Text(stringResource(R.string.smart_automation_cooldown), fontWeight = FontWeight.Medium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SmartAutomationPolicy.cooldownOptions().forEach { cooldown ->
                                FilterChip(
                                    selected = rule.cooldownMillis == cooldown,
                                    onClick = { viewModel.setSmartRuleCooldown(rule.id, cooldown) },
                                    enabled = entitlement.isPremium && rule.enabled,
                                    label = { Text(SmartAutomationPolicy.cooldownLabel(cooldown)) },
                                )
                            }
                        }
                        SettingSwitch(
                            stringResource(R.string.smart_automation_dry_run),
                            stringResource(R.string.smart_automation_dry_run_body),
                            rule.dryRun,
                            { dryRun ->
                                if (!entitlement.isPremium) {
                                    onPremium()
                                } else if (!dryRun && SmartAutomationPolicy.isBatteryProfileRule(rule) && !canWriteSystemSettings) {
                                    openWriteSettings(context)
                                } else {
                                    viewModel.setSmartRuleDryRun(rule.id, dryRun)
                                }
                            },
                            enabled = entitlement.isPremium && rule.enabled,
                        )
                        if (rule.enabled && SmartAutomationPolicy.requiresModifySystemSettings(rule) && !canWriteSystemSettings) {
                            Text(
                                stringResource(R.string.smart_automation_modify_settings_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = { openWriteSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.ui_grant_modify_system_settings))
                            }
                        }
                        if (rule.enabled) {
                            Text(
                                stringResource(if (rule.dryRun) R.string.smart_automation_status_dry_run else R.string.smart_automation_status_live),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (rule.dryRun) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(stringResource(R.string.smart_automation_recent), fontWeight = FontWeight.SemiBold)
                    if (smartAutomation.events.isEmpty()) {
                        Text(stringResource(R.string.smart_automation_no_events), style = MaterialTheme.typography.bodySmall)
                    } else {
                        smartAutomation.events.take(5).forEach { event ->
                            Text(
                                stringResource(R.string.smart_automation_event, event.ruleName, event.outcome) + " — " + event.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_backup_restore_premium)) {
                    Text(stringResource(R.string.ui_export_apextuner_settings_and_an_informational_list_of))
                    Text(stringResource(R.string.ui_purchases_file_contents_other_apps_private_data_active), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { if (entitlement.isPremium) createBackup.launch("ApexTuner-backup.json") else onPremium() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ui_export_local_backup)) }
                    OutlinedButton(
                        onClick = { if (entitlement.isPremium) openBackup.launch(arrayOf("application/json", "text/json", "text/plain")) else onPremium() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ui_restore_from_backup)) }

                    Text(stringResource(R.string.ui_scheduled_saf_backups), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ui_choose_a_folder_explicitly_apextuner_keeps_only_its_own),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { if (entitlement.isPremium) scheduledBackupTree.launch(null) else onPremium() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (prefs.scheduledBackupTreeUri == null) "Choose backup folder" else "Change backup folder") }
                    if (prefs.scheduledBackupTreeUri != null) {
                        Text(stringResource(R.string.ui_folder_grant_saved_locally),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = viewModel::clearScheduledBackupTree,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ui_forget_backup_folder)) }
                    }
                    SettingSwitch(stringResource(R.string.ui_scheduled_backups), stringResource(R.string.ui_create_a_versioned_local_backup_on_the_selected_cadence),
                        prefs.scheduledBackupEnabled,
                        { enabled -> if (entitlement.isPremium) viewModel.setScheduledBackup(enabled) else onPremium() },
                        enabled = entitlement.isPremium && prefs.scheduledBackupTreeUri != null,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaintenanceCadence.entries.forEach { cadence ->
                            FilterChip(
                                selected = prefs.scheduledBackupCadence == cadence,
                                onClick = { viewModel.setScheduledBackupCadence(cadence) },
                                enabled = entitlement.isPremium && prefs.scheduledBackupTreeUri != null,
                                label = { Text(cadence.name) },
                            )
                        }
                    }
                    Text(stringResource(R.string.scheduled_backup_keep_last, prefs.scheduledBackupRetentionCount), style = MaterialTheme.typography.bodySmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(3, 5, 10, 20).forEach { count ->
                            FilterChip(
                                selected = prefs.scheduledBackupRetentionCount == count,
                                onClick = { viewModel.setScheduledBackupRetention(count) },
                                enabled = entitlement.isPremium && prefs.scheduledBackupTreeUri != null,
                                label = { Text(count.toString()) },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_telemetry)) {
                    Text(stringResource(R.string.ui_foreground_refresh_interval), fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(2_000L to "2 s", 5_000L to "5 s", 10_000L to "10 s").forEach { (millis, label) ->
                            FilterChip(selected = prefs.telemetryRefreshMillis == millis, onClick = { viewModel.setTelemetryRefresh(millis) }, label = { Text(label) })
                        }
                    }
                    Text(stringResource(R.string.ui_shorter_intervals_are_more_responsive_longer_intervals), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard(stringResource(R.string.ui_privacy_legal)) {
                    Text(stringResource(R.string.ui_apextuner_s_diagnostic_processing_is_local_to_the_devic))
                    HorizontalDivider()
                    TextButton(onClick = { infoDialog = PRIVACY_TEXT }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_privacy_policy)) }
                    TextButton(onClick = { infoDialog = DATA_DELETION_TEXT }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_data_deletion_instructions)) }
                    TextButton(onClick = { infoDialog = TERMS_TEXT }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_terms_safety_notes)) }
                }
            }
        }
    }

    if (showNotificationHistoryDisclosure) {
        AlertDialog(
            onDismissRequest = { showNotificationHistoryDisclosure = false },
            title = { Text(stringResource(R.string.ui_allow_notification_history)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.ui_android_notification_access_is_broad_while_enabled_the),
                    )
                    Text(stringResource(R.string.ui_apextuner_stores_only_the_source_app_package_title_text),
                    )
                    Text(stringResource(R.string.ui_you_choose_the_retention_window_can_clear_all_stored_hi),
                    )
                    Text(stringResource(R.string.ui_android_may_withhold_or_redact_sensitive_notification_c),
                    )
                    Text(stringResource(R.string.ui_collection_remains_off_unless_you_continue_and_then_exp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotificationHistoryDisclosure = false
                        viewModel.enableNotificationHistoryAfterDisclosure()
                    },
                ) { Text(stringResource(R.string.ui_continue_to_android_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationHistoryDisclosure = false }) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }

    when (val backup = backupState) {
        BackupUiState.Idle -> Unit
        is BackupUiState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ui_apextuner_backup)) },
            text = { Text(backup.message) },
            confirmButton = {},
        )
        is BackupUiState.Message -> AlertDialog(
            onDismissRequest = viewModel::dismissBackupState,
            title = { Text(if (backup.isError) "Backup issue" else "Backup complete") },
            text = { Text(backup.message) },
            confirmButton = { TextButton(onClick = viewModel::dismissBackupState) { Text(stringResource(R.string.ui_close)) } },
        )
        is BackupUiState.RestorePreview -> AlertDialog(
            onDismissRequest = viewModel::dismissBackupState,
            title = { Text(stringResource(R.string.ui_restore_apextuner_settings)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.backup_preview_version, backup.preview.sourceVersion))
                    Text(stringResource(R.string.backup_preview_visible_apps, backup.preview.visibleAppCount))
                    Text(stringResource(R.string.backup_preview_theme_telemetry, backup.preview.preferences.themeMode.name, backup.preview.preferences.telemetryRefreshMillis / 1000))
                    backup.preview.warnings.forEach { Text(stringResource(R.string.backup_preview_warning, it), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { Button(onClick = viewModel::confirmRestore) { Text(stringResource(R.string.ui_restore_safe_settings)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissBackupState) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }

    infoDialog?.let { text ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            title = { Text(stringResource(R.string.ui_apextuner)) },
            text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { infoDialog = null }) { Text(stringResource(R.string.ui_close)) } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}


private fun openWriteSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val statusBar = context.getSystemService(StatusBarManager::class.java)
    val component = ComponentName(context, ApexMonitorTileService::class.java)
    runCatching {
        statusBar.requestAddTileService(
            component,
            context.getString(com.apextuner.feature.settings.R.string.monitor_tile_label),
            Icon.createWithResource(context, com.apextuner.feature.settings.R.drawable.ic_apex_monitor),
            context.mainExecutor,
        ) { }
    }
}

private const val SMART_AUTOMATION_ITEM_INDEX = 6

private const val PRIVACY_TEXT = "ApexTuner processes device telemetry, storage analysis and optimizer recommendations locally. Optional notification history, when explicitly enabled and granted Android Notification access, stores only notification source package, title, text and timestamp in ApexTuner's private local database. The app does not upload notification content, file contents, browsing payloads, device telemetry or diagnostic history to an ApexTuner server. Google Play Billing necessarily exchanges purchase information with Google Play. Optional VPN firewall traffic is discarded locally and is not forwarded, inspected for content, stored, sold or shared by ApexTuner."
private const val DATA_DELETION_TEXT = "ApexTuner stores preferences, scan history, optional notification history and encrypted local entitlement state on this device. Notification History has its own Clear all action. Use Android Settings > Apps > ApexTuner > Storage > Clear storage to remove all local app data. Uninstalling ApexTuner also removes its private local data. The lifetime Premium purchase is managed by Google Play and can be restored through your Google Play account."
private const val TERMS_TEXT = "ApexTuner never guarantees that Android will expose every requested metric or privileged setting on every OEM device. Destructive file operations require explicit review/confirmation. Root/Shizuku functionality is optional and must be intentionally authorized. Thermal protections are never disabled by the normal app."

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.Dark -> R.string.settings_theme_dark
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.System -> R.string.settings_theme_system
    },
)
