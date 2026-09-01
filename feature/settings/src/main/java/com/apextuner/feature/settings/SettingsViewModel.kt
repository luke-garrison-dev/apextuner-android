package com.apextuner.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.backup.BackupPreview
import com.apextuner.core.backup.BackupReadResult
import com.apextuner.core.backup.BackupRestoreManager
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.model.AppPreferences
import com.apextuner.core.model.EntitlementState
import com.apextuner.core.model.MaintenanceCadence
import com.apextuner.core.model.PremiumFeature
import com.apextuner.core.model.ThemeMode
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.system.ForegroundServiceLaunchResult
import com.apextuner.core.system.ForegroundServiceLauncher
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.core.tuning.TemporaryProfileOverrideCoordinator
import com.apextuner.feature.notifications.NotificationHistoryAccessController
import com.apextuner.feature.notifications.NotificationHistoryPreferences
import com.apextuner.feature.notifications.NotificationHistoryScheduler
import com.apextuner.feature.notifications.NotificationHistorySettings
import com.apextuner.feature.settings.automation.AutomationScheduler
import com.apextuner.feature.settings.automation.SmartAutomationRepository
import com.apextuner.feature.settings.automation.SmartAutomationRecovery
import com.apextuner.feature.settings.automation.SmartAutomationPolicy
import com.apextuner.feature.settings.automation.SmartAutomationSnapshot
import com.apextuner.feature.settings.monitor.ApexMonitorService
import com.apextuner.feature.settings.monitor.MonitorRuntimeRegistry
import com.apextuner.feature.settings.monitor.MonitorRuntimeState
import com.apextuner.feature.settings.monitor.MonitorRuntimeSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MonitorStartOutcome {
    Started,
    PremiumRequired,
    OverlayPermissionRequired,
    BlockedByAndroid,
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val entitlementRepository: EntitlementRepository,
    private val smartAutomationRepository: SmartAutomationRepository,
    private val smartAutomationRecovery: SmartAutomationRecovery,
    private val automationScheduler: AutomationScheduler,
    private val tuningController: SafeSystemTuningController,
    private val temporaryProfileOverride: TemporaryProfileOverrideCoordinator,
    private val backupRestoreManager: BackupRestoreManager,
    private val notificationHistoryPreferences: NotificationHistoryPreferences,
    private val notificationHistoryAccessController: NotificationHistoryAccessController,
    private val notificationHistoryScheduler: NotificationHistoryScheduler,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())
    val entitlement: StateFlow<EntitlementState> = entitlementRepository.entitlement
    val monitor: StateFlow<MonitorRuntimeSnapshot> = MonitorRuntimeRegistry.state
    val notificationHistorySettings: StateFlow<NotificationHistorySettings> =
        notificationHistoryPreferences.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NotificationHistorySettings(),
        )

    private val _notificationHistoryAccessGranted =
        MutableStateFlow(notificationHistoryAccessController.isAccessGranted())
    val notificationHistoryAccessGranted: StateFlow<Boolean> =
        _notificationHistoryAccessGranted.asStateFlow()

    val notificationHistoryAvailability =
        notificationHistoryAccessController.availability()

    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()
    private val _smartAutomation = MutableStateFlow(SmartAutomationSnapshot(emptyList(), emptyList()))
    val smartAutomation: StateFlow<SmartAutomationSnapshot> = _smartAutomation.asStateFlow()
    private val _smartAutomationMessage = MutableStateFlow<String?>(null)
    val smartAutomationMessage: StateFlow<String?> = _smartAutomationMessage.asStateFlow()
    private val _settingsActionMessage = MutableStateFlow<String?>(null)
    val settingsActionMessage: StateFlow<String?> = _settingsActionMessage.asStateFlow()
    private var pendingBackupUri: Uri? = null

    init {
        refreshSmartAutomation()
        viewModelScope.launch {
            combine(
                notificationHistoryPreferences.settings,
                entitlementRepository.entitlement,
            ) { settings, _ ->
                NotificationHistoryKey(
                    enabled = settings.enabled,
                    premium = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory),
                )
            }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.enabled) notificationHistoryScheduler.ensureScheduled()
                    notificationHistoryAccessController.setCollectionComponentEnabled(
                        key.enabled && key.premium,
                    )
                    _notificationHistoryAccessGranted.value =
                        notificationHistoryAccessController.isAccessGranted()
                }
        }
    }

    fun refreshSmartAutomation() = mutate {
        val recovery = smartAutomationRecovery.reconcileOwnedProfile()
        _smartAutomation.value = smartAutomationRepository.snapshot()
        if (recovery != null) _smartAutomationMessage.value = recovery
    }

    fun dismissSmartAutomationMessage() {
        _smartAutomationMessage.value = null
    }

    fun dismissSettingsActionMessage() {
        _settingsActionMessage.value = null
    }

    fun onAutomationNotificationPermissionChanged(granted: Boolean) = mutate {
        if (granted) smartAutomationRepository.clearNotificationCooldowns()
        _smartAutomation.value = smartAutomationRepository.snapshot()
        val notificationRuleEnabled = _smartAutomation.value.rules.any {
            it.enabled && SmartAutomationPolicy.isNotificationRule(it) && !it.dryRun
        }
        if (notificationRuleEnabled) {
            _smartAutomationMessage.value = if (granted) {
                "Automation notifications are ready. Notification rules can evaluate immediately."
            } else {
                "Notification permission was not granted. Live notification rules can evaluate, but Android cannot show their reminders."
            }
        }
    }

    fun setSmartRuleEnabled(id: String, enabled: Boolean) = mutate {
        if (enabled && !entitlementRepository.entitlement.value.isPremium) return@mutate
        smartAutomationRepository.setEnabled(id, enabled)
        val recovery = if (!enabled) smartAutomationRecovery.reconcileOwnedProfile() else null
        _smartAutomation.value = smartAutomationRepository.snapshot()
        if (recovery != null) _smartAutomationMessage.value = recovery
        automationScheduler.syncSmartAutomation(
            entitlementRepository.entitlement.value.isPremium,
            _smartAutomation.value.rules.any { it.enabled },
        )
    }

    fun setSmartRuleDryRun(id: String, dryRun: Boolean) = mutate {
        if (!entitlementRepository.entitlement.value.isPremium) return@mutate
        smartAutomationRepository.setDryRun(id, dryRun)
        _smartAutomation.value = smartAutomationRepository.snapshot()
    }

    fun setSmartRuleThreshold(id: String, thresholdValue: Double) = mutate {
        if (!entitlementRepository.entitlement.value.isPremium) return@mutate
        smartAutomationRepository.setThreshold(id, thresholdValue)
        _smartAutomation.value = smartAutomationRepository.snapshot()
    }

    fun setSmartRuleCooldown(id: String, cooldownMillis: Long) = mutate {
        if (!entitlementRepository.entitlement.value.isPremium) return@mutate
        smartAutomationRepository.setCooldown(id, cooldownMillis)
        _smartAutomation.value = smartAutomationRepository.snapshot()
    }

    fun setThemeMode(mode: ThemeMode) = mutate { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = mutate { preferencesRepository.setDynamicColor(enabled) }
    fun setHaptics(enabled: Boolean) = mutate { preferencesRepository.setHapticsEnabled(enabled) }
    fun setAdvancedTools(enabled: Boolean) = mutate { preferencesRepository.setShowAdvancedTools(enabled) }
    fun setTelemetryRefresh(millis: Long) = mutate { preferencesRepository.setTelemetryRefreshMillis(millis) }

    fun setScheduledMaintenance(enabled: Boolean) = mutate {
        if (enabled && !entitlementRepository.entitlement.value.isPremium) return@mutate
        preferencesRepository.setScheduledMaintenanceEnabled(enabled)
    }

    fun setMaintenanceCadence(cadence: MaintenanceCadence) = mutate {
        preferencesRepository.setMaintenanceCadence(cadence)
    }

    fun setNightBatteryProfile(enabled: Boolean) = mutate {
        if (enabled && !entitlementRepository.entitlement.value.isPremium) return@mutate
        if (!enabled && preferences.value.nightBatteryProfileAppliedByAutomation) restoreAutomationProfile()
        preferencesRepository.setNightBatteryProfileEnabled(enabled)
    }

    fun setScheduledBackup(enabled: Boolean) = mutate {
        if (enabled && (!entitlementRepository.entitlement.value.isPremium || preferences.value.scheduledBackupTreeUri.isNullOrBlank())) {
            return@mutate
        }
        preferencesRepository.setScheduledBackupEnabled(enabled)
    }

    fun setScheduledBackupCadence(cadence: MaintenanceCadence) = mutate {
        preferencesRepository.setScheduledBackupCadence(cadence)
    }

    fun setScheduledBackupRetention(count: Int) = mutate {
        preferencesRepository.setScheduledBackupRetentionCount(count)
    }

    fun setScheduledBackupTree(uri: Uri) = mutate("The selected folder could not be kept for scheduled backups.") {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        preferencesRepository.setScheduledBackupTreeUri(uri.toString())
    }

    fun clearScheduledBackupTree() = mutate {
        val existing = preferences.value.scheduledBackupTreeUri?.let(Uri::parse)
        preferencesRepository.setScheduledBackupEnabled(false)
        preferencesRepository.setScheduledBackupTreeUri(null)
        if (existing != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    existing,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    fun enableNotificationHistoryAfterDisclosure() = mutate {
        if (!notificationHistoryAvailability.available) return@mutate
        if (!entitlementRepository.hasAccess(PremiumFeature.NotificationHistory)) return@mutate
        notificationHistoryPreferences.setEnabled(true)
        notificationHistoryScheduler.ensureScheduled()
        notificationHistoryAccessController.setCollectionComponentEnabled(true)
        _notificationHistoryAccessGranted.value =
            notificationHistoryAccessController.isAccessGranted()
        if (!notificationHistoryAccessController.openAccessSettings()) {
            notificationHistoryPreferences.setEnabled(false)
            notificationHistoryAccessController.setCollectionComponentEnabled(false)
        }
    }

    fun disableNotificationHistory() = mutate {
        notificationHistoryPreferences.setEnabled(false)
        notificationHistoryAccessController.setCollectionComponentEnabled(false)
        _notificationHistoryAccessGranted.value =
            notificationHistoryAccessController.isAccessGranted()
    }

    fun openNotificationAccessSettings() {
        if (!notificationHistorySettings.value.enabled) return
        notificationHistoryAccessController.openAccessSettings(makeComponentVisible = true)
    }

    fun refreshNotificationHistoryAccess() {
        val enabled = notificationHistorySettings.value.enabled
        val premium = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory)
        notificationHistoryAccessController.setCollectionComponentEnabled(enabled && premium)
        _notificationHistoryAccessGranted.value =
            notificationHistoryAccessController.isAccessGranted()
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupUiState.Working("Creating local backup…")
            try {
                val result = backupRestoreManager.write(uri)
                _backupState.value = BackupUiState.Message(
                    "Backup created (${result.visibleAppCount} visible apps, ${result.bytesWritten / 1024} KiB).",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _backupState.value = BackupUiState.Message(
                    "Backup could not be written to the selected destination.",
                    isError = true,
                )
            }
        }
    }

    fun inspectBackup(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupUiState.Working("Validating backup…")
            try {
                when (val result = backupRestoreManager.read(uri)) {
                    is BackupReadResult.Invalid -> {
                        pendingBackupUri = null
                        _backupState.value = BackupUiState.Message(result.reason, isError = true)
                    }
                    is BackupReadResult.Valid -> {
                        pendingBackupUri = uri
                        _backupState.value = BackupUiState.RestorePreview(result.preview)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                pendingBackupUri = null
                _backupState.value = BackupUiState.Message(
                    "The selected backup could not be inspected safely.",
                    isError = true,
                )
            }
        }
    }

    fun confirmRestore() {
        val uri = pendingBackupUri ?: return
        viewModelScope.launch {
            _backupState.value = BackupUiState.Working("Restoring safe ApexTuner settings…")
            try {
                when (val result = backupRestoreManager.apply(uri)) {
                    is BackupReadResult.Invalid ->
                        _backupState.value = BackupUiState.Message(result.reason, isError = true)
                    is BackupReadResult.Valid ->
                        _backupState.value = BackupUiState.Message(
                            "Settings restored. Active system mutations, purchases and privileged authorizations were not imported.",
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _backupState.value = BackupUiState.Message(
                    "Android could not commit the restored settings. Existing preferences were left under DataStore's transactional guarantees.",
                    isError = true,
                )
            } finally {
                pendingBackupUri = null
            }
        }
    }

    fun dismissBackupState() {
        pendingBackupUri = null
        _backupState.value = BackupUiState.Idle
    }

    fun startMonitor(): MonitorStartOutcome {
        if (!entitlementRepository.entitlement.value.isPremium) return MonitorStartOutcome.PremiumRequired
        if (!Settings.canDrawOverlays(context)) return MonitorStartOutcome.OverlayPermissionRequired
        val result = ForegroundServiceLauncher.start(
            context,
            Intent(context, ApexMonitorService::class.java).setAction(ApexMonitorService.ACTION_START),
        )
        return if (result == ForegroundServiceLaunchResult.Started) {
            MonitorStartOutcome.Started
        } else {
            MonitorRuntimeRegistry.update(
                MonitorRuntimeState.Error,
                "Android could not start the monitor service. Keep ApexTuner visible and try again.",
            )
            MonitorStartOutcome.BlockedByAndroid
        }
    }

    fun stopMonitor() {
        context.stopService(Intent(context, ApexMonitorService::class.java))
    }

    private suspend fun restoreAutomationProfile() {
        if (temporaryProfileOverride.isActive()) return
        if (tuningController.activeProfile() != SystemProfile.Battery) {
            preferencesRepository.setNightBatteryProfileAppliedByAutomation(false)
            return
        }
        when (tuningController.restoreBalanced()) {
            is ProfileApplyResult.Applied ->
                preferencesRepository.setNightBatteryProfileAppliedByAutomation(false)
            else -> Unit
        }
    }

    private fun mutate(
        failureMessage: String? = "ApexTuner could not save this change. Please try again.",
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (failureMessage != null) _settingsActionMessage.value = failureMessage
            }
        }
    }

    private data class NotificationHistoryKey(
        val enabled: Boolean,
        val premium: Boolean,
    )
}

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class Working(val message: String) : BackupUiState
    data class RestorePreview(val preview: BackupPreview) : BackupUiState
    data class Message(val message: String, val isError: Boolean = false) : BackupUiState
}
