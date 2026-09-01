package com.apextuner.feature.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.apextuner.core.di.IoDispatcher
import com.apextuner.feature.memory.model.MemoryInsights
import com.apextuner.feature.memory.model.ProcessInsight
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface MemoryRepository {
    suspend fun readInsights(): MemoryInsights
}

@Singleton
class AndroidMemoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MemoryRepository {
    override suspend fun readInsights(): MemoryInsights = withContext(ioDispatcher) {
        val activity = context.getSystemService(ActivityManager::class.java)
        val system = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val ownMemory = runCatching { activity.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() }.getOrNull()
        val runtime = Runtime.getRuntime()
        val ownState = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val swap = readText("/proc/meminfo")?.let(MemoryParsers::parseSwap)
        val pressure = readText("/proc/pressure/memory")?.let(MemoryParsers::parsePressureSomeAvg10)
        val processes = readProcesses(activity)

        MemoryInsights(
            totalBytes = system.totalMem,
            availableBytes = system.availMem,
            thresholdBytes = system.threshold,
            lowMemory = system.lowMemory,
            swapTotalBytes = swap?.totalBytes,
            swapFreeBytes = swap?.freeBytes,
            pressureSomeAvg10 = pressure,
            apexTunerPssBytes = ownMemory?.totalPss?.toLong()?.times(1024L),
            apexTunerPrivateDirtyBytes = ownMemory?.totalPrivateDirty?.toLong()?.times(1024L),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            javaHeapUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L),
            javaHeapMaxBytes = runtime.maxMemory(),
            apexTunerTrimLevel = trimLevelName(ownState.lastTrimLevel),
            apexTunerImportance = importanceName(ownState.importance),
            processes = processes,
            recommendations = MemoryRecommendationEngine.evaluate(system.lowMemory, system.availMem, system.threshold, pressure),
        )
    }

    private fun readProcesses(activity: ActivityManager): List<ProcessInsight> = runCatching {
        activity.runningAppProcesses.orEmpty()
            .sortedWith(compareBy<ActivityManager.RunningAppProcessInfo>({ if (it.uid == Process.myUid()) 0 else 1 }, { it.importance }))
            .take(MAX_PROCESS_ROWS)
            .map { process ->
            ProcessInsight(
                processName = process.processName.orEmpty(),
                pid = process.pid,
                importance = importanceName(process.importance),
                packageNames = process.pkgList?.toList().orEmpty(),
                isApexTuner = process.uid == Process.myUid(),
            )
        }
    }.getOrDefault(emptyList())

    private fun importanceName(value: Int): String = when (value) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "Cached"
        else -> "Other ($value)"
    }

    private fun trimLevelName(value: Int): String = when (value) {
        0 -> "Normal"
        5 -> "Running moderate"
        10 -> "Running low"
        15 -> "Running critical"
        20 -> "UI hidden"
        40 -> "Background"
        60 -> "Moderate"
        80 -> "Complete"
        else -> "Level $value"
    }

    private fun readText(path: String): String? = runCatching {
        val file = File(path)
        if (file.canRead()) file.readText() else null
    }.getOrNull()

    private companion object { const val MAX_PROCESS_ROWS = 64 }
}
