package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DeviceHealthSampleDao {
    @Insert
    suspend fun insert(sample: DeviceHealthSampleEntity): Long

    @Query("SELECT * FROM device_health_samples WHERE capturedAtEpochMillis >= :sinceEpochMillis ORDER BY capturedAtEpochMillis ASC")
    suspend fun since(sinceEpochMillis: Long): List<DeviceHealthSampleEntity>

    @Query("SELECT * FROM device_health_samples ORDER BY capturedAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeviceHealthSampleEntity>

    @Query("DELETE FROM device_health_samples WHERE capturedAtEpochMillis < :minimumEpochMillis")
    suspend fun deleteBefore(minimumEpochMillis: Long): Int
}
