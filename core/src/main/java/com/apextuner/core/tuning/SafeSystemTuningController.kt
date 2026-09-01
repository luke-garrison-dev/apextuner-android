package com.apextuner.core.tuning

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.SystemProfileBackup
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

sealed interface ProfileApplyResult {
    data class Applied(
        val profile: SystemProfile,
        val changedSettings: List<String>,
        val privilegedChangesUnavailable: List<String>,
    ) : ProfileApplyResult

    data class Superseded(val profile: SystemProfile) : ProfileApplyResult
    data class PermissionRequired(val profile: SystemProfile) : ProfileApplyResult
    data class Failed(val profile: SystemProfile, val reason: String) : ProfileApplyResult
}

data class ManagedProfileStatus(
    val profile: SystemProfile,
    val matchesManagedSettings: Boolean,
    val hasRestorePoint: Boolean,
)

@Singleton
class SafeSystemTuningController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutationMutex = Mutex()
    private val mutationSequence = AtomicLong(0L)
    private val latestMutationRequest = AtomicLong(0L)

    suspend fun apply(profile: SystemProfile): ProfileApplyResult {
        val requestId = mutationSequence.incrementAndGet()
        latestMutationRequest.set(requestId)
        return withContext(ioDispatcher) {
            mutationMutex.lock()
            try {
                if (requestId != latestMutationRequest.get()) {
                    return@withContext ProfileApplyResult.Superseded(profile)
                }
                recoverInterruptedMutationLocked()
                if (profile == SystemProfile.Balanced) restoreBalancedLocked()
                else applyProfileLocked(profile)
            } finally {
                mutationMutex.unlock()
            }
        }
    }

    suspend fun restoreBalanced(): ProfileApplyResult {
        val requestId = mutationSequence.incrementAndGet()
        latestMutationRequest.set(requestId)
        return withContext(ioDispatcher) {
            mutationMutex.lock()
            try {
                if (requestId != latestMutationRequest.get()) {
                    return@withContext ProfileApplyResult.Superseded(SystemProfile.Balanced)
                }
                recoverInterruptedMutationLocked()
                restoreBalancedLocked()
            } finally {
                mutationMutex.unlock()
            }
        }
    }

    suspend fun applyExtended(
        profile: SystemProfile,
        privilegedGateway: PrivilegedSystemTuningGateway,
    ): ProfileApplyResult {
        require(profile != SystemProfile.Balanced) { "Use restoreBalanced(privilegedGateway) for Balanced." }
        val requestId = mutationSequence.incrementAndGet()
        latestMutationRequest.set(requestId)
        return withContext(ioDispatcher) {
            mutationMutex.lock()
            try {
                if (requestId != latestMutationRequest.get()) {
                    return@withContext ProfileApplyResult.Superseded(profile)
                }
                recoverInterruptedMutationLocked()
                val resolver = context.contentResolver
                val originalStored = preferencesRepository.preferences.first().systemProfileBackup
                val originalRaw = readRawState(resolver)
                val normalResult = applyProfileLocked(profile)
                if (normalResult !is ProfileApplyResult.Applied) return@withContext normalResult

                try {
                    when (val privileged = applyPrivilegedLocked(profile, privilegedGateway)) {
                        is PrivilegedTuningResult.Applied -> normalResult.copy(
                            changedSettings = normalResult.changedSettings + "Privileged CPU policies (${privileged.changedPolicies})",
                            privilegedChangesUnavailable = listOf(
                                "Android thermal protections remain platform-managed and are never overridden.",
                            ),
                        )
                        is PrivilegedTuningResult.Unavailable -> normalResult.copy(
                            privilegedChangesUnavailable = normalResult.privilegedChangesUnavailable + privileged.reason,
                        )
                        is PrivilegedTuningResult.Failed -> {
                            if (privileged.rollbackVerified) {
                                rollbackApply(resolver, originalRaw, originalStored)
                            } else {
                                runCatching { restoreRawState(resolver, originalRaw) }
                                // Keep the privileged mutation journal and CPU baseline intact.
                            }
                            ProfileApplyResult.Failed(
                                profile,
                                if (privileged.rollbackVerified) {
                                    "Privileged CPU tuning failed; prior Android and CPU settings were restored. ${privileged.reason}"
                                } else {
                                    "Privileged CPU tuning failed and CPU rollback could not be verified. Normal Android settings were restored; the privileged rollback journal was retained for explicit recovery. ${privileged.reason}"
                                },
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        runCatching { restoreRawState(resolver, originalRaw) }
                        val current = preferencesRepository.preferences.first().systemProfileBackup
                        if (current?.privilegedMutationPending != true) {
                            if (originalStored == null) preferencesRepository.clearSystemProfileBackup()
                            else preferencesRepository.saveSystemProfileBackup(originalStored)
                        }
                    }
                    throw cancelled
                }
            } finally {
                mutationMutex.unlock()
            }
        }
    }

    suspend fun restoreBalanced(
        privilegedGateway: PrivilegedSystemTuningGateway,
    ): ProfileApplyResult {
        val requestId = mutationSequence.incrementAndGet()
        latestMutationRequest.set(requestId)
        return withContext(ioDispatcher) {
            mutationMutex.lock()
            try {
                if (requestId != latestMutationRequest.get()) {
                    return@withContext ProfileApplyResult.Superseded(SystemProfile.Balanced)
                }
                recoverInterruptedMutationLocked()
                restoreBalancedLocked(privilegedGateway)
            } finally {
                mutationMutex.unlock()
            }
        }
    }

    /**
     * Repairs the only cross-app setting changed by ApexTuner 1.0.10, then removes the
     * legacy marker. This is intentionally idempotent and can run on every app start.
     */
    suspend fun reconcileLegacyState() = withContext(ioDispatcher) {
        mutationMutex.lock()
        try {
            recoverInterruptedMutationLocked()
            val backup = preferencesRepository.preferences.first().systemProfileBackup ?: return@withContext
            val originalSync = backup.legacyOriginalMasterSyncEnabled ?: return@withContext
            ContentResolver.setMasterSyncAutomatically(originalSync)
            preferencesRepository.saveSystemProfileBackup(
                backup.copy(legacyOriginalMasterSyncEnabled = null),
            )
        } finally {
            mutationMutex.unlock()
        }
    }

    suspend fun activeProfile(): SystemProfile = withContext(ioDispatcher) {
        mutationMutex.lock()
        try {
            recoverInterruptedMutationLocked()
            val backup = preferencesRepository.preferences.first().systemProfileBackup
            if (backup?.mutationPending == true) SystemProfile.Balanced
            else backup?.activeProfile ?: SystemProfile.Balanced
        } finally {
            mutationMutex.unlock()
        }
    }

    /**
     * Reconciles ApexTuner's persisted profile marker with the live Android settings it
     * actually manages. A stored profile remains available as the rollback owner even when
     * the user or OEM changes one of those settings outside ApexTuner.
     */
    suspend fun profileStatus(): ManagedProfileStatus = withContext(ioDispatcher) {
        mutationMutex.lock()
        try {
            recoverInterruptedMutationLocked()
            val backup = preferencesRepository.preferences.first().systemProfileBackup
                ?: return@withContext ManagedProfileStatus(
                    profile = SystemProfile.Balanced,
                    matchesManagedSettings = true,
                    hasRestorePoint = false,
                )
            if (backup.mutationPending) {
                return@withContext ManagedProfileStatus(
                    profile = backup.activeProfile,
                    matchesManagedSettings = false,
                    hasRestorePoint = true,
                )
            }

            val current = readRawState(context.contentResolver)
            val matches = SystemProfilePlanner.matches(
                profile = backup.activeProfile,
                baseline = backup,
                currentScreenOffTimeoutMillis = current.screenOffTimeoutMillis,
                currentHapticFeedbackEnabled = current.hapticFeedbackEnabled,
                manageHaptics = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
            )
            ManagedProfileStatus(
                profile = backup.activeProfile,
                matchesManagedSettings = matches,
                hasRestorePoint = true,
            )
        } finally {
            mutationMutex.unlock()
        }
    }

    private suspend fun applyProfileLocked(profile: SystemProfile): ProfileApplyResult {
        if (!Settings.System.canWrite(context)) return ProfileApplyResult.PermissionRequired(profile)

        val resolver = context.contentResolver
        val stored = preferencesRepository.preferences.first().systemProfileBackup
        if (stored?.privilegedMutationPending == true) {
            return ProfileApplyResult.Failed(
                profile,
                "A privileged CPU rollback is pending. Restore the privileged baseline from Advanced Access before applying another profile.",
            )
        }
        val currentState = readRawState(resolver)
        val baseline = stored ?: SystemProfileBackup(
            originalScreenOffTimeoutMillis = currentState.screenOffTimeoutMillis,
            originalHapticFeedbackEnabled = currentState.hapticFeedbackEnabled,
            activeProfile = SystemProfile.Balanced,
        )
        val targets = SystemProfilePlanner.targets(profile, baseline)
        val manageHaptics = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

        try {
            // Persist a transaction journal before touching Android settings. If the process
            // dies mid-apply, the next controller entry restores the saved baseline before
            // reporting any profile as active.
            preferencesRepository.saveSystemProfileBackup(
                baseline.copy(activeProfile = profile, mutationPending = true),
            )

            val changed = mutableListOf<String>()
            if (putLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT, targets.screenOffTimeoutMillis)) {
                if (targets.screenOffTimeoutMillis != currentState.screenOffTimeoutMillis) changed += "Screen timeout"
            } else {
                throw IllegalStateException("Android rejected the screen-timeout update.")
            }

            if (manageHaptics) {
                if (putInt(resolver, LEGACY_HAPTIC_FEEDBACK_SETTING, if (targets.hapticFeedbackEnabled) 1 else 0)) {
                    if (targets.hapticFeedbackEnabled != currentState.hapticFeedbackEnabled) changed += "System haptic feedback"
                } else {
                    throw IllegalStateException("Android rejected the haptic-feedback update.")
                }
            }


            preferencesRepository.saveSystemProfileBackup(baseline.copy(activeProfile = profile, mutationPending = false))

            return ProfileApplyResult.Applied(
                profile = profile,
                changedSettings = changed,
                privilegedChangesUnavailable = privilegedPlan(profile),
            )
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { rollbackApply(resolver, currentState, stored) }
            throw cancel
        } catch (error: Exception) {
            rollbackApply(resolver, currentState, stored)
            return ProfileApplyResult.Failed(profile, error.message ?: "System settings could not be applied safely.")
        }
    }

    private suspend fun restoreBalancedLocked(
        privilegedGateway: PrivilegedSystemTuningGateway? = null,
    ): ProfileApplyResult {
        val backup = preferencesRepository.preferences.first().systemProfileBackup
            ?: return ProfileApplyResult.Applied(
                profile = SystemProfile.Balanced,
                changedSettings = emptyList(),
                privilegedChangesUnavailable = emptyList(),
            )
        if (!Settings.System.canWrite(context)) {
            return ProfileApplyResult.PermissionRequired(SystemProfile.Balanced)
        }
        if (backup.privilegedCpuPolicies.isNotEmpty() && privilegedGateway == null) {
            return ProfileApplyResult.Failed(
                SystemProfile.Balanced,
                "A privileged CPU restore point is active. Restore Balanced from Advanced Access so those values are restored first.",
            )
        }

        return try {
            val changed = mutableListOf<String>()
            if (backup.privilegedCpuPolicies.isNotEmpty()) {
                val gateway = privilegedGateway
                    ?: return ProfileApplyResult.Failed(SystemProfile.Balanced, "Privileged restore backend is unavailable.")
                val current = gateway.snapshot()
                    ?: return ProfileApplyResult.Failed(SystemProfile.Balanced, "Current CPU policies could not be verified; nothing was restored.")
                val privilegedBaseline = rebuildPrivilegedBaseline(backup, current)
                    ?: return ProfileApplyResult.Failed(SystemProfile.Balanced, "Saved CPU policy baseline no longer matches this device; nothing was restored.")
                preferencesRepository.saveSystemProfileBackup(backup.copy(privilegedMutationPending = true))
                when (val result = gateway.restore(privilegedBaseline)) {
                    is PrivilegedTuningResult.Applied -> {
                        changed += "Privileged CPU policies (${result.changedPolicies})"
                        preferencesRepository.saveSystemProfileBackup(backup.copy(privilegedMutationPending = false))
                    }
                    is PrivilegedTuningResult.Unavailable ->
                        return ProfileApplyResult.Failed(SystemProfile.Balanced, result.reason)
                    is PrivilegedTuningResult.Failed ->
                        return ProfileApplyResult.Failed(SystemProfile.Balanced, result.reason)
                }
            }
            changed += restoreRaw(context.contentResolver, backup)
            preferencesRepository.clearSystemProfileBackup()
            ProfileApplyResult.Applied(
                profile = SystemProfile.Balanced,
                changedSettings = changed,
                privilegedChangesUnavailable = emptyList(),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            ProfileApplyResult.Failed(
                SystemProfile.Balanced,
                error.message ?: "The original system settings could not be fully restored.",
            )
        }
    }

    private suspend fun applyPrivilegedLocked(
        profile: SystemProfile,
        gateway: PrivilegedSystemTuningGateway,
    ): PrivilegedTuningResult {
        val current = gateway.snapshot()
            ?: return PrivilegedTuningResult.Unavailable(
                "CPU governor/frequency controls are unavailable through the selected privileged backend.",
            )
        if (!current.isUsable()) {
            return PrivilegedTuningResult.Unavailable("CPU policy metadata could not be verified safely.")
        }
        val stored = preferencesRepository.preferences.first().systemProfileBackup
            ?: return PrivilegedTuningResult.Failed("System profile rollback baseline is unavailable.", rollbackVerified = true)
        val baseline = if (stored.privilegedCpuPolicies.isEmpty()) {
            current
        } else {
            rebuildPrivilegedBaseline(stored, current)
                ?: return PrivilegedTuningResult.Failed("Saved CPU policy baseline no longer matches this device.", rollbackVerified = true)
        }
        val targets = SystemProfilePlanner.privilegedTargets(profile, current)
            ?: return PrivilegedTuningResult.Unavailable("A safe CPU target could not be planned for this device.")
        val withBaseline = stored.copy(
            privilegedCpuPolicies = baseline.cpuPolicies.map { it.toBackup() },
            privilegedMutationPending = true,
        )
        preferencesRepository.saveSystemProfileBackup(withBaseline)
        return try {
            when (val result = gateway.apply(targets, baseline)) {
                is PrivilegedTuningResult.Applied -> {
                    preferencesRepository.saveSystemProfileBackup(withBaseline.copy(privilegedMutationPending = false))
                    result
                }
                is PrivilegedTuningResult.Unavailable -> {
                    preferencesRepository.saveSystemProfileBackup(stored)
                    result
                }
                is PrivilegedTuningResult.Failed -> {
                    val rollback = gateway.restore(baseline)
                    if (rollback is PrivilegedTuningResult.Applied) {
                        preferencesRepository.saveSystemProfileBackup(stored)
                        result.copy(rollbackVerified = true)
                    } else {
                        // Keep the journal and baseline intact so recovery can be retried explicitly.
                        result.copy(rollbackVerified = false)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val rollback = runCatching { gateway.restore(baseline) }.getOrNull()
                if (rollback is PrivilegedTuningResult.Applied) {
                    preferencesRepository.saveSystemProfileBackup(stored)
                }
            }
            throw cancelled
        }
    }

    private fun rebuildPrivilegedBaseline(
        backup: SystemProfileBackup,
        current: PrivilegedTuningSnapshot,
    ): PrivilegedTuningSnapshot? {
        if (!current.isUsable() || backup.privilegedCpuPolicies.isEmpty()) return null
        val currentById = current.cpuPolicies.associateBy { it.policyId }
        val policies = backup.privilegedCpuPolicies.map { saved ->
            val live = currentById[saved.policyId] ?: return null
            val rebuilt = live.copy(
                governor = saved.governor,
                minimumFrequencyKHz = saved.minimumFrequencyKHz,
                maximumFrequencyKHz = saved.maximumFrequencyKHz,
            )
            if (!rebuilt.isValid()) return null
            rebuilt
        }
        return PrivilegedTuningSnapshot(policies).takeIf { it.isUsable() }
    }

    private suspend fun recoverInterruptedMutationLocked() {
        val backup = preferencesRepository.preferences.first().systemProfileBackup ?: return
        if (backup.privilegedMutationPending) return
        if (!backup.mutationPending) return
        if (!Settings.System.canWrite(context)) return

        try {
            restoreRaw(context.contentResolver, backup)
            preferencesRepository.clearSystemProfileBackup()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Keep the journal intact. A later call can retry recovery; never mark a
            // partially reconciled profile as committed.
        }
    }

    private suspend fun rollbackApply(
        resolver: ContentResolver,
        state: RawSystemState,
        stored: SystemProfileBackup?,
    ) {
        runCatching { restoreRawState(resolver, state) }
        if (stored == null) runCatching { preferencesRepository.clearSystemProfileBackup() }
        else runCatching { preferencesRepository.saveSystemProfileBackup(stored) }
    }

    private fun readRawState(resolver: ContentResolver): RawSystemState {
        // Preserve the exact Android value so Restore Balanced can put back the user's/OEM's
        // real baseline, including supported long/"Never" timeout representations. Target
        // profiles sanitize only the value they intend to apply; the rollback snapshot is exact.
        val timeout = Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT_MILLIS)
        val haptics = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Settings.System.getInt(resolver, LEGACY_HAPTIC_FEEDBACK_SETTING, 1) != 0
        } else {
            // Android 13+ applies the user's touch-haptics preference automatically.
            // ApexTuner intentionally does not read or mutate the deprecated global setting.
            true
        }
        return RawSystemState(timeout, haptics)
    }

    private fun restoreRawState(resolver: ContentResolver, state: RawSystemState) {
        check(putLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT, state.screenOffTimeoutMillis))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            check(putInt(resolver, LEGACY_HAPTIC_FEEDBACK_SETTING, if (state.hapticFeedbackEnabled) 1 else 0))
        }
    }

    private fun restoreRaw(resolver: ContentResolver, backup: SystemProfileBackup): List<String> {
        val before = readRawState(resolver)
        check(putLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT, backup.originalScreenOffTimeoutMillis))
        val changed = mutableListOf<String>()
        if (before.screenOffTimeoutMillis != backup.originalScreenOffTimeoutMillis) changed += "Screen timeout"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            check(putInt(resolver, LEGACY_HAPTIC_FEEDBACK_SETTING, if (backup.originalHapticFeedbackEnabled) 1 else 0))
            if (before.hapticFeedbackEnabled != backup.originalHapticFeedbackEnabled) changed += "System haptic feedback"
        }
        backup.legacyOriginalMasterSyncEnabled?.let { originalSync ->
            // Compatibility recovery only: ApexTuner 1.0.10 could pause Android's global
            // master sync in Battery mode. New profiles never mutate this cross-app setting.
            ContentResolver.setMasterSyncAutomatically(originalSync)
            changed += "Legacy master auto-sync recovery"
        }
        return changed
    }

    private fun putLong(resolver: ContentResolver, name: String, value: Long): Boolean =
        Settings.System.putLong(resolver, name, value)

    private fun putInt(resolver: ContentResolver, name: String, value: Int): Boolean =
        Settings.System.putInt(resolver, name, value)

    private fun privilegedPlan(profile: SystemProfile): List<String> = when (profile) {
        SystemProfile.Balanced -> emptyList()
        SystemProfile.Battery -> listOf(
            "Doze/device-idle forcing requires explicit ADB/root access",
            "Adaptive Battery and radio scanning policies are system-controlled",
        )
        SystemProfile.Performance -> listOf(
            "CPU governor/frequency extension is available only through explicit Pro privileged access",
            "I/O scheduler and VM/LMK tuning remain unavailable",
        )
        SystemProfile.Gaming -> listOf(
            "CPU governor/frequency extension is available only through explicit Pro privileged access",
            "Android thermal protections remain platform-managed and are never disabled",
        )
    }

    private data class RawSystemState(
        val screenOffTimeoutMillis: Long,
        val hapticFeedbackEnabled: Boolean,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        // The legacy global haptic-feedback setting is deprecated from API 33.
        // Retain its documented key only for legacy devices where profile rollback must
        // remain symmetric with versions that supported this system setting.
        const val LEGACY_HAPTIC_FEEDBACK_SETTING = "haptic_feedback_enabled"
    }
}
