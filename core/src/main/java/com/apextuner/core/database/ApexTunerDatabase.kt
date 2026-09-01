package com.apextuner.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScanSessionEntity::class,
        OptimizationHistoryEntity::class,
        NotificationHistoryEntity::class,
        BatteryHealthSnapshotEntity::class,
        DeviceHealthSampleEntity::class,
        ChargingSessionEntity::class,
        AutomationRuleEntity::class,
        AutomationEventEntity::class,
        NetworkQualityRunEntity::class,
        GameSessionRecordEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ApexTunerDatabase : RoomDatabase() {
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun optimizationHistoryDao(): OptimizationHistoryDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun batteryHealthSnapshotDao(): BatteryHealthSnapshotDao
    abstract fun deviceHealthSampleDao(): DeviceHealthSampleDao
    abstract fun chargingSessionDao(): ChargingSessionDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun automationEventDao(): AutomationEventDao
    abstract fun networkQualityRunDao(): NetworkQualityRunDao
    abstract fun gameSessionRecordDao(): GameSessionRecordDao
}
