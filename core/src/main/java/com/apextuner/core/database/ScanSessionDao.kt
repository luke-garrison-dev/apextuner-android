package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScanSessionEntity>>

    @Query("DELETE FROM scan_sessions WHERE startedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}
