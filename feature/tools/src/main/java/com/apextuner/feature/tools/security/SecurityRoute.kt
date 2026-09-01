package com.apextuner.feature.tools.security

import androidx.compose.ui.res.stringResource
import com.apextuner.feature.tools.R
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.ContentPasteOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import com.apextuner.core.ui.ApexCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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

@Composable
fun SecurityRoute(onBack: (() -> Unit)? = null, viewModel: SecurityViewModel = hiltViewModel()) {
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (val current = state) {
            SecurityUiState.Loading -> Centered("Checking public Android security signals…", true)
            is SecurityUiState.Error -> Centered(current.message, false, "Retry", viewModel::refresh)
            is SecurityUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    if (onBack != null) OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ui_back_to_tools)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, contentDescription = null)
                        Column {
                            Text(stringResource(R.string.ui_privacy_security), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.ui_authoritative_public_signals_only_no_invented_security), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                current.message?.let { item { InfoCard(it) } }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_device_posture), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Metric("Secure lock screen", if (current.snapshot.secureScreenLock) "Configured" else "Not configured")
                            Metric("Device locked now", if (current.snapshot.deviceLockedNow) "Yes" else "No")
                            Metric("Potential su binary", if (current.snapshot.rootBinaryPotentiallyPresent) "Detected — authorization unknown" else "Not detected in known locations")
                            Metric("ApexTuner unknown-source install access", if (current.snapshot.appCanInstallUnknownPackages) "Allowed" else "Not allowed")
                            Metric(
                                stringResource(R.string.security_patch_level),
                                current.snapshot.securityPatchLevel?.let { level ->
                                    current.snapshot.securityPatchAgeDays?.let { days -> stringResource(R.string.security_patch_age, level, days) } ?: level
                                } ?: stringResource(R.string.security_unavailable),
                            )
                            Metric(
                                stringResource(R.string.security_advanced_protection),
                                when {
                                    !current.snapshot.advancedProtectionSupported -> stringResource(R.string.security_not_supported)
                                    current.snapshot.advancedProtectionEnabled == true -> stringResource(R.string.security_enabled)
                                    current.snapshot.advancedProtectionEnabled == false -> stringResource(R.string.security_disabled)
                                    else -> stringResource(R.string.security_unavailable)
                                },
                            )
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ContentPasteOff, contentDescription = null)
                                Text(stringResource(R.string.ui_clipboard_cleaner), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            Text(stringResource(R.string.ui_android_may_hide_clipboard_contents_when_apextuner_is_n),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = viewModel::clearClipboard, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_clear_clipboard_now)) }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_system_security_surfaces), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            OutlinedButton(onClick = { context.openSettingsSafely(Settings.ACTION_SECURITY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_open_android_security_settings)) }
                            OutlinedButton(onClick = { context.openSettingsSafely(Settings.ACTION_PRIVACY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_open_privacy_settings)) }
                            OutlinedButton(onClick = { context.openSettingsSafely(Settings.ACTION_APPLICATION_SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_open_app_permissions_settings)) }
                        }
                    }
                }
                items(current.snapshot.diagnostics) { diagnostic -> InfoCard(diagnostic) }
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String) {
    ApexMetricRow(label = label, value = value)
}

@Composable private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(text, Modifier.weight(1f))
        }
    }
}

@Composable private fun Centered(text: String, progress: Boolean, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (progress) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)) }
        Text(text, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onAction) { Text(action) } }
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
