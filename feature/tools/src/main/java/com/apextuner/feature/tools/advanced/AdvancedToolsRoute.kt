package com.apextuner.feature.tools.advanced

import androidx.compose.ui.res.stringResource
import com.apextuner.feature.tools.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.ui.ApexCard
import com.apextuner.core.ui.ApexLayout

private sealed interface PendingPrivilegedAction {
    data class Freeze(val packageName: String, val label: String) : PendingPrivilegedAction
    data class Unfreeze(val packageName: String, val label: String) : PendingPrivilegedAction
    data class ForceStop(val packageName: String, val label: String) : PendingPrivilegedAction
    data class Profile(val profile: SystemProfile) : PendingPrivilegedAction
    data object RestoreProfile : PendingPrivilegedAction
}

@Composable
fun AdvancedToolsRoute(
    onBack: (() -> Unit)? = null,
    viewModel: AdvancedToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingPrivilegedAction?>(null) }

    pending?.let { action ->
        val (title, body) = when (action) {
            is PendingPrivilegedAction.Freeze -> "Freeze ${action.label}?" to
                "ApexTuner will disable only ${action.packageName} for the current user through the selected privileged backend. The exact prior enabled state is saved for explicit restoration."
            is PendingPrivilegedAction.Unfreeze -> "Restore ${action.label}?" to
                "ApexTuner will restore the PackageManager enabled state saved when this app was frozen."
            is PendingPrivilegedAction.ForceStop -> "Force-stop ${action.label}?" to
                "ApexTuner will ask Android to force-stop only ${action.packageName}. This is a one-time user action and is never scheduled or used as a RAM booster."
            is PendingPrivilegedAction.Profile -> "Apply ${action.profile.name} CPU profile?" to
                "ApexTuner will snapshot verified CPU governor/frequency values, change only allow-listed cpufreq policy controls, and retain a rollback baseline. Android thermal protections remain enabled and platform-managed."
            PendingPrivilegedAction.RestoreProfile -> "Restore privileged tuning?" to
                "ApexTuner will restore the saved CPU governor/frequency baseline and the normal system-profile baseline. Thermal protections are never modified."
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Button(onClick = {
                    val chosen = pending
                    pending = null
                    when (chosen) {
                        is PendingPrivilegedAction.Freeze -> viewModel.freeze(chosen.packageName)
                        is PendingPrivilegedAction.Unfreeze -> viewModel.unfreeze(chosen.packageName)
                        is PendingPrivilegedAction.ForceStop -> viewModel.forceStop(chosen.packageName)
                        is PendingPrivilegedAction.Profile -> viewModel.applyExtended(chosen.profile)
                        PendingPrivilegedAction.RestoreProfile -> viewModel.restoreExtended()
                        null -> Unit
                    }
                }) { Text(stringResource(R.string.ui_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (val current = state) {
            AdvancedToolsUiState.Loading -> CenteredStatus("Checking advanced access…")
            is AdvancedToolsUiState.Error -> CenteredStatus(current.message, action = viewModel::refresh)
            is AdvancedToolsUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ApexLayout.horizontalPadding(), vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        onBack?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.ui_back_to_tools)) } }
                        Text(stringResource(R.string.ui_advanced_access), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ui_typed_allow_listed_shizuku_root_operations_only_pro_onl),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    ApexCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.ui_backend), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrivilegedBackend.entries.forEach { backend ->
                                    FilterChip(
                                        selected = current.selectedBackend == backend,
                                        onClick = { viewModel.setBackend(backend) },
                                        label = { Text(backend.name) },
                                    )
                                }
                            }
                            Text(stringResource(R.string.advanced_shizuku_permission, stringResource(if (current.status.shizukuPermissionGranted) R.string.advanced_permission_granted else R.string.advanced_permission_not_granted)))
                            Text(stringResource(R.string.advanced_root_status, current.status.lastRootAuthorization.name))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = viewModel::requestShizukuPermission, enabled = !current.busy) { Text(stringResource(R.string.ui_request_shizuku)) }
                                OutlinedButton(onClick = viewModel::testRoot, enabled = !current.busy) { Text(stringResource(R.string.ui_test_root)) }
                            }
                        }
                    }
                }
                item {
                    ApexCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_verified_read_only_checks), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            PrivilegedReadCommand.entries.forEach { command ->
                                OutlinedButton(onClick = { viewModel.execute(command) }, enabled = !current.busy) { Text(command.title) }
                            }
                            Text(stringResource(R.string.ui_reversible_animation_scales), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = viewModel::applyFastAnimations, enabled = !current.busy) { Text(stringResource(R.string.ui_text_0_5)) }
                                OutlinedButton(onClick = viewModel::applyNormalAnimations, enabled = !current.busy) { Text(stringResource(R.string.ui_text_1)) }
                                OutlinedButton(onClick = viewModel::restoreAnimations, enabled = !current.busy) { Text(stringResource(R.string.ui_restore)) }
                            }
                        }
                    }
                }
                item {
                    ApexCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_pro_cpu_tuning), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ui_cpu_governor_frequency_targets_are_range_checked_agains))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = viewModel::inspectCpu, enabled = !current.busy) { Text(stringResource(R.string.ui_inspect)) }
                                OutlinedButton(onClick = { pending = PendingPrivilegedAction.Profile(SystemProfile.Battery) }, enabled = !current.busy) { Text(stringResource(R.string.ui_battery)) }
                                OutlinedButton(onClick = { pending = PendingPrivilegedAction.Profile(SystemProfile.Performance) }, enabled = !current.busy) { Text(stringResource(R.string.ui_performance)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pending = PendingPrivilegedAction.Profile(SystemProfile.Gaming) }, enabled = !current.busy) { Text(stringResource(R.string.ui_gaming)) }
                                OutlinedButton(onClick = { pending = PendingPrivilegedAction.RestoreProfile }, enabled = !current.busy) { Text(stringResource(R.string.ui_restore_baseline)) }
                            }
                            Text(stringResource(R.string.advanced_verified_policies, current.cpuTuningStatus.availablePolicies, current.cpuTuningStatus.thermalPolicy))
                        }
                    }
                }
                item {
                    ApexCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_system_dalvik_cache), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ui_a_rollback_safe_cache_clear_is_not_exposed_by_android_a))
                            OutlinedButton(onClick = viewModel::explainCacheSafety, enabled = !current.busy) { Text(stringResource(R.string.ui_why_unavailable)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.ui_per_app_actions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ui_single_app_explicit_actions_only_protected_system_packa), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(current.packageTargets, key = { it.packageName }) { app ->
                    ApexCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { pending = PendingPrivilegedAction.Freeze(app.packageName, app.label) },
                                    enabled = !current.busy,
                                ) { Text(stringResource(R.string.ui_freeze)) }
                                OutlinedButton(
                                    onClick = { pending = PendingPrivilegedAction.Unfreeze(app.packageName, app.label) },
                                    enabled = !current.busy,
                                ) { Text(stringResource(R.string.ui_restore)) }
                                OutlinedButton(
                                    onClick = { pending = PendingPrivilegedAction.ForceStop(app.packageName, app.label) },
                                    enabled = !current.busy,
                                ) { Text(stringResource(R.string.ui_force_stop)) }
                            }
                        }
                    }
                }
                if (current.busy) item { CircularProgressIndicator() }
                current.message?.let { message -> item { Text(message) } }
                current.output?.let { output ->
                    item {
                        ApexCard {
                            Text(output, modifier = Modifier.padding(14.dp), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredStatus(
    message: String,
    action: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, modifier = Modifier.padding(16.dp))
        action?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.ui_retry)) } }
    }
}
