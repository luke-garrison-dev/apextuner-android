package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ChargingSessionDao {
    @Upsert
    suspend fun upsert(session: ChargingSessionEntity)

    @Query("SELECT * FROM charging_sessions WHERE endedAtEpochMillis IS NULL ORDER BY startedAtEpochMillis DESC LIMIT 1")
    suspend fun active(): ChargingSessionEntity?

    @Query("SELECT * FROM charging_sessions WHERE endedAtEpochMillis IS NOT NULL ORDER BY endedAtEpochMillis DESC LIMIT :limit")
    suspend fun recentCompleted(limit: Int): List<ChargingSessionEntity>

    @Query("DELETE FROM charging_sessions WHERE endedAtEpochMillis IS NOT NULL AND endedAtEpochMillis < :minimumEpochMillis")
    suspend fun deleteCompletedBefore(minimumEpochMillis: Long): Int
}
