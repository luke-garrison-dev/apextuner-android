package com.apextuner.feature.tools.systeminfo

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaDrm
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.util.DisplayMetrics
import android.view.WindowManager
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.system.BatteryTelemetryReader
import com.apextuner.core.util.ByteSizeFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class SystemInfoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val batteryReader: BatteryTelemetryReader,
    private val capabilityManager: CapabilityManager,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun snapshot(): SystemInfoSnapshot = withContext(io) {
        val diagnostics = mutableListOf<String>()
        val sections = buildList {
            add(deviceSection())
            add(cpuKernelSection())
            add(memoryStorageSection())
            add(displaySection())
            add(batterySection(diagnostics))
            add(sensorSection())
            add(cameraSection(diagnostics))
            add(drmSection(diagnostics))
            add(securitySection())
        }
        SystemInfoSnapshot(sections, diagnostics.distinct())
    }

    private fun deviceSection() = SystemInfoSection(
        "Device & Android",
        listOf(
            SystemInfoRow("Manufacturer", Build.MANUFACTURER.safe()),
            SystemInfoRow("Brand", Build.BRAND.safe()),
            SystemInfoRow("Model", Build.MODEL.safe()),
            SystemInfoRow("Device", Build.DEVICE.safe()),
            SystemInfoRow("Product", Build.PRODUCT.safe()),
            SystemInfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            SystemInfoRow("Build", Build.DISPLAY.safe()),
            SystemInfoRow("Security patch", Build.VERSION.SECURITY_PATCH.safe("Unavailable")),
            SystemInfoRow("Supported ABIs", Build.SUPPORTED_ABIS.joinToString().ifBlank { "Unavailable" }),
        ),
    )

    private fun cpuKernelSection(): SystemInfoSection {
        val uname = runCatching { Os.uname() }.getOrNull()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuModel = readFirstMatchingLine("/proc/cpuinfo", listOf("Hardware", "model name", "Processor"))
        return SystemInfoSection(
            "CPU & kernel",
            listOf(
                SystemInfoRow("Logical cores", cores.toString()),
                SystemInfoRow("CPU", cpuModel ?: Build.HARDWARE.safe("Unavailable")),
                SystemInfoRow("Hardware", Build.HARDWARE.safe()),
                SystemInfoRow("Kernel", uname?.release?.safe() ?: System.getProperty("os.version").safe()),
                SystemInfoRow("Kernel machine", uname?.machine?.safe() ?: "Unavailable"),
                SystemInfoRow("Kernel build", readBoundedText("/proc/version", 512) ?: "Unavailable"),
            ),
        )
    }

    private fun memoryStorageSection(): SystemInfoSection {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val totalStorage = stat.totalBytes.coerceAtLeast(0)
        val freeStorage = stat.availableBytes.coerceAtLeast(0)
        return SystemInfoSection(
            "Memory & storage",
            listOf(
                SystemInfoRow("RAM total", ByteSizeFormatter.format(memory.totalMem)),
                SystemInfoRow("RAM available", ByteSizeFormatter.format(memory.availMem)),
                SystemInfoRow("Low-memory state", if (memory.lowMemory) "Yes" else "No"),
                SystemInfoRow("Internal storage", ByteSizeFormatter.format(totalStorage)),
                SystemInfoRow("Internal free", ByteSizeFormatter.format(freeStorage)),
                SystemInfoRow("Low-RAM device", if (am.isLowRamDevice) "Yes" else "No"),
            ),
        )
    }

    private fun displaySection(): SystemInfoSection {
        val metrics = context.resources.displayMetrics
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else null
        val width = bounds?.width() ?: metrics.widthPixels
        val height = bounds?.height() ?: metrics.heightPixels
        val density = metrics.densityDpi
        val refreshRate = if (Build.VERSION.SDK_INT >= 30) context.display.refreshRate else @Suppress("DEPRECATION") wm.defaultDisplay?.refreshRate
        val refresh = refreshRate?.let { String.format(java.util.Locale.US, "%.1f Hz", it) } ?: "Unavailable"
        return SystemInfoSection(
            "Display",
            listOf(
                SystemInfoRow("Resolution", "$width × $height"),
                SystemInfoRow("Density", "$density dpi (${String.format(java.util.Locale.US, "%.2f", metrics.density)}×)"),
                SystemInfoRow("Refresh rate", refresh),
                SystemInfoRow("Font scale", String.format(java.util.Locale.US, "%.2f×", context.resources.configuration.fontScale)),
            ),
        )
    }

    private fun batterySection(diagnostics: MutableList<String>): SystemInfoSection {
        val battery = runCatching { batteryReader.read() }.getOrElse {
            diagnostics += "Battery telemetry is unavailable on this device."
            null
        }
        return SystemInfoSection(
            "Battery",
            if (battery == null) listOf(SystemInfoRow("Status", "Unavailable")) else listOf(
                SystemInfoRow("Level", "${battery.levelPercent}%"),
                SystemInfoRow("Health", battery.health.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")),
                SystemInfoRow("Temperature", battery.temperatureCelsius?.let { String.format(java.util.Locale.US, "%.1f °C", it) } ?: "Unavailable"),
                SystemInfoRow("Voltage", battery.voltageMillivolts?.let { "$it mV" } ?: "Unavailable"),
                SystemInfoRow("Technology", battery.technology ?: "Unavailable"),
                SystemInfoRow("Cycle count", battery.cycleCount?.toString() ?: "Unavailable"),
            ),
        )
    }

    private fun sensorSection(): SystemInfoSection {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensors = runCatching { manager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val byType = sensors.groupBy { it.type }
        val summary = listOf(
            Sensor.TYPE_ACCELEROMETER to "Accelerometer",
            Sensor.TYPE_GYROSCOPE to "Gyroscope",
            Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
            Sensor.TYPE_LIGHT to "Light",
            Sensor.TYPE_PROXIMITY to "Proximity",
            Sensor.TYPE_PRESSURE to "Barometer",
            Sensor.TYPE_STEP_COUNTER to "Step counter",
        ).joinToString(" • ") { (type, name) -> "$name: ${if (byType[type].isNullOrEmpty()) "No" else "Yes"}" }
        return SystemInfoSection(
            "Sensors",
            listOf(
                SystemInfoRow("Total exposed", sensors.size.toString()),
                SystemInfoRow("Common sensors", summary),
                SystemInfoRow("Vendors", sensors.mapNotNull { it.vendor?.takeIf(String::isNotBlank) }.distinct().take(6).joinToString().ifBlank { "Unavailable" }),
            ),
        )
    }

    private fun cameraSection(diagnostics: MutableList<String>): SystemInfoSection {
        val manager = context.getSystemService(CameraManager::class.java)
        return try {
            val ids = manager.cameraIdList.take(16)
            val details = ids.map { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars[CameraCharacteristics.LENS_FACING]) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Unknown"
                }
                val level = when (chars[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
                    else -> "Unknown"
                }
                "$id: $facing / $level"
            }
            SystemInfoSection("Cameras", listOf(SystemInfoRow("Count", ids.size.toString()), SystemInfoRow("Exposed devices", details.joinToString("; ").ifBlank { "None" })))
        } catch (security: SecurityException) {
            diagnostics += "Some camera characteristics are hidden until Android grants camera access."
            SystemInfoSection("Cameras", listOf(SystemInfoRow("Status", "Restricted by Android")))
        } catch (_: Throwable) {
            SystemInfoSection("Cameras", listOf(SystemInfoRow("Status", "Unavailable")))
        }
    }

    private fun drmSection(diagnostics: MutableList<String>): SystemInfoSection {
        val widevine = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        if (!MediaDrm.isCryptoSchemeSupported(widevine)) {
            return SystemInfoSection("DRM", listOf(SystemInfoRow("Widevine", "Not reported as supported")))
        }
        var drm: MediaDrm? = null
        var session: ByteArray? = null
        return try {
            drm = MediaDrm(widevine)
            val vendor = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VENDOR) }.getOrNull() ?: "Unavailable"
            val version = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VERSION) }.getOrNull() ?: "Unavailable"
            val maxLevel = if (Build.VERSION.SDK_INT >= 28) {
                session = runCatching { drm.openSession() }.getOrNull()
                session?.let { securityLevelName(drm.getSecurityLevel(it)) } ?: "Supported; session unavailable"
            } else "Supported (security level API unavailable)"
            SystemInfoSection("DRM", listOf(SystemInfoRow("Widevine", "Supported"), SystemInfoRow("Security level", maxLevel), SystemInfoRow("Vendor", vendor), SystemInfoRow("Plugin version", version)))
        } catch (_: Throwable) {
            diagnostics += "Widevine is supported but detailed DRM information could not be opened; provisioning/resources may be required."
            SystemInfoSection("DRM", listOf(SystemInfoRow("Widevine", "Supported; details unavailable")))
        } finally {
            val activeDrm = drm
            val activeSession = session
            if (activeDrm != null && activeSession != null) {
                runCatching { activeDrm.closeSession(activeSession) }
            }
            if (activeDrm != null) {
                if (Build.VERSION.SDK_INT >= 28) runCatching { activeDrm.close() } else @Suppress("DEPRECATION") runCatching { activeDrm.release() }
            }
        }
    }

    private suspend fun securitySection(): SystemInfoSection {
        val caps = runCatching { capabilityManager.allStatuses() }.getOrNull()
        val root = caps?.firstOrNull { it.capability.name == "RootAccess" }
        return SystemInfoSection(
            "Security & boot",
            listOf(
                SystemInfoRow("Bootloader", Build.BOOTLOADER.safe()),
                SystemInfoRow("Build tags", Build.TAGS.safe()),
                SystemInfoRow("Build type", Build.TYPE.safe()),
                SystemInfoRow("Root potential", root?.state?.name ?: "Unknown"),
                SystemInfoRow("Verified-boot state", readSystemProperty("ro.boot.verifiedbootstate") ?: "Unavailable to app"),
                SystemInfoRow("Boot state", readSystemProperty("ro.boot.vbmeta.device_state") ?: "Unavailable to app"),
            ),
        )
    }

    private fun securityLevelName(level: Int): String = when (level) {
        MediaDrm.SECURITY_LEVEL_SW_SECURE_CRYPTO -> "Software secure crypto"
        MediaDrm.SECURITY_LEVEL_SW_SECURE_DECODE -> "Software secure decode"
        MediaDrm.SECURITY_LEVEL_HW_SECURE_CRYPTO -> "Hardware secure crypto"
        MediaDrm.SECURITY_LEVEL_HW_SECURE_DECODE -> "Hardware secure decode"
        MediaDrm.SECURITY_LEVEL_HW_SECURE_ALL -> "Hardware secure all"
        else -> "Unknown"
    }

    private fun readFirstMatchingLine(path: String, keys: List<String>): String? = runCatching {
        File(path).useLines { lines ->
            lines.take(256).firstNotNullOfOrNull { line ->
                val pair = line.split(':', limit = 2)
                if (pair.size == 2 && keys.any { pair[0].trim().equals(it, ignoreCase = true) }) pair[1].trim().take(160) else null
            }
        }
    }.getOrNull()

    private fun readBoundedText(path: String, maxChars: Int): String? = runCatching {
        File(path).bufferedReader().use { it.readLine()?.take(maxChars)?.trim()?.takeIf(String::isNotBlank) }
    }.getOrNull()

    private fun readSystemProperty(key: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readLine()?.trim()?.take(80) }?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun String?.safe(fallback: String = "Unknown"): String = this?.trim()?.takeIf { it.isNotEmpty() }?.take(512) ?: fallback
}
