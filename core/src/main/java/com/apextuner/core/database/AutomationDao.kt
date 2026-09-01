package com.apextuner.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY name")
    suspend fun all(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1 ORDER BY name")
    suspend fun enabled(): List<AutomationRuleEntity>

    @Upsert
    suspend fun upsert(rule: AutomationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaults(rules: List<AutomationRuleEntity>)

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean): Int

    @Query("UPDATE automation_rules SET dryRun = :dryRun WHERE id = :id")
    suspend fun setDryRun(id: String, dryRun: Boolean): Int

    @Query("UPDATE automation_rules SET thresholdValue = :thresholdValue WHERE id = :id")
    suspend fun setThreshold(id: String, thresholdValue: Double?): Int

    @Query("UPDATE automation_rules SET cooldownMillis = :cooldownMillis WHERE id = :id")
    suspend fun setCooldown(id: String, cooldownMillis: Long): Int

    @Query("UPDATE automation_rules SET lastTriggeredAtEpochMillis = :epochMillis WHERE id = :id")
    suspend fun markTriggered(id: String, epochMillis: Long): Int

    @Query("UPDATE automation_rules SET lastTriggeredAtEpochMillis = NULL WHERE id = :id")
    suspend fun clearLastTriggered(id: String): Int

    @Query("UPDATE automation_rules SET lastTriggeredAtEpochMillis = NULL WHERE actionType = :actionType")
    suspend fun clearLastTriggeredForAction(actionType: String): Int
}

@Dao
interface AutomationEventDao {
    @Insert
    suspend fun insert(event: AutomationEventEntity): Long

    @Query("SELECT * FROM automation_events ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AutomationEventEntity>

    @Query("DELETE FROM automation_events WHERE createdAtEpochMillis < :minimumEpochMillis")
    suspend fun deleteBefore(minimumEpochMillis: Long): Int

    @Query("DELETE FROM automation_events WHERE id NOT IN (SELECT id FROM automation_events ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :keep)")
    suspend fun trimToNewest(keep: Int): Int
}
