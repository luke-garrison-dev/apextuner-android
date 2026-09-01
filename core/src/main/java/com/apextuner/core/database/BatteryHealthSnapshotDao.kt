package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BatteryHealthSnapshotDao {
    @Upsert
    suspend fun upsert(snapshot: BatteryHealthSnapshotEntity)

    @Query("SELECT * FROM battery_health_snapshots ORDER BY epochDay DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BatteryHealthSnapshotEntity>

    @Query("DELETE FROM battery_health_snapshots WHERE epochDay < :minimumEpochDay")
    suspend fun deleteBefore(minimumEpochDay: Long): Int
}
