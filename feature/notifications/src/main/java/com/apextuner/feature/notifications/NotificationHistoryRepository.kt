package com.apextuner.feature.notifications

import com.apextuner.core.database.NotificationHistoryDao
import com.apextuner.core.database.NotificationHistoryEntity
import com.apextuner.core.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface NotificationHistoryRepository {
    fun observeRecent(limit: Int = NotificationHistoryPolicy.MAX_VISIBLE_ITEMS): Flow<List<NotificationHistoryItem>>
    suspend fun record(capture: NotificationCapture): Boolean
    suspend fun clearAll(): Int
    suspend fun clearPackage(packageName: String): Int
    suspend fun prune(nowEpochMillis: Long, retentionDays: Int): Int
    suspend fun enforceHardLimit(): Int
    suspend fun count(): Long
}

@Singleton
class RoomNotificationHistoryRepository @Inject constructor(
    private val dao: NotificationHistoryDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : NotificationHistoryRepository {
    override fun observeRecent(limit: Int): Flow<List<NotificationHistoryItem>> =
        dao.observeRecent(limit.coerceIn(1, NotificationHistoryPolicy.MAX_VISIBLE_ITEMS))
            .map { rows -> rows.map { it.toModel() } }

    override suspend fun record(capture: NotificationCapture): Boolean = withContext(io) {
        val packageName = NotificationHistoryPolicy.sanitizePackageName(capture.packageName)
            ?: return@withContext false
        val title = NotificationHistoryPolicy.sanitizeTitle(capture.title)
        val text = NotificationHistoryPolicy.sanitizeBody(capture.text)
        if (title.isBlank() && text.isBlank()) return@withContext false
        val timestamp = capture.postedAtEpochMillis.coerceAtLeast(0L)
        dao.insert(
            NotificationHistoryEntity(
                packageName = packageName,
                title = title,
                text = text,
                postedAtEpochMillis = timestamp,
            ),
        ) != INSERT_IGNORED
    }

    override suspend fun clearAll(): Int = withContext(io) {
        dao.deleteAll()
    }

    override suspend fun clearPackage(packageName: String): Int = withContext(io) {
        val sanitized = NotificationHistoryPolicy.sanitizePackageName(packageName) ?: return@withContext 0
        dao.deleteForPackage(sanitized)
    }

    override suspend fun prune(nowEpochMillis: Long, retentionDays: Int): Int = withContext(io) {
        val cutoff = NotificationHistoryPolicy.retentionCutoffEpochMillis(nowEpochMillis, retentionDays)
        dao.deleteOlderThan(cutoff)
    }

    override suspend fun enforceHardLimit(): Int = withContext(io) {
        dao.trimToNewest(NotificationHistoryPolicy.MAX_HISTORY_ITEMS)
    }

    override suspend fun count(): Long = withContext(io) {
        dao.count()
    }

    private fun NotificationHistoryEntity.toModel(): NotificationHistoryItem =
        NotificationHistoryItem(
            id = id,
            packageName = packageName,
            title = title,
            text = text,
            postedAtEpochMillis = postedAtEpochMillis,
        )

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationHistoryRepositoryModule {
    @Binds
    abstract fun bindNotificationHistoryRepository(
        impl: RoomNotificationHistoryRepository,
    ): NotificationHistoryRepository
}
