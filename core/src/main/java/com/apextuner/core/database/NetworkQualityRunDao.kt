package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NetworkQualityRunDao {
    @Insert
    suspend fun insert(run: NetworkQualityRunEntity): Long

    @Query("SELECT * FROM network_quality_runs ORDER BY capturedAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NetworkQualityRunEntity>

    @Query("DELETE FROM network_quality_runs WHERE capturedAtEpochMillis < :minimumEpochMillis")
    suspend fun deleteBefore(minimumEpochMillis: Long): Int

    @Query("DELETE FROM network_quality_runs WHERE id NOT IN (SELECT id FROM network_quality_runs ORDER BY capturedAtEpochMillis DESC, id DESC LIMIT :keep)")
    suspend fun trimToNewest(keep: Int): Int
}
