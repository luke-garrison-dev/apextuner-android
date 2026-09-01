package com.apextuner.feature.tools.advanced

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.annotation.Keep
import java.util.Locale

class ApexPrivilegedUserService : IPrivilegedUserService.Stub {
    private var serviceContext: Context? = null

    constructor() : super()

    @Keep
    constructor(context: Context) : super() {
        serviceContext = context.applicationContext
    }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }

    override fun executeReadOnly(commandId: Int): String = when (commandId) {
        PrivilegedReadCommand.Identity.id -> "uid=${Process.myUid()}\n" + executeFixed(listOf("/system/bin/id"))
        PrivilegedReadCommand.DeviceIdleState.id -> executeFixed(listOf("/system/bin/dumpsys", "deviceidle"))
        PrivilegedReadCommand.AnimationScales.id -> readAnimationScales()
        PrivilegedReadCommand.KernelVersion.id -> executeFixed(listOf("/system/bin/cat", "/proc/version"))
        else -> "ERROR: unsupported command id"
    }

    override fun setAnimationScales(windowScale: Float, transitionScale: Float, animatorScale: Float): String {
        val values = AnimationScales(windowScale, transitionScale, animatorScale)
        if (!values.isSafe()) return "ERROR: animation scale outside safe range"
        val previousText = readAnimationScales()
        val previous = parseAnimationScales(previousText)
            ?: return "ERROR: current animation scales could not be verified; nothing was changed"
        val result = writeAnimationScales(values)
        if (result != null) {
            val rollback = writeAnimationScales(previous)
            return "ERROR: animation write failed; rollback ${if (rollback == null) "succeeded" else "failed"}\n$result"
        }
        return readAnimationScales()
    }

    override fun setApplicationEnabledState(packageName: String, state: Int): String {
        if (!PACKAGE_NAME_REGEX.matches(packageName) || !isRestorableEnabledState(state)) {
            return "ERROR: invalid package state request"
        }
        val context = serviceContext ?: return "ERROR: privileged PackageManager context unavailable"
        if (!isSafePackageTarget(context, packageName)) return "ERROR: package is protected"
        return try {
            val manager = context.packageManager
            manager.setApplicationEnabledSetting(packageName, state, 0)
            val verified = manager.getApplicationEnabledSetting(packageName)
            if (verified == state) "state=$verified" else "ERROR: package enabled state did not verify"
        } catch (error: Throwable) {
            "ERROR: ${error.javaClass.simpleName}"
        }
    }

    override fun forceStopPackage(packageName: String): String {
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return "ERROR: invalid package name"
        val context = serviceContext ?: return "ERROR: privileged PackageManager context unavailable"
        if (!isSafePackageTarget(context, packageName)) return "ERROR: package is protected"
        return executeFixed(listOf("/system/bin/am", "force-stop", "--user", "current", packageName))
            .ifBlank { "OK" }
    }

    override fun readCpuPolicy(policyId: Int): String {
        if (policyId !in CPU_POLICY_RANGE) return "ERROR: invalid CPU policy"
        val governor = readCpuValue(policyId, "scaling_governor") ?: return "ERROR: CPU policy unavailable"
        val available = readCpuValue(policyId, "scaling_available_governors") ?: return "ERROR: CPU governors unavailable"
        val minimum = readCpuValue(policyId, "scaling_min_freq") ?: return "ERROR: CPU minimum unavailable"
        val maximum = readCpuValue(policyId, "scaling_max_freq") ?: return "ERROR: CPU maximum unavailable"
        val hardwareMinimum = readCpuValue(policyId, "cpuinfo_min_freq") ?: return "ERROR: CPU hardware minimum unavailable"
        val hardwareMaximum = readCpuValue(policyId, "cpuinfo_max_freq") ?: return "ERROR: CPU hardware maximum unavailable"
        return buildString {
            appendLine("governor=${governor.trim()}")
            appendLine("availableGovernors=${available.trim()}")
            appendLine("minimumKHz=${minimum.trim()}")
            appendLine("maximumKHz=${maximum.trim()}")
            appendLine("hardwareMinimumKHz=${hardwareMinimum.trim()}")
            append("hardwareMaximumKHz=${hardwareMaximum.trim()}")
        }
    }

    override fun writeCpuPolicy(policyId: Int, governor: String, minimumKHz: Long, maximumKHz: Long): String {
        if (policyId !in CPU_POLICY_RANGE || !GOVERNOR_REGEX.matches(governor)) return "ERROR: invalid CPU policy request"
        val before = parseCpuPolicySnapshot(policyId, readCpuPolicy(policyId))
            ?: return "ERROR: current CPU policy could not be verified; nothing was changed"
        if (governor !in before.availableGovernors ||
            minimumKHz !in before.hardwareMinimumFrequencyKHz..before.hardwareMaximumFrequencyKHz ||
            maximumKHz !in minimumKHz..before.hardwareMaximumFrequencyKHz
        ) {
            return "ERROR: requested CPU values are outside the verified policy range"
        }

        val failure = writeCpuValues(policyId, governor, minimumKHz, maximumKHz, before.minimumFrequencyKHz, before.maximumFrequencyKHz)
        if (failure != null) {
            val rollbackFailure = writeCpuValues(
                policyId,
                before.governor,
                before.minimumFrequencyKHz,
                before.maximumFrequencyKHz,
                minimumKHz,
                maximumKHz,
            )
            val rollbackVerified = parseCpuPolicySnapshot(policyId, readCpuPolicy(policyId))?.let {
                it.governor == before.governor &&
                    it.minimumFrequencyKHz == before.minimumFrequencyKHz &&
                    it.maximumFrequencyKHz == before.maximumFrequencyKHz
            } == true
            return "ERROR: CPU policy write failed; rollback ${if (rollbackFailure == null && rollbackVerified) "verified" else "unverified"}\n$failure"
        }

        val after = parseCpuPolicySnapshot(policyId, readCpuPolicy(policyId))
            ?: return "ERROR: CPU policy changed but verification failed"
        return if (
            after.governor == governor &&
            after.minimumFrequencyKHz == minimumKHz &&
            after.maximumFrequencyKHz == maximumKHz
        ) {
            "policy=$policyId\ngovernor=$governor\nminimumKHz=$minimumKHz\nmaximumKHz=$maximumKHz"
        } else {
            val rollbackFailure = writeCpuValues(
                policyId,
                before.governor,
                before.minimumFrequencyKHz,
                before.maximumFrequencyKHz,
                after.minimumFrequencyKHz,
                after.maximumFrequencyKHz,
            )
            "ERROR: CPU policy verification mismatch; rollback ${if (rollbackFailure == null) "attempted" else "failed"}"
        }
    }

    @Suppress("DEPRECATION")
    private fun isSafePackageTarget(context: Context, packageName: String): Boolean {
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        return !isProtectedPackageTarget(packageName, context.packageName, info.flags, info.uid)
    }

    private fun writeAnimationScales(values: AnimationScales): String? {
        val commands = listOf(
            listOf("/system/bin/settings", "put", "global", "window_animation_scale", formatScale(values.window)),
            listOf("/system/bin/settings", "put", "global", "transition_animation_scale", formatScale(values.transition)),
            listOf("/system/bin/settings", "put", "global", "animator_duration_scale", formatScale(values.animator)),
        )
        for (command in commands) {
            val result = executeFixed(command)
            if (result.startsWith("ERROR:")) return result
        }
        return null
    }

    private fun readAnimationScales(): String {
        val window = executeSettingGet("window_animation_scale")
        val transition = executeSettingGet("transition_animation_scale")
        val animator = executeSettingGet("animator_duration_scale")
        return "window=$window\ntransition=$transition\nanimator=$animator"
    }

    private fun executeSettingGet(key: String): String = executeFixed(
        listOf("/system/bin/settings", "get", "global", key),
    ).lineSequence().firstOrNull()?.trim().orEmpty()

    private fun readCpuValue(policyId: Int, leaf: String): String? {
        if (leaf !in CPU_READ_LEAVES) return null
        val result = executeFixed(listOf("/system/bin/cat", cpuPath(policyId, leaf)))
        return result.takeUnless { it.startsWith("ERROR:") || it.isBlank() }
    }

    private fun writeCpuValues(
        policyId: Int,
        governor: String,
        targetMinimum: Long,
        targetMaximum: Long,
        currentMinimum: Long,
        currentMaximum: Long,
    ): String? {
        if (!GOVERNOR_REGEX.matches(governor) || targetMinimum <= 0L || targetMaximum < targetMinimum) {
            return "ERROR: invalid CPU target"
        }

        fun write(leaf: String, value: String): String? {
            if (leaf !in CPU_WRITE_LEAVES) return "ERROR: CPU leaf not allow-listed"
            val bytes = "$value\n".toByteArray(Charsets.US_ASCII)
            val result = BoundedProcessRunner.run(
                arguments = listOf("/system/bin/tee", cpuPath(policyId, leaf)),
                timeoutMillis = COMMAND_TIMEOUT_MILLIS,
                maximumOutputBytes = MAX_OUTPUT_BYTES,
                stdin = bytes,
            )
            return when {
                result.timedOut -> "ERROR: CPU write timed out"
                result.exitCode != 0 -> "ERROR: CPU write exited with ${result.exitCode}"
                else -> null
            }
        }

        if (targetMinimum > currentMaximum) {
            write("scaling_max_freq", targetMaximum.toString())?.let { return it }
            write("scaling_min_freq", targetMinimum.toString())?.let { return it }
        } else if (targetMaximum < currentMinimum) {
            write("scaling_min_freq", targetMinimum.toString())?.let { return it }
            write("scaling_max_freq", targetMaximum.toString())?.let { return it }
        } else {
            write("scaling_min_freq", targetMinimum.toString())?.let { return it }
            write("scaling_max_freq", targetMaximum.toString())?.let { return it }
        }
        write("scaling_governor", governor)?.let { return it }
        write("scaling_min_freq", targetMinimum.toString())?.let { return it }
        write("scaling_max_freq", targetMaximum.toString())?.let { return it }
        return null
    }

    private fun cpuPath(policyId: Int, leaf: String): String =
        "/sys/devices/system/cpu/cpufreq/policy$policyId/$leaf"

    private fun executeFixed(arguments: List<String>, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): String {
        if (arguments.isEmpty() || arguments.size > 8 || arguments.any { it.length !in 1..256 || it.contains('\n') || it.contains('\r') || it.contains('\u0000') }) {
            return "ERROR: invalid internal command"
        }
        val result = BoundedProcessRunner.run(arguments, timeoutMillis, MAX_OUTPUT_BYTES)
        return when {
            result.timedOut -> "ERROR: command timed out"
            result.exitCode != 0 -> "ERROR: exit=${result.exitCode}${if (result.output.isNotBlank()) "\n${result.output}" else ""}"
            else -> result.output
        }
    }

    private fun formatScale(value: Float): String = String.format(Locale.US, "%.2f", value)

    private companion object {
        val PACKAGE_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        val GOVERNOR_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
        val CPU_POLICY_RANGE = 0..31
        val CPU_READ_LEAVES = setOf(
            "scaling_governor",
            "scaling_available_governors",
            "scaling_min_freq",
            "scaling_max_freq",
            "cpuinfo_min_freq",
            "cpuinfo_max_freq",
        )
        val CPU_WRITE_LEAVES = setOf("scaling_governor", "scaling_min_freq", "scaling_max_freq")
        const val MAX_OUTPUT_BYTES = 32 * 1024
        const val COMMAND_TIMEOUT_MILLIS = 5_000L
    }
}
