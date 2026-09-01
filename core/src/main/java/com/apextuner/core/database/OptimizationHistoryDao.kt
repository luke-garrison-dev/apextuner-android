package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OptimizationHistoryDao {
    @Insert
    suspend fun insert(record: OptimizationHistoryEntity): Long

    @Query("SELECT * FROM optimization_history ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OptimizationHistoryEntity>>

    @Query("SELECT * FROM optimization_history ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OptimizationHistoryEntity>

    @Query("DELETE FROM optimization_history WHERE createdAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}
