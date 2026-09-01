package com.apextuner.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apextuner.core.model.AppPreferences
import com.apextuner.core.model.MaintenanceCadence
import com.apextuner.core.model.PrivilegedCpuPolicyBackup
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.SystemProfileBackup
import com.apextuner.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.apextunerDataStore by preferencesDataStore(name = "apextuner_preferences")

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setShowAdvancedTools(enabled: Boolean)
    suspend fun setTelemetryRefreshMillis(value: Long)
    suspend fun setScheduledMaintenanceEnabled(enabled: Boolean)
    suspend fun setMaintenanceCadence(cadence: MaintenanceCadence)
    suspend fun setNightBatteryProfileEnabled(enabled: Boolean)
    suspend fun setNightBatteryProfileAppliedByAutomation(applied: Boolean)
    suspend fun setScheduledBackupEnabled(enabled: Boolean)
    suspend fun setScheduledBackupCadence(cadence: MaintenanceCadence)
    suspend fun setScheduledBackupRetentionCount(count: Int)
    suspend fun setScheduledBackupTreeUri(uri: String?)
    suspend fun saveSystemProfileBackup(backup: SystemProfileBackup)
    suspend fun clearSystemProfileBackup()
    suspend fun restoreUserPreferences(preferences: AppPreferences)
}

@Singleton
class DataStorePreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PreferencesRepository {

