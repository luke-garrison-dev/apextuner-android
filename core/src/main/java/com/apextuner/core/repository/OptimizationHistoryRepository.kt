package com.apextuner.core.repository

import com.apextuner.core.database.OptimizationHistoryDao
import com.apextuner.core.database.OptimizationHistoryEntity
import com.apextuner.core.model.OptimizationOutcome
import com.apextuner.core.model.OptimizationRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface OptimizationHistoryRepository {
    suspend fun record(record: OptimizationRecord): Long
    fun observeRecent(limit: Int = 50): Flow<List<OptimizationRecord>>
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}

@Singleton
class RoomOptimizationHistoryRepository @Inject constructor(
    private val dao: OptimizationHistoryDao,
) : OptimizationHistoryRepository {
    override suspend fun record(record: OptimizationRecord): Long = dao.insert(record.toEntity())

    override fun observeRecent(limit: Int): Flow<List<OptimizationRecord>> {
        require(limit in 1..500) { "History limit must be between 1 and 500." }
        return dao.observeRecent(limit).map { entities -> entities.map { entity -> entity.toModel() } }
    }

    override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int = dao.deleteOlderThan(cutoffEpochMillis)

    private fun OptimizationRecord.toEntity() = OptimizationHistoryEntity(
        id = id,
        actionType = actionType,
        scope = scope,
        createdAtEpochMillis = createdAtEpochMillis,
        outcome = outcome.name,
        bytesChanged = bytesChanged,
        reversibleUntilEpochMillis = reversibleUntilEpochMillis,
    )

    private fun OptimizationHistoryEntity.toModel() = OptimizationRecord(
        id = id,
        actionType = actionType,
        scope = scope,
        createdAtEpochMillis = createdAtEpochMillis,
        outcome = OptimizationOutcome.entries.firstOrNull { it.name == outcome } ?: OptimizationOutcome.Failed,
        bytesChanged = bytesChanged,
        reversibleUntilEpochMillis = reversibleUntilEpochMillis,
    )
}
