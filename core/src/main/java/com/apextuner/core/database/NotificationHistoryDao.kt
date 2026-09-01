package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: NotificationHistoryEntity): Long

    @Query(
        """
        SELECT * FROM notification_history
        ORDER BY postedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<NotificationHistoryEntity>>

    @Query("DELETE FROM notification_history")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM notification_history WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String): Int

    @Query("DELETE FROM notification_history WHERE postedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int

    @Query(
        """
        DELETE FROM notification_history
        WHERE id IN (
            SELECT id FROM notification_history
            ORDER BY postedAtEpochMillis DESC, id DESC
            LIMIT -1 OFFSET :maxItems
        )
        """,
    )
    suspend fun trimToNewest(maxItems: Int): Int

    @Query("SELECT COUNT(*) FROM notification_history")
    suspend fun count(): Long
}
