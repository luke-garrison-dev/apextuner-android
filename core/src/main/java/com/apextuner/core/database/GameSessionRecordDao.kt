package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface GameSessionRecordDao {
    @Insert
    suspend fun insert(record: GameSessionRecordEntity): Long

    @Query("SELECT * FROM game_session_records ORDER BY endedAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<GameSessionRecordEntity>

    @Query("SELECT * FROM game_session_records WHERE packageName = :packageName ORDER BY endedAtEpochMillis DESC LIMIT :limit")
    suspend fun recentForPackage(packageName: String, limit: Int): List<GameSessionRecordEntity>

    @Query("DELETE FROM game_session_records WHERE endedAtEpochMillis < :minimumEpochMillis")
    suspend fun deleteBefore(minimumEpochMillis: Long): Int
}
