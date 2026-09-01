package com.apextuner.feature.tools.advanced

import android.content.Context
import android.content.pm.PackageManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.tuning.CpuPolicyTarget
import com.apextuner.core.tuning.PrivilegedTuningResult
import com.apextuner.core.tuning.PrivilegedTuningSnapshot
import com.apextuner.core.tuning.PrivilegedTuningTargets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun checkAuthorization(): RootAuthorizationState = runInterruptible(ioDispatcher) {
        val result = runRootFixed(listOf("/system/bin/id"), AUTH_TIMEOUT_MILLIS)
        when {
            result.timedOut -> RootAuthorizationState.TimedOut
            result.exitCode == 0 && Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(result.output) -> RootAuthorizationState.Granted
            else -> RootAuthorizationState.DeniedOrUnavailable
        }
    }

    suspend fun executeReadOnly(command: PrivilegedReadCommand): PrivilegedCommandResult = runInterruptible(ioDispatcher) {
        val args = when (command) {
            PrivilegedReadCommand.Identity -> listOf("/system/bin/id")
            PrivilegedReadCommand.DeviceIdleState -> listOf("/system/bin/dumpsys", "deviceidle")
            PrivilegedReadCommand.AnimationScales -> emptyList()
            PrivilegedReadCommand.KernelVersion -> listOf("/system/bin/cat", "/proc/version")
        }
        val output = if (command == PrivilegedReadCommand.AnimationScales) readAnimationScales() else runRootFixed(args).asText()
        PrivilegedCommandResult(PrivilegedBackend.Root, !output.startsWith("ERROR:"), boundedOutput(output))
    }

    suspend fun setAnimationScales(values: AnimationScales): PrivilegedCommandResult = runInterruptible(ioDispatcher) {
        require(values.isSafe()) { "Animation scales are outside the allowed range." }
        val previous = parseAnimationScales(readAnimationScales())
            ?: return@runInterruptible PrivilegedCommandResult(
                PrivilegedBackend.Root, false, "Could not verify current animation scales; nothing was changed.",
            )
        val failure = writeAnimationScales(values)
        val output = if (failure == null) {
            readAnimationScales()
        } else {
            val rollbackFailure = writeAnimationScales(previous)
            "ERROR: animation write failed; rollback ${if (rollbackFailure == null) "succeeded" else "failed"}\n$failure"
        }
        PrivilegedCommandResult(PrivilegedBackend.Root, !output.startsWith("ERROR:"), boundedOutput(output))
    }

    suspend fun setApplicationEnabledState(packageName: String, state: Int): PrivilegedCommandResult =
        runInterruptible(ioDispatcher) {
            if (!isSafePackageTarget(packageName)) {
                return@runInterruptible PrivilegedCommandResult(PrivilegedBackend.Root, false, "Package is invalid or protected.")
            }
            val verb = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default-state"
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disable-until-used"
                else -> return@runInterruptible PrivilegedCommandResult(
                    PrivilegedBackend.Root, false, "Unsupported PackageManager enabled state.",
                )
            }
            val result = runRootFixed(listOf("/system/bin/pm", verb, "--user", "current", packageName)).asText()
            PrivilegedCommandResult(PrivilegedBackend.Root, !result.startsWith("ERROR:"), boundedOutput(result))
        }

    suspend fun forceStopPackage(packageName: String): PrivilegedCommandResult = runInterruptible(ioDispatcher) {
        if (!isSafePackageTarget(packageName)) {
            return@runInterruptible PrivilegedCommandResult(PrivilegedBackend.Root, false, "Package is invalid or protected.")
        }
        val result = runRootFixed(listOf("/system/bin/am", "force-stop", "--user", "current", packageName)).asText()
        PrivilegedCommandResult(PrivilegedBackend.Root, !result.startsWith("ERROR:"), boundedOutput(result))
    }

    suspend fun snapshotCpuPolicies(): PrivilegedTuningSnapshot? = runInterruptible(ioDispatcher) {
        val policies = buildList {
            for (policyId in CPU_POLICY_RANGE) {
                val parsed = parseCpuPolicySnapshot(policyId, readCpuPolicy(policyId))
                if (parsed != null) add(parsed)
            }
        }
        PrivilegedTuningSnapshot(policies).takeIf { it.isUsable() }
    }

    suspend fun applyCpuTargets(
        targets: PrivilegedTuningTargets,
        baseline: PrivilegedTuningSnapshot,
    ): PrivilegedTuningResult = runInterruptible(ioDispatcher) {
        applyCpuTargetsBlocking(targets.cpuPolicies, baseline)
    }

    suspend fun restoreCpuSnapshot(baseline: PrivilegedTuningSnapshot): PrivilegedTuningResult =
        runInterruptible(ioDispatcher) {
            applyCpuTargetsBlocking(
                baseline.cpuPolicies.map {
                    CpuPolicyTarget(it.policyId, it.governor, it.minimumFrequencyKHz, it.maximumFrequencyKHz)
                },
                baseline,
            )
        }

    private fun applyCpuTargetsBlocking(
        targets: List<CpuPolicyTarget>,
        baseline: PrivilegedTuningSnapshot,
    ): PrivilegedTuningResult {
        if (!baseline.isUsable() || targets.isEmpty() || targets.size != baseline.cpuPolicies.size) {
            return PrivilegedTuningResult.Unavailable("CPU policy set could not be verified.")
        }
        val byId = baseline.cpuPolicies.associateBy { it.policyId }
        if (targets.any { target -> byId[target.policyId]?.let { target.isSafeAgainst(it) } != true }) {
            return PrivilegedTuningResult.Unavailable("CPU target is outside a verified policy range.")
        }
        var changed = 0
        for (target in targets.sortedBy { it.policyId }) {
            val output = writeCpuPolicy(target, byId.getValue(target.policyId))
            if (output.startsWith("ERROR:")) {
                val rollback = baseline.cpuPolicies.sortedBy { it.policyId }.all { policy ->
                    !writeCpuPolicy(
                        CpuPolicyTarget(policy.policyId, policy.governor, policy.minimumFrequencyKHz, policy.maximumFrequencyKHz),
                        policy,
                    ).startsWith("ERROR:")
                }
                return PrivilegedTuningResult.Failed(output, rollback)
            }
            changed += 1
        }
        return PrivilegedTuningResult.Applied(changed, "CPU policies updated; Android thermal management remains enabled.")
    }

    private fun readCpuPolicy(policyId: Int): String {
        if (policyId !in CPU_POLICY_RANGE) return "ERROR: invalid CPU policy"
        fun read(leaf: String): String {
            val path = cpuPath(policyId, leaf)
            return runRootFixed(listOf("/system/bin/cat", path), READ_TIMEOUT_MILLIS).output.lineSequence().firstOrNull()?.trim().orEmpty()
        }
        val governor = read("scaling_governor")
        if (governor.isBlank()) return "ERROR: policy unavailable"
        return buildString {
            appendLine("governor=$governor")
            appendLine("availableGovernors=${read("scaling_available_governors")}")
            appendLine("minimumKHz=${read("scaling_min_freq")}")
            appendLine("maximumKHz=${read("scaling_max_freq")}")
            appendLine("hardwareMinimumKHz=${read("cpuinfo_min_freq")}")
            append("hardwareMaximumKHz=${read("cpuinfo_max_freq")}")
        }
    }

    private fun writeCpuPolicy(target: CpuPolicyTarget, baseline: com.apextuner.core.tuning.CpuPolicySnapshot): String {
        if (!target.isSafeAgainst(baseline)) return "ERROR: target outside verified policy range"
        fun write(leaf: String, value: String): String? {
            val result = runRootFixed(
                listOf("/system/bin/tee", cpuPath(target.policyId, leaf)),
                stdin = value.toByteArray(Charsets.US_ASCII),
            ).asText()
            return result.takeIf { it.startsWith("ERROR:") }
        }

        val widenMaxFirst = target.maximumFrequencyKHz > baseline.maximumFrequencyKHz
        val ordered = buildList {
            if (widenMaxFirst) add("scaling_max_freq" to target.maximumFrequencyKHz.toString())
            add("scaling_min_freq" to target.minimumFrequencyKHz.toString())
            if (!widenMaxFirst) add("scaling_max_freq" to target.maximumFrequencyKHz.toString())
            add("scaling_governor" to target.governor)
            add("scaling_min_freq" to target.minimumFrequencyKHz.toString())
            add("scaling_max_freq" to target.maximumFrequencyKHz.toString())
        }
        for ((leaf, value) in ordered) {
            write(leaf, value)?.let { return it }
        }
        val verified = parseCpuPolicySnapshot(target.policyId, readCpuPolicy(target.policyId))
            ?: return "ERROR: policy verification failed"
        return if (
            verified.governor == target.governor &&
            verified.minimumFrequencyKHz == target.minimumFrequencyKHz &&
            verified.maximumFrequencyKHz == target.maximumFrequencyKHz
        ) "OK" else "ERROR: CPU policy did not verify after write"
    }

    @Suppress("DEPRECATION")
    private fun isSafePackageTarget(packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        return !isProtectedPackageTarget(packageName, context.packageName, info.flags, info.uid)
    }

    private fun writeAnimationScales(values: AnimationScales): String? {
        val pairs = listOf(
            "window_animation_scale" to values.window,
            "transition_animation_scale" to values.transition,
            "animator_duration_scale" to values.animator,
        )
        for ((key, value) in pairs) {
            val result = runRootFixed(listOf("/system/bin/settings", "put", "global", key, formatScale(value))).asText()
            if (result.startsWith("ERROR:")) return result
        }
        return null
    }

    private fun readAnimationScales(): String {
        fun get(key: String): String =
            runRootFixed(listOf("/system/bin/settings", "get", "global", key)).output.lineSequence().firstOrNull()?.trim().orEmpty()
        return "window=${get("window_animation_scale")}\ntransition=${get("transition_animation_scale")}\nanimator=${get("animator_duration_scale")}"
    }

    private fun runRootFixed(
        arguments: List<String>,
        timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
        stdin: ByteArray? = null,
    ): BoundedProcessResult {
        if (
            !isAllowListedRootCommand(arguments) ||
            arguments.any { it.length !in 1..160 || it.any { ch -> ch == '\n' || ch == '\r' || ch == '\u0000' || ch == '\'' } }
        ) return BoundedProcessResult(-1, "ERROR: invalid internal command", false)
        val command = arguments.joinToString(" ") { "'$it'" }
        return BoundedProcessRunner.run(listOf("su", "-c", command), timeoutMillis, MAX_OUTPUT_BYTES, stdin)
    }

    private fun isAllowListedRootCommand(arguments: List<String>): Boolean {
        if (arguments.isEmpty() || arguments.size > 8) return false
        return when (arguments.first()) {
            "/system/bin/id" -> arguments.size == 1
            "/system/bin/dumpsys" -> arguments == listOf("/system/bin/dumpsys", "deviceidle")
            "/system/bin/cat" -> arguments.size == 2 && (
                arguments[1] == "/proc/version" || isAllowListedCpuPath(arguments[1], CPU_READ_LEAVES)
            )
            "/system/bin/tee" -> arguments.size == 2 && isAllowListedCpuPath(arguments[1], CPU_WRITE_LEAVES)
            "/system/bin/pm" ->
                arguments.size == 5 &&
                    arguments[1] in PACKAGE_STATE_VERBS &&
                    arguments[2] == "--user" &&
                    arguments[3] == "current" &&
                    PACKAGE_NAME.matches(arguments[4])
            "/system/bin/am" ->
                arguments.size == 5 &&
                    arguments[1] == "force-stop" &&
                    arguments[2] == "--user" &&
                    arguments[3] == "current" &&
                    PACKAGE_NAME.matches(arguments[4])
            "/system/bin/settings" -> when {
                arguments.size == 4 ->
                    arguments[1] == "get" &&
                        arguments[2] == "global" &&
                        arguments[3] in ANIMATION_KEYS
                arguments.size == 5 ->
                    arguments[1] == "put" &&
                        arguments[2] == "global" &&
                        arguments[3] in ANIMATION_KEYS &&
                        SAFE_SCALE_VALUE.matches(arguments[4])
                else -> false
            }
            else -> false
        }
    }

    private fun isAllowListedCpuPath(path: String, leaves: Set<String>): Boolean {
        val match = CPU_PATH.matchEntire(path) ?: return false
        val policyId = match.groupValues[1].toIntOrNull() ?: return false
        return policyId in CPU_POLICY_RANGE && match.groupValues[2] in leaves
    }

    private fun cpuPath(policyId: Int, leaf: String): String {
        require(policyId in CPU_POLICY_RANGE && leaf in CPU_LEAVES)
        return "/sys/devices/system/cpu/cpufreq/policy$policyId/$leaf"
    }

    private fun BoundedProcessResult.asText(): String = when {
        timedOut -> "ERROR: root command timed out"
        exitCode != 0 -> "ERROR: root command exited with $exitCode${if (output.isNotBlank()) "\n$output" else ""}"
        else -> output
    }

    private fun formatScale(value: Float): String = String.format(Locale.US, "%.2f", value)

    private companion object {
        val CPU_POLICY_RANGE = 0..31
        val CPU_READ_LEAVES = setOf(
            "scaling_governor", "scaling_available_governors", "scaling_min_freq",
            "scaling_max_freq", "cpuinfo_min_freq", "cpuinfo_max_freq",
        )
        val CPU_WRITE_LEAVES = setOf("scaling_governor", "scaling_min_freq", "scaling_max_freq")
        val CPU_LEAVES = CPU_READ_LEAVES
        val CPU_PATH = Regex("""/sys/devices/system/cpu/cpufreq/policy(\d+)/(\w+)""")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        val PACKAGE_STATE_VERBS = setOf("default-state", "enable", "disable", "disable-user", "disable-until-used")
        val ANIMATION_KEYS = setOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val SAFE_SCALE_VALUE = Regex("""\d{1,2}\.\d{2}""")
        const val MAX_OUTPUT_BYTES = 32 * 1024
        const val AUTH_TIMEOUT_MILLIS = 8_000L
        const val COMMAND_TIMEOUT_MILLIS = 6_000L
        const val READ_TIMEOUT_MILLIS = 2_000L
    }
}
