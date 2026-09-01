package com.apextuner.feature.tools.advanced

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.tuning.PrivilegedSystemTuningGateway
import com.apextuner.core.tuning.PrivilegedTuningResult
import com.apextuner.core.tuning.PrivilegedTuningSnapshot
import com.apextuner.core.tuning.PrivilegedTuningTargets
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

private val Context.advancedToolsDataStore by preferencesDataStore(name = "apextuner_advanced_tools")

@Singleton
class AdvancedToolsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityManager: CapabilityManager,
    private val shizukuGateway: ShizukuGateway,
    private val rootGateway: RootGateway,
    private val entitlementRepository: EntitlementRepository,
    private val tuningController: SafeSystemTuningController,
) {
    @Volatile private var lastRootAuthorization = RootAuthorizationState.NotChecked

    fun status(): PrivilegedBackendStatus {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = binderAlive && runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        val permissionGranted = binderAlive && !preV11 && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return PrivilegedBackendStatus(
            shizukuBinderAlive = binderAlive,
            shizukuApiVersion = if (binderAlive) runCatching { Shizuku.getVersion() }.getOrNull()?.takeIf { it > 0 } else null,
            shizukuPermissionGranted = permissionGranted,
            shizukuPermissionRationaleRequired = binderAlive && !permissionGranted && !preV11 && runCatching {
                Shizuku.shouldShowRequestPermissionRationale()
            }.getOrDefault(false),
            shizukuUid = if (permissionGranted) runCatching { Shizuku.getUid() }.getOrNull()?.takeIf { it >= 0 } else null,
            shizukuIsRoot = if (permissionGranted) runCatching { Shizuku.getUid() == 0 }.getOrDefault(false) else false,
            rootPotentiallyAvailable = capabilityManager.status(Capability.RootAccess).state == CapabilityState.Available,
            lastRootAuthorization = lastRootAuthorization,
        )
    }

    fun requestShizukuPermission() {
        check(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { "Start Shizuku or Sui first." }
        check(!runCatching { Shizuku.isPreV11() }.getOrDefault(true)) { "Shizuku API versions below 11 are unsupported." }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        check(!Shizuku.shouldShowRequestPermissionRationale()) { "Shizuku permission is blocked. Open Shizuku and grant ApexTuner access there." }
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    suspend fun testRootAuthorization(): RootAuthorizationState {
        val result = rootGateway.checkAuthorization()
        lastRootAuthorization = result
        return result
    }

    suspend fun executeReadOnly(backend: PrivilegedBackend, command: PrivilegedReadCommand): PrivilegedCommandResult =
        when (backend) {
            PrivilegedBackend.Shizuku -> shizukuGateway.executeReadOnly(command)
            PrivilegedBackend.Root -> rootGateway.executeReadOnly(command)
        }

    suspend fun savedAnimationBaseline(): AnimationScales? {
        val preferences = context.advancedToolsDataStore.data.first()
        if (!preferences.contains(BASE_WINDOW) || !preferences.contains(BASE_TRANSITION) || !preferences.contains(BASE_ANIMATOR)) return null
        return AnimationScales(
            preferences[BASE_WINDOW] ?: return null,
            preferences[BASE_TRANSITION] ?: return null,
            preferences[BASE_ANIMATOR] ?: return null,
        ).takeIf(AnimationScales::isSafe)
    }

    suspend fun applyAnimationPreset(backend: PrivilegedBackend, values: AnimationScales): PrivilegedCommandResult {
        require(values.isSafe()) { "Unsafe animation scale." }
        if (savedAnimationBaseline() == null) {
            val current = executeReadOnly(backend, PrivilegedReadCommand.AnimationScales)
            if (!current.success) return current
            persistBaseline(parseAnimationScales(current.output)
                ?: return PrivilegedCommandResult(backend, false, "Could not verify current animation scales; nothing changed."))
        }
        return when (backend) {
            PrivilegedBackend.Shizuku -> shizukuGateway.setAnimationScales(values)
            PrivilegedBackend.Root -> rootGateway.setAnimationScales(values)
        }
    }

    suspend fun restoreAnimationBaseline(backend: PrivilegedBackend): PrivilegedCommandResult {
        val baseline = savedAnimationBaseline()
            ?: return PrivilegedCommandResult(backend, false, "No saved animation baseline is available.")
        val result = when (backend) {
            PrivilegedBackend.Shizuku -> shizukuGateway.setAnimationScales(baseline)
            PrivilegedBackend.Root -> rootGateway.setAnimationScales(baseline)
        }
        if (result.success) clearBaseline()
        return result
    }

    suspend fun packageTargets(): List<PrivilegedPackageTarget> {
        val launcher = context.getSystemService(LauncherApps::class.java)
        val pm = context.packageManager
        return runCatching {
            launcher.getActivityList(null, Process.myUserHandle()).asSequence()
                .mapNotNull { activity ->
                    val info = activity.applicationInfo
                    val pkg = info.packageName
                    if (isProtectedPackageTarget(pkg, context.packageName, info.flags, info.uid)) return@mapNotNull null
                    val state = runCatching { pm.getApplicationEnabledSetting(pkg) }
                        .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    if (!isRestorableEnabledState(state)) return@mapNotNull null
                    PrivilegedPackageTarget(pkg, activity.label.toString().take(120), state)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .take(MAX_PACKAGE_TARGETS)
                .toList()
        }.getOrDefault(emptyList())
    }

    suspend fun freezePackage(backend: PrivilegedBackend, packageName: String): PrivilegedCommandResult {
        requirePremium()
        val target = verifyPackageTarget(packageName)
            ?: return PrivilegedCommandResult(backend, false, "Package is unavailable or protected; nothing changed.")
        val baselines = frozenBaselines()
        if (packageName !in baselines) persistFrozenBaseline(packageName, target.enabledSetting)
        val result = setPackageState(backend, packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        if (!result.success && packageName !in baselines) removeFrozenBaseline(packageName)
        return result
    }

    suspend fun unfreezePackage(backend: PrivilegedBackend, packageName: String): PrivilegedCommandResult {
        requirePremium()
        verifyPackageTarget(packageName)
            ?: return PrivilegedCommandResult(backend, false, "Package is unavailable or protected; nothing changed.")
        val original = frozenBaselines()[packageName]
            ?: return PrivilegedCommandResult(backend, false, "No ApexTuner freeze rollback state exists for this app.")
        val result = setPackageState(backend, packageName, original)
        if (result.success) removeFrozenBaseline(packageName)
        return result
    }

    suspend fun forceStopPackage(backend: PrivilegedBackend, packageName: String): PrivilegedCommandResult {
        requirePremium()
        verifyPackageTarget(packageName)
            ?: return PrivilegedCommandResult(backend, false, "Package is unavailable or protected; nothing changed.")
        return when (backend) {
            PrivilegedBackend.Shizuku -> shizukuGateway.forceStopPackage(packageName)
            PrivilegedBackend.Root -> rootGateway.forceStopPackage(packageName)
        }
    }

    fun cacheMaintenanceCapability(): CacheMaintenanceCapability =
        CacheMaintenanceCapability.RollbackSafeUnavailable

    suspend fun inspectCpuTuning(backend: PrivilegedBackend): CpuTuningStatus {
        requirePremium()
        val snapshot = gateway(backend).snapshot()
        return CpuTuningStatus(
            availablePolicies = snapshot?.cpuPolicies?.size ?: 0,
            baselinePolicies = 0,
            thermalPolicy = "Platform managed",
        )
    }

    suspend fun applyExtendedProfile(backend: PrivilegedBackend, profile: SystemProfile): ProfileApplyResult {
        requirePremium()
        require(profile != SystemProfile.Balanced)
        return tuningController.applyExtended(profile, gateway(backend))
    }

    suspend fun restoreExtendedProfile(backend: PrivilegedBackend): ProfileApplyResult {
        // Restoration is intentionally not entitlement-gated.
        return tuningController.restoreBalanced(gateway(backend))
    }

    private fun gateway(backend: PrivilegedBackend): PrivilegedSystemTuningGateway =
        object : PrivilegedSystemTuningGateway {
            override suspend fun snapshot(): PrivilegedTuningSnapshot? = when (backend) {
                PrivilegedBackend.Shizuku -> shizukuGateway.snapshotCpuPolicies()
                PrivilegedBackend.Root -> rootGateway.snapshotCpuPolicies()
            }
            override suspend fun apply(
                targets: PrivilegedTuningTargets,
                baseline: PrivilegedTuningSnapshot,
            ): PrivilegedTuningResult = when (backend) {
                PrivilegedBackend.Shizuku -> shizukuGateway.applyCpuTargets(targets, baseline)
                PrivilegedBackend.Root -> rootGateway.applyCpuTargets(targets, baseline)
            }
            override suspend fun restore(baseline: PrivilegedTuningSnapshot): PrivilegedTuningResult = when (backend) {
                PrivilegedBackend.Shizuku -> shizukuGateway.restoreCpuSnapshot(baseline)
                PrivilegedBackend.Root -> rootGateway.restoreCpuSnapshot(baseline)
            }
        }

    private suspend fun requirePremium() {
        entitlementRepository.refresh("advanced_premium_action")
        check(entitlementRepository.entitlement.value.isPremium) { "This action requires the ApexTuner lifetime Premium purchase." }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageTarget(packageName: String): PrivilegedPackageTarget? {
        if (!PACKAGE_NAME.matches(packageName)) return null
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        if (isProtectedPackageTarget(packageName, context.packageName, info.flags, info.uid)) return null
        val state = runCatching { pm.getApplicationEnabledSetting(packageName) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        if (!isRestorableEnabledState(state)) return null
        return PrivilegedPackageTarget(packageName, pm.getApplicationLabel(info).toString().take(120), state)
    }

    private suspend fun setPackageState(
        backend: PrivilegedBackend,
        packageName: String,
        state: Int,
    ): PrivilegedCommandResult = when (backend) {
        PrivilegedBackend.Shizuku -> shizukuGateway.setApplicationEnabledState(packageName, state)
        PrivilegedBackend.Root -> rootGateway.setApplicationEnabledState(packageName, state)
    }

    private suspend fun frozenBaselines(): Map<String, Int> =
        context.advancedToolsDataStore.data.first()[FROZEN_BASELINES].orEmpty()
            .mapNotNull(::decodeFrozenBaseline)
            .toMap()

    private suspend fun persistFrozenBaseline(packageName: String, state: Int) {
        require(isRestorableEnabledState(state))
        context.advancedToolsDataStore.edit { prefs ->
            val current = prefs[FROZEN_BASELINES].orEmpty()
                .mapNotNull(::decodeFrozenBaseline).toMap().toMutableMap()
            current[packageName] = state
            require(current.size <= MAX_FROZEN_BASELINES)
            prefs[FROZEN_BASELINES] = current.entries.map { "${it.key}|${it.value}" }.toSet()
        }
    }

    private suspend fun removeFrozenBaseline(packageName: String) {
        context.advancedToolsDataStore.edit { prefs ->
            val updated = prefs[FROZEN_BASELINES].orEmpty().filterNot { decodeFrozenBaseline(it)?.first == packageName }.toSet()
            if (updated.isEmpty()) prefs.remove(FROZEN_BASELINES) else prefs[FROZEN_BASELINES] = updated
        }
    }

    private fun decodeFrozenBaseline(value: String): Pair<String, Int>? {
        val parts = value.split('|', limit = 2)
        if (parts.size != 2 || !PACKAGE_NAME.matches(parts[0])) return null
        val state = parts[1].toIntOrNull() ?: return null
        return if (isRestorableEnabledState(state)) parts[0] to state else null
    }

    private suspend fun persistBaseline(values: AnimationScales) {
        context.advancedToolsDataStore.edit { preferences ->
            preferences[BASE_WINDOW] = values.window
            preferences[BASE_TRANSITION] = values.transition
            preferences[BASE_ANIMATOR] = values.animator
        }
    }

    private suspend fun clearBaseline() {
        context.advancedToolsDataStore.edit { preferences ->
            preferences.remove(BASE_WINDOW)
            preferences.remove(BASE_TRANSITION)
            preferences.remove(BASE_ANIMATOR)
        }
    }

    companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 6126
        private val BASE_WINDOW = floatPreferencesKey("animation_baseline_window")
        private val BASE_TRANSITION = floatPreferencesKey("animation_baseline_transition")
        private val BASE_ANIMATOR = floatPreferencesKey("animation_baseline_animator")
        private val FROZEN_BASELINES = stringSetPreferencesKey("frozen_package_baselines")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        private const val MAX_PACKAGE_TARGETS = 500
        private const val MAX_FROZEN_BASELINES = 100
    }
}
