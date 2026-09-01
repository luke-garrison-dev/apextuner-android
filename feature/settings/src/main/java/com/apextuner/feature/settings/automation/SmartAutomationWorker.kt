package com.apextuner.feature.settings.automation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.repository.DeviceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class SmartAutomationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val entitlementRepository: EntitlementRepository,
    private val deviceRepository: DeviceRepository,
    private val repository: SmartAutomationRepository,
    private val executor: SmartAutomationExecutor,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            // Premium is a one-time entitlement. The repository is initialized from its bounded,
            // encrypted offline mirror when WorkManager starts this process; foreground/resume
            // flows remain responsible for authoritative Google Play refreshes. Avoid reconnecting
            // Billing every 15 minutes from background work.
            val premium = entitlementRepository.entitlement.value.isPremium
            val hasOwnedProfile = executor.hasOwnedProfile()
            // Premium loss must not strand a reversible profile that ApexTuner owns. A stale
            // worker therefore restores owned state before exiting, but otherwise does no
            // telemetry when Premium is inactive.
            if (!premium && !hasOwnedProfile) return Result.success()

            val rules = if (premium) repository.enabledRules() else emptyList()
            if (rules.isEmpty() && !hasOwnedProfile) return Result.success()
            val ownershipRules = if (hasOwnedProfile) repository.allRules() else rules
            val snapshot = deviceRepository.snapshot()
            if (hasOwnedProfile) {
                executor.restoreIfOwnedAndConditionCleared(
                    ownershipRules,
                    snapshot,
                    forceRestore = !premium,
                )
            }
            if (!premium || rules.isEmpty()) return Result.success()

            var mutationExecuted = false
            var diagnosticSnapshotCaptured = false
            val now = System.currentTimeMillis()
            for (rule in rules) {
                if (!SmartAutomationEvaluator.matches(rule, snapshot)) continue
                val last = rule.lastTriggeredAtEpochMillis
                if (last != null && now >= last && now - last < rule.cooldownMillis) continue
                if (rule.dryRun) {
                    repository.record(rule, "DRY_RUN", "Condition matched: ${SmartAutomationEvaluator.description(rule)}")
                    continue
                }
                if (rule.actionType == SmartActionType.ApplyBatteryProfile.name && mutationExecuted) {
                    repository.record(rule, "SKIPPED", "A different mutating rule already ran in this evaluation.", markTriggered = false)
                    continue
                }
                if (SmartAutomationPolicy.isDiagnosticCaptureRule(rule) && diagnosticSnapshotCaptured) {
                    repository.record(
                        rule,
                        "APPLIED",
                        "Condition matched; reused the diagnostic snapshot already captured during this evaluation.",
                    )
                    continue
                }
                val result = executor.execute(rule, snapshot)
                // Notification-policy failures are still throttled by the configured cooldown to
                // avoid a failed reminder producing a new history row every 15 minutes. Granting
                // notification permission explicitly clears those cooldowns from Settings.
                val throttleAttempt = SmartAutomationPolicy.isNotificationRule(rule)
                repository.record(
                    rule,
                    if (result.success) "APPLIED" else "SKIPPED",
                    result.detail,
                    markTriggered = result.success || throttleAttempt,
                )
                if (result.success && rule.actionType == SmartActionType.ApplyBatteryProfile.name) mutationExecuted = true
                if (result.success && SmartAutomationPolicy.isDiagnosticCaptureRule(rule)) diagnosticSnapshotCaptured = true
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
