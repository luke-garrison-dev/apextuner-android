package com.apextuner.feature.memory.model

data class ProcessInsight(
    val processName: String,
    val pid: Int,
    val importance: String,
    val packageNames: List<String>,
    val isApexTuner: Boolean,
)

data class MemoryInsights(
    val totalBytes: Long,
    val availableBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
    val pressureSomeAvg10: Double?,
    val apexTunerPssBytes: Long?,
    val apexTunerPrivateDirtyBytes: Long?,
    val nativeHeapBytes: Long,
    val javaHeapUsedBytes: Long,
    val javaHeapMaxBytes: Long,
    val apexTunerTrimLevel: String,
    val apexTunerImportance: String,
    val processes: List<ProcessInsight>,
    val recommendations: List<String>,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Double get() = if (totalBytes <= 0L) 0.0 else (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
}

sealed interface MemoryUiState {
    data object Loading : MemoryUiState
    data class Ready(val insights: MemoryInsights) : MemoryUiState
    data class Error(val message: String) : MemoryUiState
}
