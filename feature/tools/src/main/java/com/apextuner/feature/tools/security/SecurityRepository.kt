package com.apextuner.feature.tools.security

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class SecurityRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityManager: CapabilityManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun snapshot(): SecuritySnapshot = withContext(ioDispatcher) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val rootStatus = capabilityManager.status(Capability.RootAccess)
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else false
        val patchLevel = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }
        val patchAgeDays = patchLevel?.let(::securityPatchAgeDays)
        val advancedProtectionSupported = Build.VERSION.SDK_INT >= 36
        val advancedProtectionEnabled = if (advancedProtectionSupported) {
            runCatching {
                context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled
            }.getOrNull()
        } else null
        SecuritySnapshot(
            secureScreenLock = runCatching { keyguard.isDeviceSecure }.getOrDefault(false),
            deviceLockedNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) runCatching { keyguard.isDeviceLocked }.getOrDefault(false) else false,
            rootBinaryPotentiallyPresent = rootStatus.state == CapabilityState.Available,
            appCanInstallUnknownPackages = canInstall,
            securityPatchLevel = patchLevel,
            securityPatchAgeDays = patchAgeDays,
            advancedProtectionSupported = advancedProtectionSupported,
            advancedProtectionEnabled = advancedProtectionEnabled,
            diagnostics = buildList {
                add("Android does not expose a trustworthy third-party API for reading the global Developer Options/ADB state; ApexTuner deliberately does not fabricate that result.")
                add("Modern Android encryption/verified-boot posture is intentionally left to system security surfaces unless ApexTuner has an authoritative public API for the specific signal.")
                if (advancedProtectionSupported && advancedProtectionEnabled == null) add("Android Advanced Protection status was unavailable even though this OS version exposes the public API.")
                if (patchLevel == null) add("This device did not expose a parseable Android security patch level through Build.VERSION.SECURITY_PATCH.")
                if (rootStatus.state == CapabilityState.Available) add("A known su binary is executable. This is only a potential root signal; authorization and device integrity are separate questions.")
            },
        )
    }

    private fun securityPatchAgeDays(value: String): Long? = try {
        ChronoUnit.DAYS.between(LocalDate.parse(value), LocalDate.now()).coerceAtLeast(0L)
    } catch (_: DateTimeParseException) {
        null
    }

    fun clearClipboard(): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                @Suppress("DEPRECATION")
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            true
        }.getOrDefault(false)
    }
}
