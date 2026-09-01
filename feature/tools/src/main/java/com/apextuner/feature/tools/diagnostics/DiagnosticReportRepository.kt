package com.apextuner.feature.tools.diagnostics

import android.content.Context
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import com.apextuner.core.database.BatteryHealthSnapshotDao
import com.apextuner.core.database.DeviceHealthSampleDao
import com.apextuner.core.database.GameSessionRecordDao
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.feature.tools.security.SecurityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class DiagnosticReportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val securityRepository: SecurityRepository,
    private val healthSamples: DeviceHealthSampleDao,
    private val batteryHealth: BatteryHealthSnapshotDao,
    private val gameSessions: GameSessionRecordDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun capture(): DiagnosticCapture = withContext(io) {
        val device = deviceRepository.snapshot()
        val launcher = context.getSystemService(LauncherApps::class.java)
        val appCount = runCatching {
            launcher.getActivityList(null, Process.myUserHandle()).map { it.applicationInfo.packageName }.distinct().size
        }.getOrDefault(0)
        DiagnosticCapture(
            capturedAtEpochMillis = System.currentTimeMillis(),
            device = device,
            security = securityRepository.snapshot(),
            launchableAppCount = appCount,
            healthSampleCount = bestEffort(0) { healthSamples.recent(1_000).size },
            batteryHealthSnapshotCount = bestEffort(0) { batteryHealth.recent(180).size },
            recentGameSessionCount = bestEffort(0) { gameSessions.recent(100).size },
        )
    }

    suspend fun export(
        destination: Uri,
        format: DiagnosticReportFormat,
        current: DiagnosticCapture,
        baseline: DiagnosticCapture?,
        sections: Set<DiagnosticReportSection>,
    ): Long = withContext(io) {
        require(sections.isNotEmpty()) { "Select at least one report section." }
        val text = when (format) {
            DiagnosticReportFormat.Json -> buildJson(current, baseline, sections)
            DiagnosticReportFormat.Html -> buildHtml(current, baseline, sections)
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_REPORT_BYTES) { "The generated report exceeded ApexTuner's local export limit." }
        val resolver = context.contentResolver
        val output = resolver.openOutputStream(destination, "w") ?: throw IllegalStateException("The selected report destination could not be opened.")
        try {
            output.buffered().use { it.write(bytes) }
        } catch (error: Throwable) {
            runCatching { resolver.delete(destination, null, null) }
            throw error
        }
        bytes.size.toLong()
    }

    private fun buildJson(current: DiagnosticCapture, baseline: DiagnosticCapture?, sections: Set<DiagnosticReportSection>): String {
        val root = JSONObject()
            .put("format", "ApexTuner diagnostic report v1")
            .put("generatedAtEpochMillis", current.capturedAtEpochMillis)
            .put("privacy", "Local report. No account identifiers, contact contents, filenames, notification contents, or raw app inventory are included.")
        root.put("current", captureJson(current, sections))
        baseline?.let {
            root.put("baseline", captureJson(it, sections))
            root.put("comparison", comparisonJson(compareCaptures(it, current)))
        }
        return root.toString(2)
    }

    private fun captureJson(capture: DiagnosticCapture, sections: Set<DiagnosticReportSection>): JSONObject = JSONObject().apply {
        put("capturedAtEpochMillis", capture.capturedAtEpochMillis)
        val d = capture.device
        if (DiagnosticReportSection.Device in sections) put("device", JSONObject()
            .put("cpuLogicalCores", d.cpu.logicalCoreCount)
            .put("cpuUsagePercent", d.cpu.totalUsagePercent ?: JSONObject.NULL)
            .put("thermalStatus", d.thermalStatus.name)
            .put("uptimeMillis", d.uptimeMillis)
            .put("launchableAppCount", capture.launchableAppCount))
        if (DiagnosticReportSection.Battery in sections) put("battery", JSONObject()
            .put("levelPercent", d.battery.levelPercent ?: JSONObject.NULL)
            .put("temperatureCelsius", d.battery.temperatureCelsius ?: JSONObject.NULL)
            .put("charging", d.battery.charging)
            .put("health", d.battery.health.name)
            .put("cycleCount", d.battery.cycleCount ?: JSONObject.NULL)
            .put("currentMicroamps", d.battery.currentMicroamps ?: JSONObject.NULL))
        if (DiagnosticReportSection.MemoryStorage in sections) put("memoryStorage", JSONObject()
            .put("memoryTotalBytes", d.memory.totalBytes)
            .put("memoryAvailableBytes", d.memory.availableBytes)
            .put("lowMemory", d.memory.lowMemory)
            .put("internalStorageTotalBytes", d.storage.internal.totalBytes)
            .put("internalStorageAvailableBytes", d.storage.internal.availableBytes))
        if (DiagnosticReportSection.Network in sections) put("network", JSONObject()
            .put("validated", d.network.activeNetworkValidated)
            .put("metered", d.network.metered)
            .put("totalRxBytes", d.network.totalRxBytes ?: JSONObject.NULL)
            .put("totalTxBytes", d.network.totalTxBytes ?: JSONObject.NULL))
        if (DiagnosticReportSection.Security in sections) put("security", JSONObject()
            .put("secureScreenLock", capture.security.secureScreenLock)
            .put("deviceLockedNow", capture.security.deviceLockedNow)
            .put("potentialRootSignal", capture.security.rootBinaryPotentiallyPresent)
            .put("unknownSourceInstallAccess", capture.security.appCanInstallUnknownPackages)
            .put("securityPatchLevel", capture.security.securityPatchLevel ?: JSONObject.NULL)
            .put("securityPatchAgeDays", capture.security.securityPatchAgeDays ?: JSONObject.NULL)
            .put("advancedProtectionEnabled", capture.security.advancedProtectionEnabled ?: JSONObject.NULL))
        if (DiagnosticReportSection.History in sections) put("localHistory", JSONObject()
            .put("healthSamplesRetained", capture.healthSampleCount)
            .put("batteryHealthSnapshotsRetained", capture.batteryHealthSnapshotCount)
            .put("recentGameSessionsRetained", capture.recentGameSessionCount))
    }

    private fun comparisonJson(value: DiagnosticComparison): JSONObject = JSONObject()
        .put("baselineCapturedAtEpochMillis", value.baselineCapturedAtEpochMillis)
        .put("currentCapturedAtEpochMillis", value.currentCapturedAtEpochMillis)
        .put("elapsedMillis", value.elapsedMillis)
        .put("batteryDeltaPercent", value.batteryDeltaPercent ?: JSONObject.NULL)
        .put("memoryAvailableDeltaBytes", value.memoryAvailableDeltaBytes)
        .put("storageAvailableDeltaBytes", value.storageAvailableDeltaBytes)
        .put("batteryTemperatureDeltaCelsius", value.batteryTemperatureDeltaCelsius ?: JSONObject.NULL)
        .put("rxDeltaBytes", value.rxDeltaBytes ?: JSONObject.NULL)
        .put("txDeltaBytes", value.txDeltaBytes ?: JSONObject.NULL)

    private fun buildHtml(current: DiagnosticCapture, baseline: DiagnosticCapture?, sections: Set<DiagnosticReportSection>): String {
        val rows = mutableListOf<Pair<String, String>>()
        fun row(name: String, value: Any?) { rows += name to (value?.toString() ?: "Unavailable") }
        val d = current.device
        if (DiagnosticReportSection.Device in sections) {
            row("CPU logical cores", d.cpu.logicalCoreCount); row("CPU usage", d.cpu.totalUsagePercent?.let { String.format(Locale.US, "%.1f%%", it) }); row("Thermal status", d.thermalStatus.name); row("Launchable apps visible", current.launchableAppCount)
        }
        if (DiagnosticReportSection.Battery in sections) {
            row("Battery level", d.battery.levelPercent?.let { "$it%" }); row("Battery temperature", d.battery.temperatureCelsius?.let { String.format(Locale.US, "%.1f °C", it) }); row("Battery health", d.battery.health.name); row("Cycle count", d.battery.cycleCount)
        }
        if (DiagnosticReportSection.MemoryStorage in sections) {
            row("Memory available bytes", d.memory.availableBytes); row("Memory total bytes", d.memory.totalBytes); row("Internal storage available bytes", d.storage.internal.availableBytes); row("Internal storage total bytes", d.storage.internal.totalBytes)
        }
        if (DiagnosticReportSection.Network in sections) {
            row("Network validated", d.network.activeNetworkValidated); row("Network metered", d.network.metered); row("Total RX bytes", d.network.totalRxBytes); row("Total TX bytes", d.network.totalTxBytes)
        }
        if (DiagnosticReportSection.Security in sections) {
            row("Secure screen lock", current.security.secureScreenLock); row("Potential root signal", current.security.rootBinaryPotentiallyPresent); row("Security patch", current.security.securityPatchLevel); row("Security patch age days", current.security.securityPatchAgeDays); row("Advanced Protection", current.security.advancedProtectionEnabled)
        }
        if (DiagnosticReportSection.History in sections) {
            row("Health samples retained", current.healthSampleCount); row("Battery health snapshots retained", current.batteryHealthSnapshotCount); row("Recent game sessions retained", current.recentGameSessionCount)
        }
        val comparisonRows = baseline?.let { compareCaptures(it, current) }
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ApexTuner Diagnostic Report</title>")
            append("<style>body{font-family:system-ui,sans-serif;max-width:920px;margin:32px auto;padding:0 18px;line-height:1.45}table{border-collapse:collapse;width:100%}td,th{padding:9px;border-bottom:1px solid #bbb;text-align:left}small{opacity:.75}</style></head><body>")
            append("<h1>ApexTuner Diagnostic Report</h1><p><small>Generated ${escapeHtml(formatTime(current.capturedAtEpochMillis))}. Local report; sensitive contents and raw app inventory are excluded.</small></p><table><tbody>")
            rows.forEach { (name, value) -> append("<tr><th>${escapeHtml(name)}</th><td>${escapeHtml(value)}</td></tr>") }
            append("</tbody></table>")
            if (comparisonRows != null) {
                append("<h2>Before / after comparison</h2><table><tbody>")
                listOf(
                    "Comparison window milliseconds" to comparisonRows.elapsedMillis,
                    "Battery delta" to comparisonRows.batteryDeltaPercent?.let { "$it%" },
                    "Available-memory delta bytes" to comparisonRows.memoryAvailableDeltaBytes,
                    "Available-storage delta bytes" to comparisonRows.storageAvailableDeltaBytes,
                    "Battery-temperature delta" to comparisonRows.batteryTemperatureDeltaCelsius?.let { String.format(Locale.US, "%.1f °C", it) },
                    "Network traffic since baseline bytes" to comparisonRows.totalNetworkDeltaBytes(),
                    "RX delta bytes" to comparisonRows.rxDeltaBytes,
                    "TX delta bytes" to comparisonRows.txDeltaBytes,
                ).forEach { (name, value) -> append("<tr><th>${escapeHtml(name)}</th><td>${escapeHtml(value?.toString() ?: "Unavailable")}</td></tr>") }
                append("</tbody></table>")
            }
            append("</body></html>")
        }
    }

    private suspend fun <T> bestEffort(default: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        default
    }

    private fun formatTime(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(epochMillis))
    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private companion object { const val MAX_REPORT_BYTES = 2 * 1024 * 1024 }
}
