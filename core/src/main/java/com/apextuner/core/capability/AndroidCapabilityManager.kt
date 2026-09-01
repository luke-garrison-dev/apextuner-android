package com.apextuner.core.capability

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import com.apextuner.core.model.CapabilityStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCapabilityManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CapabilityManager {

    override fun status(capability: Capability): CapabilityStatus = when (capability) {
        Capability.UsageAccess -> usageAccessStatus()
        Capability.DrawOverOtherApps -> binaryStatus(
            capability = capability,
            granted = Settings.canDrawOverlays(context),
            detailGranted = "Overlay permission is available.",
            detailMissing = "Overlay permission has not been granted.",
        )
        Capability.WriteSystemSettings -> binaryStatus(
            capability = capability,
            granted = Settings.System.canWrite(context),
            detailGranted = "System settings write access is available.",
            detailMissing = "System settings write access has not been granted.",
        )
        Capability.ScheduleExactAlarms -> exactAlarmStatus()
        Capability.AllFilesAccess -> allFilesStatus()
        Capability.RootAccess -> rootStatus()
    }

    private fun usageAccessStatus(): CapabilityStatus {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return binaryStatus(
            capability = Capability.UsageAccess,
            granted = mode == AppOpsManager.MODE_ALLOWED,
            detailGranted = "Usage access is available for device analytics.",
            detailMissing = "Usage access must be enabled by the user in system settings.",
        )
    }

    private fun exactAlarmStatus(): CapabilityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return CapabilityStatus(
                capability = Capability.ScheduleExactAlarms,
                state = CapabilityState.Granted,
                userActionRequired = false,
                detail = "Exact alarms do not require special access on this Android version.",
            )
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return binaryStatus(
            capability = Capability.ScheduleExactAlarms,
            granted = alarmManager.canScheduleExactAlarms(),
            detailGranted = "Exact alarm access is available.",
            detailMissing = "Exact alarm access is not granted; WorkManager should be preferred unless an exact user-visible schedule is required.",
        )
    }

    private fun allFilesStatus(): CapabilityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CapabilityStatus(
                capability = Capability.AllFilesAccess,
                state = CapabilityState.Unsupported,
                userActionRequired = false,
                detail = "All-files special access does not apply before Android 11; scoped APIs remain preferred.",
            )
        }
        return binaryStatus(
            capability = Capability.AllFilesAccess,
            granted = Environment.isExternalStorageManager(),
            detailGranted = "All-files special access is available.",
            detailMissing = "All-files special access is not granted. ApexTuner will use MediaStore and SAF by default.",
        )
    }

    private fun rootStatus(): CapabilityStatus {
        val knownSuPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/bin/su",
        )
        val detected = knownSuPaths.any { File(it).canExecute() }
        return CapabilityStatus(
            capability = Capability.RootAccess,
            state = if (detected) CapabilityState.Available else CapabilityState.NotGranted,
            userActionRequired = detected,
            detail = if (detected) {
                "A known su binary is present. Root authorization has not been requested or assumed."
            } else {
                "No executable su binary was detected in known locations."
            },
        )
    }

    private fun binaryStatus(
        capability: Capability,
        granted: Boolean,
        detailGranted: String,
        detailMissing: String,
    ): CapabilityStatus = CapabilityStatus(
        capability = capability,
        state = if (granted) CapabilityState.Granted else CapabilityState.NotGranted,
        userActionRequired = !granted,
        detail = if (granted) detailGranted else detailMissing,
    )
}
