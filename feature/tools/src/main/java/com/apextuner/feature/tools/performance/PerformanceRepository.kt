package com.apextuner.feature.tools.performance

import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.ThermalStatus
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface PerformanceRepository {
    suspend fun readInsights(): PerformanceInsights
    suspend fun applyProfile(profile: SystemProfile): ProfileApplyResult
}

@Singleton
class AndroidPerformanceRepository @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val capabilityManager: CapabilityManager,
    private val tuningController: SafeSystemTuningController,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PerformanceRepository {
    override suspend fun readInsights(): PerformanceInsights = withContext(ioDispatcher) {
        val snapshot = deviceRepository.snapshot()
        val cores = (0 until snapshot.cpu.logicalCoreCount.coerceIn(1, MAX_CPU_CORES)).map { index ->
            val base = "/sys/devices/system/cpu/cpu$index/cpufreq"
            CpuCoreInsight(
                core = index,
                currentKhz = snapshot.cpu.currentFrequenciesKhz.getOrNull(index),
                minKhz = readLong("$base/cpuinfo_min_freq") ?: readLong("$base/scaling_min_freq"),
                maxKhz = readLong("$base/cpuinfo_max_freq") ?: readLong("$base/scaling_max_freq"),
                governor = readToken("$base/scaling_governor"),
            )
        }
        PerformanceInsights(
            cpuUsagePercent = snapshot.cpu.totalUsagePercent,
            cores = cores,
            gpuUsagePercent = snapshot.gpu.utilizationPercent,
            thermalStatus = snapshot.thermalStatus,
            ioSchedulers = readSchedulers(),
            tcpCongestionAlgorithm = readToken("/proc/sys/net/ipv4/tcp_congestion_control"),
            rootPotentiallyAvailable = capabilityManager.status(Capability.RootAccess).state == CapabilityState.Available,
            activeProfile = tuningController.activeProfile(),
            recommendations = recommendations(snapshot.thermalStatus, snapshot.cpu.totalUsagePercent),
        )
    }

    override suspend fun applyProfile(profile: SystemProfile): ProfileApplyResult = tuningController.apply(profile)

    private fun readSchedulers(): List<String> = runCatching {
        File("/sys/block").listFiles().orEmpty().asSequence()
            .mapNotNull { block ->
                val raw = readText(File(block, "queue/scheduler")) ?: return@mapNotNull null
                PerformanceParsers.selectedScheduler(raw)?.let { "${block.name}: $it" }
            }
            .take(16)
            .toList()
    }.getOrDefault(emptyList())

    private fun readLong(path: String): Long? = readText(File(path))?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    private fun readToken(path: String): String? = readText(File(path))?.let(PerformanceParsers::safeKernelToken)
    private fun readText(file: File): String? = runCatching { if (file.canRead()) file.readText() else null }.getOrNull()

    private fun recommendations(thermal: ThermalStatus, cpuUsage: Double?): List<String> {
        val result = mutableListOf<String>()
        if (thermal in setOf(ThermalStatus.Severe, ThermalStatus.Critical, ThermalStatus.Emergency, ThermalStatus.Shutdown)) result += "Thermal throttling is significant. Reduce workload and let Android manage cooling; disabling thermal protection is unsafe."
        if ((cpuUsage ?: 0.0) >= 85.0) result += "CPU load is high. Identify the foreground workload before changing any advanced tuning parameter."
        if (result.isEmpty()) result += "No severe CPU or thermal condition is currently reported."
        result += "Governor, I/O, LMK/VM and secure animation controls remain read-only until an explicit privileged session is authorized."
        return result
    }

    private companion object { const val MAX_CPU_CORES = 256 }
}
