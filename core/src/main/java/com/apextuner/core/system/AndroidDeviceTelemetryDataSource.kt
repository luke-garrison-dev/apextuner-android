package com.apextuner.core.system

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.SystemClock
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.CpuSnapshot
import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.model.GpuSnapshot
import com.apextuner.core.model.MemorySnapshot
import com.apextuner.core.model.NetworkSnapshot
import com.apextuner.core.model.StorageSnapshot
import com.apextuner.core.model.StorageVolumeSnapshot
import com.apextuner.core.model.ThermalStatus
import com.apextuner.core.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class AndroidDeviceTelemetryDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider,
    private val batteryTelemetryReader: BatteryTelemetryReader,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DeviceTelemetryDataSource {

    private val cpuUsageTracker = CpuUsageTracker()
    private val storageSampleLock = Any()
    private var cachedStorage: TimedStorageSnapshot? = null

    private val gpuUtilizationFile: File? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        findGpuUtilizationFile()
    }

    override suspend fun readSnapshot(): DeviceSnapshot = withContext(ioDispatcher) {
        DeviceSnapshot(
            capturedAtEpochMillis = timeProvider.nowEpochMillis(),
            uptimeMillis = SystemClock.elapsedRealtime(),
            cpu = readCpu(),
            gpu = readGpu(),
            memory = readMemory(),
            storage = readStorageCached(SystemClock.elapsedRealtime()),
            battery = batteryTelemetryReader.read(),
            network = readNetwork(),
            thermalStatus = readThermalStatus(),
        )
    }

    private fun readCpu(): CpuSnapshot {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val frequencies = (0 until cores).map { core ->
            readLongOrNull(File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"))
        }
        val usage = cpuUsageTracker.update(readCpuCounters())
        return CpuSnapshot(
            logicalCoreCount = cores,
            totalUsagePercent = usage.percent,
            currentFrequenciesKhz = frequencies,
            usageAvailability = usage.availability,
        )
    }

    private fun readCpuCounters(): CpuCounterRead {
        val procStat = File(PROC_STAT_PATH)
        if (!procStat.canRead()) return CpuCounterRead.RestrictedByPlatform

        return try {
            val line = procStat.useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            } ?: return CpuCounterRead.Unavailable
            val counters = TelemetryParsers.parseCpuCounters(line) ?: return CpuCounterRead.Unavailable
            CpuCounterRead.Success(
                counters = counters,
                uptimeMillis = SystemClock.elapsedRealtime(),
            )
        } catch (_: SecurityException) {
            CpuCounterRead.RestrictedByPlatform
        } catch (_: FileNotFoundException) {
            CpuCounterRead.RestrictedByPlatform
        } catch (_: IOException) {
            if (procStat.canRead()) CpuCounterRead.Unavailable else CpuCounterRead.RestrictedByPlatform
        }
    }

    private fun readGpu(): GpuSnapshot {
        val utilization = gpuUtilizationFile?.let(::readGpuUtilizationPercent)
        return GpuSnapshot(utilizationPercent = utilization)
    }

    private fun findGpuUtilizationFile(): File? {
        val fixedCandidates = listOf(
            File("/sys/class/kgsl/kgsl-3d0/gpubusy"),
            File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
        )
        fixedCandidates.firstOrNull { it.isFile && it.canRead() }?.let { return it }

        return runCatching {
            File("/sys/class/devfreq").listFiles()
                .orEmpty()
                .asSequence()
                .filter { directory ->
                    val name = directory.name.lowercase()
                    name.contains("gpu") || name.contains("mali") || name.contains("kgsl")
                }
                .flatMap { directory ->
                    sequenceOf(File(directory, "load"), File(directory, "utilization"))
                }
                .firstOrNull { it.isFile && it.canRead() }
        }.getOrNull()
    }

    private fun readGpuUtilizationPercent(file: File): Double? = runCatching {
        TelemetryParsers.parseGpuUtilizationPercent(file.name, file.readText())
    }.getOrNull()

    private fun readMemory(): MemorySnapshot {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return MemorySnapshot(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            lowMemory = info.lowMemory,
            thresholdBytes = info.threshold,
        )
    }

    private fun readStorageCached(nowUptimeMillis: Long): StorageSnapshot = synchronized(storageSampleLock) {
        cachedStorage
            ?.takeIf { nowUptimeMillis - it.uptimeMillis in 0..STORAGE_CACHE_TTL_MILLIS }
            ?.snapshot
            ?: readStorage().also { fresh ->
                // Serialize cache misses too: overlapping dashboard/monitor callers must not stampede
                // StorageManager/StatFs with duplicate work. Timestamp after the read for a full TTL.
                cachedStorage = TimedStorageSnapshot(SystemClock.elapsedRealtime(), fresh)
            }
    }

    private fun readStorage(): StorageSnapshot {
        val internal = statFsSnapshot(Environment.getDataDirectory())
        val primarySharedDirectory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)
                .storageVolumes
                .firstOrNull { it.isPrimary }
                ?.directory
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        }
        val shared = primarySharedDirectory?.let { directory ->
            runCatching { statFsSnapshot(directory) }.getOrNull()
        }
        val uniqueShared = shared?.takeUnless {
            it.totalBytes == internal.totalBytes && it.availableBytes == internal.availableBytes
        }
        return StorageSnapshot(internal = internal, primaryShared = uniqueShared)
    }

    private fun statFsSnapshot(file: File): StorageVolumeSnapshot {
        val stats = StatFs(file.absolutePath)
        return StorageVolumeSnapshot(
            totalBytes = stats.totalBytes,
            availableBytes = stats.availableBytes,
        )
    }

    private fun readNetwork(): NetworkSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val rx = TrafficStats.getTotalRxBytes().takeUnless { it == TrafficStats.UNSUPPORTED.toLong() }
        val tx = TrafficStats.getTotalTxBytes().takeUnless { it == TrafficStats.UNSUPPORTED.toLong() }
        return NetworkSnapshot(
            totalRxBytes = rx,
            totalTxBytes = tx,
            activeNetworkValidated = validated,
            metered = connectivity.isActiveNetworkMetered,
        )
    }

    private fun readThermalStatus(): ThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.Unknown
        return when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.None
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.Light
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.Moderate
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.Severe
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.Critical
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.Emergency
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.Shutdown
            else -> ThermalStatus.Unknown
        }
    }

    private fun readLongOrNull(file: File): Long? = runCatching {
        if (!file.canRead()) null else file.readText().trim().toLongOrNull()?.takeIf { it >= 0L }
    }.getOrNull()

    private data class TimedStorageSnapshot(
        val uptimeMillis: Long,
        val snapshot: StorageSnapshot,
    )

    private companion object {
        const val PROC_STAT_PATH = "/proc/stat"
        const val STORAGE_CACHE_TTL_MILLIS = 10_000L
    }
}
