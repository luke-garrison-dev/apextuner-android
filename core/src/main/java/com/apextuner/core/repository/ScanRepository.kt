package com.apextuner.core.repository

import com.apextuner.core.database.ScanSessionDao
import com.apextuner.core.database.ScanSessionEntity
import com.apextuner.core.model.ScanSession
import com.apextuner.core.model.ScanStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ScanRepository {
    suspend fun save(session: ScanSession)
    suspend fun get(id: String): ScanSession?
    fun observeRecent(limit: Int = 20): Flow<List<ScanSession>>
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}

@Singleton
class RoomScanRepository @Inject constructor(
    private val dao: ScanSessionDao,
) : ScanRepository {
    override suspend fun save(session: ScanSession) = dao.upsert(session.toEntity())

    override suspend fun get(id: String): ScanSession? = dao.getById(id)?.toModel()

    override fun observeRecent(limit: Int): Flow<List<ScanSession>> {
        require(limit in 1..200) { "Recent scan limit must be between 1 and 200." }
        return dao.observeRecent(limit).map { entities -> entities.map { entity -> entity.toModel() } }
    }

    override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int = dao.deleteOlderThan(cutoffEpochMillis)

    private fun ScanSession.toEntity() = ScanSessionEntity(
        id = id,
        scanType = scanType,
        startedAtEpochMillis = startedAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        itemsScanned = itemsScanned,
        bytesEligible = bytesEligible,
        status = status.name,
    )

    private fun ScanSessionEntity.toModel() = ScanSession(
        id = id,
        scanType = scanType,
        startedAtEpochMillis = startedAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        itemsScanned = itemsScanned,
        bytesEligible = bytesEligible,
        status = ScanStatus.entries.firstOrNull { it.name == status } ?: ScanStatus.Failed,
    )
}
