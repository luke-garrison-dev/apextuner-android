package com.apextuner.feature.tools.game

import androidx.compose.ui.res.stringResource
import com.apextuner.feature.tools.R
import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
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
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.apextuner.feature.tools.recording.ScreenRecordingRuntime
import com.apextuner.feature.tools.recording.ScreenRecordingService
import com.apextuner.feature.tools.recording.ScreenRecordingState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.system.ForegroundServiceLaunchResult
import com.apextuner.core.system.ForegroundServiceLauncher
import com.apextuner.core.ui.ApexLayout
import com.apextuner.core.util.ByteSizeFormatter
import java.util.Locale

@Composable
fun GameBoosterRoute(
    onBack: () -> Unit,
    premiumEnabled: Boolean,
    onUpgrade: () -> Unit,
    viewModel: GameBoosterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var specialAccessRefreshToken by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) specialAccessRefreshToken += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val recording by ScreenRecordingRuntime.state.collectAsStateWithLifecycle()
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val launch = ForegroundServiceLauncher.start(
                context,
                Intent(context, ScreenRecordingService::class.java)
                    .setAction(ScreenRecordingService.ACTION_START)
                    .putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data),
            )
            if (launch != ForegroundServiceLaunchResult.Started) {
                ScreenRecordingRuntime.set(
                    ScreenRecordingState.Failed,
                    "Android could not start screen recording. Keep ApexTuner visible and try again.",
                )
            }
        }
    }
    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        else ScreenRecordingRuntime.set(ScreenRecordingState.Failed, "Android 8/9 storage permission is required to save the recording.")
    }
    val continueToCaptureConsent = {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            continueToCaptureConsent()
        } else {
            ScreenRecordingRuntime.set(
                ScreenRecordingState.Failed,
                "Enable ApexTuner notifications before recording so Android can keep the Stop & save control visible.",
            )
        }
    }
    val requestScreenCapture = {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> {
                ScreenRecordingRuntime.set(
                    ScreenRecordingState.Failed,
                    "Enable ApexTuner notifications before recording so Android can keep the Stop & save control visible.",
                )
            }
            else -> continueToCaptureConsent()
        }
    }
    val sessionNotificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            viewModel.startSelected()
        } else {
            viewModel.setUserMessage(context.getString(R.string.game_session_notifications_required))
        }
    }
    val requestStartSession = {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                sessionNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> {
                viewModel.setUserMessage(context.getString(R.string.game_session_notifications_required))
            }
            else -> viewModel.startSelected()
        }
    }
    var search by rememberSaveable { mutableStateOf("") }
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    // Refresh special-access checks immediately when the user returns from Android Settings.
    val canWrite = remember(context, specialAccessRefreshToken) { Settings.System.canWrite(context) }
    val canDnd = remember(notificationManager, specialAccessRefreshToken) { notificationManager.isNotificationPolicyAccessGranted }
    val filtered = remember(state.apps, search) {
        val q = search.trim()
        if (q.isEmpty()) state.apps else state.apps.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Outlined.SportsEsports, contentDescription = null)
                    Column { Text(stringResource(R.string.ui_game_session_booster), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(stringResource(R.string.ui_reversible_user_controlled_session_tuning_without_fake), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (!premiumEnabled) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.ui_premium_feature), fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.ui_game_session_booster_applies_only_reversible_android_se)); Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_view_apextuner_premium)) } } }
            }
            state.message?.let { msg -> item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(msg); TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.ui_dismiss)) } } } } }
            if (state.session.active) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ui_session_active), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(state.session.appLabel ?: state.session.packageName ?: "Game")
                        Text(stringResource(R.string.ui_apextuner_will_also_attempt_a_safety_restore_after_six), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = viewModel::stop, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_end_session_restore)) }
                    }
                }
            } else if (premiumEnabled) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.ui_session_controls), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            val selected = state.selectedApp
                            if (selected == null) {
                                Text(stringResource(R.string.game_choose_app_profile), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(selected.label, fontWeight = FontWeight.SemiBold)
                                Text(selected.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OptionSwitch(stringResource(R.string.game_option_profile), stringResource(R.string.game_option_profile_body), state.selectedOptions.useGamingProfile, viewModel::setGamingProfile)
                                if (state.selectedOptions.useGamingProfile && !canWrite) OutlinedButton(onClick = { context.openSetting(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageSpecific = true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_grant_modify_system_settings)) }
                                OptionSwitch(stringResource(R.string.game_option_dnd), stringResource(R.string.game_option_dnd_body), state.selectedOptions.silenceInterruptions, viewModel::setSilenceInterruptions)
                                if (state.selectedOptions.silenceInterruptions && !canDnd) OutlinedButton(onClick = { context.openSetting(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_grant_do_not_disturb_access)) }
                                Text(stringResource(R.string.game_thermal_threshold, state.selectedOptions.thermalWarningCelsius), style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = state.selectedOptions.thermalWarningCelsius.toFloat(),
                                    onValueChange = { viewModel.setThermalWarningCelsius(it.toDouble()) },
                                    valueRange = GameProfileStore.MIN_THERMAL_WARNING.toFloat()..GameProfileStore.MAX_THERMAL_WARNING.toFloat(),
                                    steps = 14,
                                )
                                Button(
                                    onClick = requestStartSession,
                                    enabled = !state.busy && (!state.selectedOptions.useGamingProfile || canWrite) && (!state.selectedOptions.silenceInterruptions || canDnd),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.game_launch_saved_profile)) }
                            }
                            Text(stringResource(R.string.ui_apextuner_does_not_disable_android_thermal_protection_f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.ui_screen_recording), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ui_android_s_system_capture_consent_is_required_every_time), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.recording_status_summary, recording.state.name, recording.message?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            if (recording.state in setOf(ScreenRecordingState.Recording, ScreenRecordingState.Starting, ScreenRecordingState.Stopping)) {
                                OutlinedButton(
                                    onClick = {
                                        val stopDelivered = runCatching {
                                            context.startService(
                                                Intent(context, ScreenRecordingService::class.java)
                                                    .setAction(ScreenRecordingService.ACTION_STOP),
                                            )
                                        }.getOrNull() != null
                                        if (!stopDelivered) {
                                            ScreenRecordingRuntime.set(
                                                recording.state,
                                                "Android could not deliver the stop request. Keep ApexTuner visible and try again.",
                                            )
                                        }
                                    },
                                    enabled = recording.state != ScreenRecordingState.Stopping,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ui_stop_save_recording)) }
                            } else {
                                Button(onClick = requestScreenCapture, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_start_screen_recording)) }
                            }
                        }
                    }
                }
                if (state.recentSessions.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.game_recent_sessions), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            state.recentSessions.take(5).forEach { session ->
                                val minutes = session.durationMillis / 60_000L
                                val battery = session.batteryDeltaPercent?.let { String.format(Locale.US, "%+d%%", it) } ?: stringResource(R.string.game_metric_unavailable)
                                val peak = session.peakBatteryTemperatureCelsius?.let { String.format(Locale.US, "%.1f °C", it) } ?: stringResource(R.string.game_metric_unavailable)
                                val network = listOfNotNull(session.receivedBytes, session.transmittedBytes).takeIf { it.isNotEmpty() }?.sumOf { it }?.let(ByteSizeFormatter::format) ?: stringResource(R.string.game_metric_unavailable)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(session.appLabel, fontWeight = FontWeight.Medium)
                                    Text(stringResource(R.string.game_session_metrics, minutes, battery, peak, network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item { TextField(value = search, onValueChange = { search = it.take(120) }, label = { Text(stringResource(R.string.ui_find_a_launchable_app)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                when {
                    !state.appsLoaded -> item { Text(stringResource(R.string.ui_loading_launchable_apps)) }
                    filtered.isEmpty() && search.isBlank() -> item { Text(stringResource(R.string.ui_no_launchable_apps)) }
                    filtered.isEmpty() -> item { Text(stringResource(R.string.ui_no_launchable_apps_match_this_filter)) }
                }
                items(filtered.take(250), key = { it.packageName }) { app ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) { Text(app.label, fontWeight = FontWeight.SemiBold); Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            OutlinedButton(onClick = { viewModel.selectApp(app) }, enabled = !state.busy) { Text(stringResource(R.string.game_configure)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun Context.openSetting(action: String, packageSpecific: Boolean = false) {
    val intent = Intent(action).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageSpecific) data = android.net.Uri.parse("package:$packageName")
    }
    runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
