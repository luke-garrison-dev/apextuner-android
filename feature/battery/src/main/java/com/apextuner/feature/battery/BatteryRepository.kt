package com.apextuner.feature.battery

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.di.IoDispatcher
import com.apextuner.core.database.BatteryHealthSnapshotDao
import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityState
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.ThermalStatus
import com.apextuner.core.system.BatteryTelemetryReader
import com.apextuner.core.tuning.ProfileApplyResult
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.feature.battery.model.BatteryInsights
import com.apextuner.feature.battery.model.RecentAppActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface BatteryRepository {
    suspend fun readInsights(): BatteryInsights
    suspend fun applyBatteryProfile(): ProfileApplyResult
    suspend fun restoreBalanced(): ProfileApplyResult
}

@Singleton
class AndroidBatteryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val batteryTelemetryReader: BatteryTelemetryReader,
    private val batteryHealthSnapshotDao: BatteryHealthSnapshotDao,
    private val chargingSessionTracker: ChargingSessionTracker,
    private val capabilityManager: CapabilityManager,
    private val tuningController: SafeSystemTuningController,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BatteryRepository {

    override suspend fun readInsights(): BatteryInsights = withContext(ioDispatcher) {
        val battery = batteryTelemetryReader.read()
        chargingSessionTracker.observe(battery)
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = readThermalStatus(power)
        val usageGranted = capabilityManager.status(Capability.UsageAccess).state == CapabilityState.Granted
        val recent = if (usageGranted) queryRecentActivityCached() else emptyList()
        val prediction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { power.batteryDischargePrediction?.toMillis() }.getOrNull()
        } else null
        val profileStatus = tuningController.profileStatus()
        val chargingSessions = chargingSessionTracker.recent()
        val healthRows = batteryHealthSnapshotDao.recent(BATTERY_HISTORY_LIMIT)
        val currentCapacityEstimate = BatteryHealthSnapshotPolicy.estimateFullChargeCapacityMicroampHours(
            battery.chargeCounterMicroampHours,
            battery.levelPercent,
        )
        val healthTrend = if (healthRows.isEmpty() && battery.cycleCount == null && currentCapacityEstimate == null) {
            BatteryHealthTrend.TelemetryUnavailable(daysObserved = 0)
        } else {
            BatteryHealthSnapshotPolicy.toTrend(healthRows)
        }
        BatteryInsights(
            battery = battery,
            thermalStatus = thermal,
            powerSaveMode = power.isPowerSaveMode,
            predictedRemainingMillis = prediction,
            usageAccessGranted = usageGranted,
            apexTunerStandbyBucket = readOwnStandbyBucket(),
            recentActivity = recent,
            activeProfile = profileStatus.profile,
            profileMatchesSystem = profileStatus.matchesManagedSettings,
            hasProfileRestorePoint = profileStatus.hasRestorePoint,
            recommendations = BatteryRecommendationEngine.evaluate(battery, thermal, power.isPowerSaveMode),
            healthTrend = healthTrend,
            chargingSessions = chargingSessions,
            chargingHistory = ChargingHistoryAnalyzer.analyze(chargingSessions),
        )
    }

    override suspend fun applyBatteryProfile(): ProfileApplyResult = tuningController.apply(SystemProfile.Battery)
    override suspend fun restoreBalanced(): ProfileApplyResult = tuningController.restoreBalanced()

    private fun queryRecentActivityCached(): List<RecentAppActivity> {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(usageCacheLock) {
            val cachedAt = usageCacheUptimeMillis
            if (cachedAt != null && now >= cachedAt && now - cachedAt < USAGE_CACHE_MILLIS) {
                return usageCache
            }
        }
        val fresh = queryRecentActivity()
        synchronized(usageCacheLock) {
            usageCache = fresh
            usageCacheUptimeMillis = now
        }
        return fresh
    }

    private fun queryRecentActivity(): List<RecentAppActivity> {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - LOOKBACK_MILLIS
        val stats = runCatching { usage.queryAndAggregateUsageStats(start, end) }.getOrNull().orEmpty()
        val pm = context.packageManager
        return stats.values.asSequence()
            .filter { it.totalTimeInForeground > 0L }
            .sortedByDescending { it.totalTimeInForeground }
            .take(MAX_ACTIVITY_ROWS)
            .map { stat ->
                val label = runCatching {
                    val info = pm.getApplicationInfo(stat.packageName, 0)
                    pm.getApplicationLabel(info).toString()
                }.getOrDefault(stat.packageName)
                RecentAppActivity(stat.packageName, label, stat.totalTimeInForeground, stat.lastTimeUsed)
            }
            .toList()
    }


    private fun readOwnStandbyBucket(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val bucket = runCatching { usage.appStandbyBucket }.getOrNull() ?: return null
        return when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
            else -> "Bucket $bucket"
        }
    }

    private fun readThermalStatus(power: PowerManager): ThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.Unknown
        return when (power.currentThermalStatus) {
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

    private val usageCacheLock = Any()
    @Volatile private var usageCacheUptimeMillis: Long? = null
    @Volatile private var usageCache: List<RecentAppActivity> = emptyList()

    private companion object {
        const val LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_ACTIVITY_ROWS = 8
        const val USAGE_CACHE_MILLIS = 60_000L
        const val BATTERY_HISTORY_LIMIT = 400
    }
}
