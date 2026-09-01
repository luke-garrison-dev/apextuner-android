package com.apextuner.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val Migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notification_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `postedAtEpochMillis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notification_history_postedAtEpochMillis` " +
                    "ON `notification_history` (`postedAtEpochMillis`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notification_history_packageName` " +
                    "ON `notification_history` (`packageName`)",
            )
        }
    }

    val Migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `battery_health_snapshots` (
                    `epochDay` INTEGER NOT NULL,
                    `capturedAtEpochMillis` INTEGER NOT NULL,
                    `cycleCount` INTEGER,
                    `estimatedFullChargeCapacityMicroampHours` INTEGER,
                    `sourceLevelPercent` INTEGER,
                    PRIMARY KEY(`epochDay`)
                )
                """.trimIndent(),
            )
        }
    }


    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `device_health_samples` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `capturedAtEpochMillis` INTEGER NOT NULL,
                    `cpuUsagePercent` REAL,
                    `memoryUsedPercent` REAL NOT NULL,
                    `internalStorageAvailableBytes` INTEGER NOT NULL,
                    `internalStorageTotalBytes` INTEGER NOT NULL,
                    `batteryLevelPercent` INTEGER,
                    `batteryTemperatureCelsius` REAL,
                    `batteryCurrentMicroamps` INTEGER,
                    `batteryCharging` INTEGER NOT NULL,
                    `thermalStatus` TEXT NOT NULL,
                    `networkMetered` INTEGER NOT NULL,
                    `networkValidated` INTEGER NOT NULL,
                    `totalRxBytes` INTEGER,
                    `totalTxBytes` INTEGER
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_health_samples_capturedAtEpochMillis` ON `device_health_samples` (`capturedAtEpochMillis`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `charging_sessions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `startedAtEpochMillis` INTEGER NOT NULL,
                    `endedAtEpochMillis` INTEGER,
                    `startLevelPercent` INTEGER,
                    `endLevelPercent` INTEGER,
                    `startChargeCounterMicroampHours` INTEGER,
                    `endChargeCounterMicroampHours` INTEGER,
                    `startCycleCount` INTEGER,
                    `endCycleCount` INTEGER,
                    `maxTemperatureCelsius` REAL,
                    `temperatureSumCelsius` REAL NOT NULL,
                    `temperatureSampleCount` INTEGER NOT NULL,
                    `maxAbsCurrentMicroamps` INTEGER,
                    `sampleCount` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_charging_sessions_startedAtEpochMillis` ON `charging_sessions` (`startedAtEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_charging_sessions_endedAtEpochMillis` ON `charging_sessions` (`endedAtEpochMillis`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `automation_rules` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `conditionType` TEXT NOT NULL,
                    `thresholdValue` REAL,
                    `actionType` TEXT NOT NULL,
                    `actionArgument` TEXT,
                    `cooldownMillis` INTEGER NOT NULL,
                    `dryRun` INTEGER NOT NULL,
                    `lastTriggeredAtEpochMillis` INTEGER,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `automation_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `ruleId` TEXT NOT NULL,
                    `ruleName` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `outcome` TEXT NOT NULL,
                    `detail` TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_events_createdAtEpochMillis` ON `automation_events` (`createdAtEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_events_ruleId` ON `automation_events` (`ruleId`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `network_quality_runs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `capturedAtEpochMillis` INTEGER NOT NULL,
                    `host` TEXT NOT NULL,
                    `port` INTEGER NOT NULL,
                    `attempts` INTEGER NOT NULL,
                    `successes` INTEGER NOT NULL,
                    `minLatencyMillis` INTEGER,
                    `medianLatencyMillis` INTEGER,
                    `averageLatencyMillis` REAL,
                    `p95LatencyMillis` INTEGER,
                    `jitterMillis` REAL,
                    `dnsLatencyMillis` INTEGER,
                    `ipv4Count` INTEGER NOT NULL,
                    `ipv6Count` INTEGER NOT NULL,
                    `networkMetered` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_quality_runs_capturedAtEpochMillis` ON `network_quality_runs` (`capturedAtEpochMillis`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `game_session_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appLabel` TEXT NOT NULL,
                    `startedAtEpochMillis` INTEGER NOT NULL,
                    `endedAtEpochMillis` INTEGER NOT NULL,
                    `startBatteryLevelPercent` INTEGER,
                    `endBatteryLevelPercent` INTEGER,
                    `peakBatteryTemperatureCelsius` REAL,
                    `startRxBytes` INTEGER,
                    `endRxBytes` INTEGER,
                    `startTxBytes` INTEGER,
                    `endTxBytes` INTEGER,
                    `gamingProfileUsed` INTEGER NOT NULL,
                    `dndUsed` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_session_records_startedAtEpochMillis` ON `game_session_records` (`startedAtEpochMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_session_records_packageName` ON `game_session_records` (`packageName`)")
        }
    }

}