    override val preferences: Flow<AppPreferences> = context.apextunerDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw throwable
        }
        .map(::toPreferences)

    override suspend fun setThemeMode(mode: ThemeMode) = update(Keys.ThemeMode, mode.name)
    override suspend fun setDynamicColor(enabled: Boolean) = update(Keys.DynamicColor, enabled)
    override suspend fun setHapticsEnabled(enabled: Boolean) = update(Keys.HapticsEnabled, enabled)
    override suspend fun setShowAdvancedTools(enabled: Boolean) = update(Keys.ShowAdvancedTools, enabled)
    override suspend fun setScheduledMaintenanceEnabled(enabled: Boolean) = update(Keys.ScheduledMaintenanceEnabled, enabled)
    override suspend fun setMaintenanceCadence(cadence: MaintenanceCadence) = update(Keys.MaintenanceCadence, cadence.name)
    override suspend fun setNightBatteryProfileEnabled(enabled: Boolean) = update(Keys.NightBatteryProfileEnabled, enabled)
    override suspend fun setNightBatteryProfileAppliedByAutomation(applied: Boolean) = update(Keys.NightBatteryProfileAppliedByAutomation, applied)
    override suspend fun setScheduledBackupEnabled(enabled: Boolean) = update(Keys.ScheduledBackupEnabled, enabled)
    override suspend fun setScheduledBackupCadence(cadence: MaintenanceCadence) = update(Keys.ScheduledBackupCadence, cadence.name)

    override suspend fun setScheduledBackupRetentionCount(count: Int) {
        require(count in MIN_BACKUP_RETENTION..MAX_BACKUP_RETENTION) { "Scheduled backup retention is outside the supported range." }
        update(Keys.ScheduledBackupRetentionCount, count)
    }

    override suspend fun setScheduledBackupTreeUri(uri: String?) {
        context.apextunerDataStore.edit { values ->
            if (uri.isNullOrBlank()) {
                values.remove(Keys.ScheduledBackupTreeUri)
                values[Keys.ScheduledBackupEnabled] = false
            } else {
                require(uri.length <= MAX_URI_LENGTH) { "Scheduled backup location is too long." }
                values[Keys.ScheduledBackupTreeUri] = uri
            }
        }
    }

    override suspend fun saveSystemProfileBackup(backup: SystemProfileBackup) {
        context.apextunerDataStore.edit { values ->
            values[Keys.ProfileActive] = true
            values[Keys.ProfileName] = backup.activeProfile.name
            values[Keys.ProfileTimeout] = backup.originalScreenOffTimeoutMillis
            values[Keys.ProfileHaptics] = backup.originalHapticFeedbackEnabled
            backup.legacyOriginalMasterSyncEnabled?.let { values[Keys.ProfileSync] = it }
                ?: values.remove(Keys.ProfileSync)
            values[Keys.ProfileMutationPending] = backup.mutationPending
            values[Keys.ProfilePrivilegedMutationPending] = backup.privilegedMutationPending
            val encodedPolicies = backup.privilegedCpuPolicies.mapNotNull(::encodeCpuPolicy).toSet()
            if (encodedPolicies.isEmpty()) values.remove(Keys.ProfileCpuPolicies)
            else values[Keys.ProfileCpuPolicies] = encodedPolicies
        }
    }

    override suspend fun clearSystemProfileBackup() {
        context.apextunerDataStore.edit { values ->
            values.remove(Keys.ProfileActive)
            values.remove(Keys.ProfileName)
            values.remove(Keys.ProfileTimeout)
            values.remove(Keys.ProfileHaptics)
            values.remove(Keys.ProfileSync)
            values.remove(Keys.ProfileMutationPending)
            values.remove(Keys.ProfileCpuPolicies)
            values.remove(Keys.ProfilePrivilegedMutationPending)
        }
    }

    override suspend fun restoreUserPreferences(preferences: AppPreferences) {
        context.apextunerDataStore.edit { values ->
            values[Keys.ThemeMode] = preferences.themeMode.name
            values[Keys.DynamicColor] = preferences.dynamicColor
            values[Keys.HapticsEnabled] = preferences.hapticsEnabled
            values[Keys.ShowAdvancedTools] = preferences.showAdvancedTools
            values[Keys.TelemetryRefreshMillis] = preferences.telemetryRefreshMillis.coerceIn(MIN_REFRESH_MILLIS, MAX_REFRESH_MILLIS)
            values[Keys.ScheduledMaintenanceEnabled] = preferences.scheduledMaintenanceEnabled
            values[Keys.MaintenanceCadence] = preferences.maintenanceCadence.name
            values[Keys.NightBatteryProfileEnabled] = preferences.nightBatteryProfileEnabled
            values[Keys.NightBatteryProfileAppliedByAutomation] = false
            values[Keys.ScheduledBackupEnabled] = false
            values[Keys.ScheduledBackupCadence] = preferences.scheduledBackupCadence.name
            values[Keys.ScheduledBackupRetentionCount] =
                preferences.scheduledBackupRetentionCount.coerceIn(MIN_BACKUP_RETENTION, MAX_BACKUP_RETENTION)
            values.remove(Keys.ScheduledBackupTreeUri)
            // Active system-profile transactions and SAF grants are intentionally never imported.
            values.remove(Keys.ProfileActive)
            values.remove(Keys.ProfileName)
            values.remove(Keys.ProfileTimeout)
            values.remove(Keys.ProfileHaptics)
            values.remove(Keys.ProfileSync)
            values.remove(Keys.ProfileMutationPending)
            values.remove(Keys.ProfileCpuPolicies)
            values.remove(Keys.ProfilePrivilegedMutationPending)
        }
    }

    override suspend fun setTelemetryRefreshMillis(value: Long) {
        require(value in MIN_REFRESH_MILLIS..MAX_REFRESH_MILLIS) {
            "Telemetry refresh interval must be between $MIN_REFRESH_MILLIS and $MAX_REFRESH_MILLIS ms."
        }
        update(Keys.TelemetryRefreshMillis, value)
    }

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.apextunerDataStore.edit { mutable -> mutable[key] = value }
    }

    private fun toPreferences(values: Preferences): AppPreferences {
        val theme = values[Keys.ThemeMode]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.Dark
        val cpuPolicies = values[Keys.ProfileCpuPolicies].orEmpty()
            .mapNotNull(::decodeCpuPolicy)
            .distinctBy { it.policyId }
            .sortedBy { it.policyId }
        val profileBackup = if (values[Keys.ProfileActive] == true) {
            val profile = values[Keys.ProfileName]
                ?.let { stored -> SystemProfile.entries.firstOrNull { it.name == stored } }
            val timeout = values[Keys.ProfileTimeout]
            val haptics = values[Keys.ProfileHaptics]
            val legacySync = values[Keys.ProfileSync]
            if (profile != null && timeout != null && haptics != null) {
                SystemProfileBackup(
                    originalScreenOffTimeoutMillis = timeout,
                    originalHapticFeedbackEnabled = haptics,
                    legacyOriginalMasterSyncEnabled = legacySync,
                    activeProfile = profile,
                    mutationPending = values[Keys.ProfileMutationPending] ?: false,
                    privilegedCpuPolicies = cpuPolicies,
                    privilegedMutationPending = values[Keys.ProfilePrivilegedMutationPending] ?: false,
                )
            } else null
        } else null

        return AppPreferences(
            themeMode = theme,
            dynamicColor = values[Keys.DynamicColor] ?: false,
            hapticsEnabled = values[Keys.HapticsEnabled] ?: true,
            showAdvancedTools = values[Keys.ShowAdvancedTools] ?: false,
            telemetryRefreshMillis = (values[Keys.TelemetryRefreshMillis] ?: DEFAULT_REFRESH_MILLIS)
                .coerceIn(MIN_REFRESH_MILLIS, MAX_REFRESH_MILLIS),
            scheduledMaintenanceEnabled = values[Keys.ScheduledMaintenanceEnabled] ?: false,
            maintenanceCadence = values[Keys.MaintenanceCadence]
                ?.let { stored -> MaintenanceCadence.entries.firstOrNull { it.name == stored } }
                ?: MaintenanceCadence.Weekly,
            nightBatteryProfileEnabled = values[Keys.NightBatteryProfileEnabled] ?: false,
            nightBatteryProfileAppliedByAutomation = values[Keys.NightBatteryProfileAppliedByAutomation] ?: false,
            scheduledBackupEnabled = values[Keys.ScheduledBackupEnabled] ?: false,
            scheduledBackupCadence = values[Keys.ScheduledBackupCadence]
                ?.let { stored -> MaintenanceCadence.entries.firstOrNull { it.name == stored } }
                ?: MaintenanceCadence.Weekly,
            scheduledBackupRetentionCount = (values[Keys.ScheduledBackupRetentionCount] ?: DEFAULT_BACKUP_RETENTION)
                .coerceIn(MIN_BACKUP_RETENTION, MAX_BACKUP_RETENTION),
            scheduledBackupTreeUri = values[Keys.ScheduledBackupTreeUri]
                ?.takeIf { it.length <= MAX_URI_LENGTH },
            systemProfileBackup = profileBackup,
        )
    }

    private fun encodeCpuPolicy(value: PrivilegedCpuPolicyBackup): String? {
        if (value.policyId !in 0..63 || !GOVERNOR_REGEX.matches(value.governor)) return null
        if (value.minimumFrequencyKHz <= 0L || value.maximumFrequencyKHz < value.minimumFrequencyKHz) return null
        return "${value.policyId}|${value.governor}|${value.minimumFrequencyKHz}|${value.maximumFrequencyKHz}"
    }

    private fun decodeCpuPolicy(value: String): PrivilegedCpuPolicyBackup? {
        val parts = value.split('|')
        if (parts.size != 4) return null
        val policyId = parts[0].toIntOrNull()?.takeIf { it in 0..63 } ?: return null
        val governor = parts[1].takeIf(GOVERNOR_REGEX::matches) ?: return null
        val minimum = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val maximum = parts[3].toLongOrNull()?.takeIf { it >= minimum } ?: return null
        return PrivilegedCpuPolicyBackup(policyId, governor, minimum, maximum)
    }

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
        val DynamicColor = booleanPreferencesKey("dynamic_color")
        val HapticsEnabled = booleanPreferencesKey("haptics_enabled")
        val ShowAdvancedTools = booleanPreferencesKey("show_advanced_tools")
        val TelemetryRefreshMillis = longPreferencesKey("telemetry_refresh_millis")
        val ScheduledMaintenanceEnabled = booleanPreferencesKey("scheduled_maintenance_enabled")
        val MaintenanceCadence = stringPreferencesKey("maintenance_cadence")
        val NightBatteryProfileEnabled = booleanPreferencesKey("night_battery_profile_enabled")
        val NightBatteryProfileAppliedByAutomation = booleanPreferencesKey("night_battery_profile_applied")
        val ScheduledBackupEnabled = booleanPreferencesKey("scheduled_backup_enabled")
        val ScheduledBackupCadence = stringPreferencesKey("scheduled_backup_cadence")
        val ScheduledBackupRetentionCount = intPreferencesKey("scheduled_backup_retention")
        val ScheduledBackupTreeUri = stringPreferencesKey("scheduled_backup_tree_uri")
        val ProfileActive = booleanPreferencesKey("system_profile_active")
        val ProfileName = stringPreferencesKey("system_profile_name")
        val ProfileTimeout = longPreferencesKey("system_profile_original_timeout")
        val ProfileHaptics = booleanPreferencesKey("system_profile_original_haptics")
        val ProfileSync = booleanPreferencesKey("system_profile_original_sync")
        val ProfileMutationPending = booleanPreferencesKey("system_profile_mutation_pending")
        val ProfileCpuPolicies = stringSetPreferencesKey("system_profile_cpu_policy_baseline")
        val ProfilePrivilegedMutationPending = booleanPreferencesKey("system_profile_privileged_mutation_pending")
    }

    private companion object {
        val GOVERNOR_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
        const val DEFAULT_REFRESH_MILLIS = 2_000L
        const val MIN_REFRESH_MILLIS = 1_000L
        const val MAX_REFRESH_MILLIS = 60_000L
        const val DEFAULT_BACKUP_RETENTION = 5
        const val MIN_BACKUP_RETENTION = 1
        const val MAX_BACKUP_RETENTION = 30
        const val MAX_URI_LENGTH = 4_096
    }
}
