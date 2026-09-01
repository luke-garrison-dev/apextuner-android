package com.apextuner.feature.tools.advanced

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.tuning.CpuPolicyTarget
import com.apextuner.core.tuning.PrivilegedTuningResult
import com.apextuner.core.tuning.PrivilegedTuningSnapshot
import com.apextuner.core.tuning.PrivilegedTuningTargets
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

@Singleton
class ShizukuGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun executeReadOnly(command: PrivilegedReadCommand): PrivilegedCommandResult = withService { service ->
        val output = service.executeReadOnly(command.id)
        PrivilegedCommandResult(PrivilegedBackend.Shizuku, !output.startsWith("ERROR:"), boundedOutput(output))
    }

    suspend fun setAnimationScales(values: AnimationScales): PrivilegedCommandResult {
        require(values.isSafe()) { "Animation scales are outside the allowed range." }
        return withService { service ->
            val output = service.setAnimationScales(values.window, values.transition, values.animator)
            PrivilegedCommandResult(PrivilegedBackend.Shizuku, !output.startsWith("ERROR:"), boundedOutput(output))
        }
    }

    suspend fun setApplicationEnabledState(packageName: String, state: Int): PrivilegedCommandResult =
        withService { service ->
            val output = service.setApplicationEnabledState(packageName, state)
            PrivilegedCommandResult(PrivilegedBackend.Shizuku, !output.startsWith("ERROR:"), boundedOutput(output))
        }

    suspend fun forceStopPackage(packageName: String): PrivilegedCommandResult = withService { service ->
        val output = service.forceStopPackage(packageName)
        PrivilegedCommandResult(PrivilegedBackend.Shizuku, !output.startsWith("ERROR:"), boundedOutput(output))
    }

    suspend fun snapshotCpuPolicies(): PrivilegedTuningSnapshot? = withService { service ->
        val policies = buildList {
            for (policyId in CPU_POLICY_RANGE) {
                val output = service.readCpuPolicy(policyId)
                val parsed = parseCpuPolicySnapshot(policyId, output)
                if (parsed != null) add(parsed)
            }
        }
        PrivilegedTuningSnapshot(policies).takeIf { it.isUsable() }
    }

    suspend fun applyCpuTargets(
        targets: PrivilegedTuningTargets,
        baseline: PrivilegedTuningSnapshot,
    ): PrivilegedTuningResult = withService { service ->
        applyCpuTargets(service, targets.cpuPolicies, baseline)
    }

    suspend fun restoreCpuSnapshot(baseline: PrivilegedTuningSnapshot): PrivilegedTuningResult = withService { service ->
        val targets = baseline.cpuPolicies.map {
            CpuPolicyTarget(it.policyId, it.governor, it.minimumFrequencyKHz, it.maximumFrequencyKHz)
        }
        applyCpuTargets(service, targets, baseline)
    }

    private fun applyCpuTargets(
        service: IPrivilegedUserService,
        targets: List<CpuPolicyTarget>,
        baseline: PrivilegedTuningSnapshot,
    ): PrivilegedTuningResult {
        if (!baseline.isUsable() || targets.isEmpty() || targets.size != baseline.cpuPolicies.size) {
            return PrivilegedTuningResult.Unavailable("CPU policy set could not be verified.")
        }
        val baselineById = baseline.cpuPolicies.associateBy { it.policyId }
        if (targets.any { target -> baselineById[target.policyId]?.let(target::isSafeAgainst) != true }) {
            return PrivilegedTuningResult.Unavailable("CPU target is outside a verified policy range.")
        }

        var changed = 0
        for (target in targets.sortedBy { it.policyId }) {
            val output = service.writeCpuPolicy(
                target.policyId,
                target.governor,
                target.minimumFrequencyKHz,
                target.maximumFrequencyKHz,
            )
            if (output.startsWith("ERROR:")) {
                val rollbackVerified = rollbackPolicies(service, baseline)
                return PrivilegedTuningResult.Failed(
                    reason = boundedOutput(output),
                    rollbackVerified = rollbackVerified,
                )
            }
            changed += 1
        }
        return PrivilegedTuningResult.Applied(
            changedPolicies = changed,
            message = "CPU policies updated; Android thermal management remains enabled.",
        )
    }

    private fun rollbackPolicies(
        service: IPrivilegedUserService,
        baseline: PrivilegedTuningSnapshot,
    ): Boolean {
        for (policy in baseline.cpuPolicies.sortedBy { it.policyId }) {
            val output = service.writeCpuPolicy(
                policy.policyId,
                policy.governor,
                policy.minimumFrequencyKHz,
                policy.maximumFrequencyKHz,
            )
            if (output.startsWith("ERROR:")) return false
        }
        return true
    }

    private suspend fun <T> withService(block: suspend (IPrivilegedUserService) -> T): T {
        ensureAuthorized()
        val args = userServiceArgs()
        var connection: ServiceConnection? = null
        try {
            val binder = withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<IBinder> { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (service == null) {
                                if (continuation.isActive) continuation.resumeWithException(
                                    IllegalStateException("Shizuku returned no user-service binder."),
                                )
                            } else if (continuation.isActive) {
                                continuation.resume(service)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException("Shizuku user service disconnected."),
                            )
                        }
                    }
                    connection = conn
                    continuation.invokeOnCancellation {
                        runCatching { Shizuku.unbindUserService(args, conn, true) }
                    }
                    Shizuku.bindUserService(args, conn)
                }
            }
            val service = IPrivilegedUserService.Stub.asInterface(binder)
                ?: throw IllegalStateException("Shizuku user service interface is unavailable.")
            return withContext(ioDispatcher) { block(service) }
        } finally {
            connection?.let { conn -> runCatching { Shizuku.unbindUserService(args, conn, true) } }
        }
    }

    private fun ensureAuthorized() {
        check(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { "Shizuku/Sui is not running." }
        check(!runCatching { Shizuku.isPreV11() }.getOrDefault(true)) { "This Shizuku server version is unsupported." }
        check(runCatching { Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            "Shizuku permission has not been granted to ApexTuner."
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context, ApexPrivilegedUserService::class.java),
    )
        .processNameSuffix("apex_privileged")
        .tag(USER_SERVICE_TAG)
        .version(USER_SERVICE_VERSION)
        .daemon(false)

    private companion object {
        val CPU_POLICY_RANGE = 0..31
        const val USER_SERVICE_TAG = "apextuner-privileged-v1"
        const val USER_SERVICE_VERSION = 2
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L
    }
}
