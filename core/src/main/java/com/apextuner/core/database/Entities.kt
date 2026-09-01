package com.apextuner.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val scanType: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val itemsScanned: Long,
    val bytesEligible: Long,
    val status: String,
)

@Entity(tableName = "optimization_history")
data class OptimizationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,
    val scope: String,
    val createdAtEpochMillis: Long,
    val outcome: String,
    val bytesChanged: Long,
    val reversibleUntilEpochMillis: Long?,
)

@Entity(
    tableName = "notification_history",
    indices = [
        Index(value = ["postedAtEpochMillis"]),
        Index(value = ["packageName"]),
    ],
)
data class NotificationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtEpochMillis: Long,
)


@Entity(tableName = "battery_health_snapshots")
data class BatteryHealthSnapshotEntity(
    @PrimaryKey val epochDay: Long,
    val capturedAtEpochMillis: Long,
    val cycleCount: Int?,
    val estimatedFullChargeCapacityMicroampHours: Long?,
    val sourceLevelPercent: Int?,
)

@Entity(
    tableName = "device_health_samples",
    indices = [Index(value = ["capturedAtEpochMillis"])],
)
data class DeviceHealthSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAtEpochMillis: Long,
    val cpuUsagePercent: Double?,
    val memoryUsedPercent: Double,
    val internalStorageAvailableBytes: Long,
    val internalStorageTotalBytes: Long,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Double?,
    val batteryCurrentMicroamps: Long?,
    val batteryCharging: Boolean,
    val thermalStatus: String,
    val networkMetered: Boolean,
    val networkValidated: Boolean,
    val totalRxBytes: Long?,
    val totalTxBytes: Long?,
)

@Entity(
    tableName = "charging_sessions",
    indices = [Index(value = ["startedAtEpochMillis"]), Index(value = ["endedAtEpochMillis"])],
)
data class ChargingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val startLevelPercent: Int?,
    val endLevelPercent: Int?,
    val startChargeCounterMicroampHours: Long?,
    val endChargeCounterMicroampHours: Long?,
    val startCycleCount: Int?,
    val endCycleCount: Int?,
    val maxTemperatureCelsius: Double?,
    val temperatureSumCelsius: Double,
    val temperatureSampleCount: Int,
    val maxAbsCurrentMicroamps: Long?,
    val sampleCount: Int,
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val conditionType: String,
    val thresholdValue: Double?,
    val actionType: String,
    val actionArgument: String?,
    val cooldownMillis: Long,
    val dryRun: Boolean,
    val lastTriggeredAtEpochMillis: Long?,
)

@Entity(
    tableName = "automation_events",
    indices = [Index(value = ["createdAtEpochMillis"]), Index(value = ["ruleId"])],
)
data class AutomationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val ruleName: String,
    val createdAtEpochMillis: Long,
    val outcome: String,
    val detail: String,
)

@Entity(
    tableName = "network_quality_runs",
    indices = [Index(value = ["capturedAtEpochMillis"])],
)
data class NetworkQualityRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAtEpochMillis: Long,
    val host: String,
    val port: Int,
    val attempts: Int,
    val successes: Int,
    val minLatencyMillis: Long?,
    val medianLatencyMillis: Long?,
    val averageLatencyMillis: Double?,
    val p95LatencyMillis: Long?,
    val jitterMillis: Double?,
    val dnsLatencyMillis: Long?,
    val ipv4Count: Int,
    val ipv6Count: Int,
    val networkMetered: Boolean,
)

@Entity(
    tableName = "game_session_records",
    indices = [Index(value = ["startedAtEpochMillis"]), Index(value = ["packageName"])],
)
data class GameSessionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val startBatteryLevelPercent: Int?,
    val endBatteryLevelPercent: Int?,
    val peakBatteryTemperatureCelsius: Double?,
    val startRxBytes: Long?,
    val endRxBytes: Long?,
    val startTxBytes: Long?,
    val endTxBytes: Long?,
    val gamingProfileUsed: Boolean,
    val dndUsed: Boolean,
)
