package com.apextuner.feature.notifications

import com.apextuner.core.database.NotificationHistoryDao
import com.apextuner.core.database.NotificationHistoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationHistoryRepositoryTest {
    @Test
    fun record_sanitizesAndBoundsBeforeRoomInsert() = runTest {
        val dao = FakeNotificationHistoryDao()
        val repository = RoomNotificationHistoryRepository(
            dao = dao,
            io = UnconfinedTestDispatcher(testScheduler),
        )

        val inserted = repository.record(
            NotificationCapture(
                packageName = " com.example.mail ",
                title = " title\u0000here ",
                text = "x".repeat(NotificationHistoryPolicy.MAX_TEXT_CHARS + 100),
                postedAtEpochMillis = -100L,
            ),
        )

        assertTrue(inserted)
        val row = dao.inserted.single()
        assertEquals("com.example.mail", row.packageName)
        assertEquals("title here", row.title)
        assertEquals(NotificationHistoryPolicy.MAX_TEXT_CHARS, row.text.length)
        assertEquals(0L, row.postedAtEpochMillis)
    }

    @Test
    fun record_rejectsInvalidPackageAndContentlessNotification() = runTest {
        val dao = FakeNotificationHistoryDao()
        val repository = RoomNotificationHistoryRepository(
            dao = dao,
            io = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(
            repository.record(
                NotificationCapture(
                    packageName = "invalid",
                    title = "Title",
                    text = "Body",
                    postedAtEpochMillis = 1L,
                ),
            ),
        )
        assertFalse(
            repository.record(
                NotificationCapture(
                    packageName = "com.example.app",
                    title = "   ",
                    text = "",
                    postedAtEpochMillis = 1L,
                ),
            ),
        )
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun record_reportsRoomConflictAsNotInserted() = runTest {
        val dao = FakeNotificationHistoryDao(insertResult = -1L)
        val repository = RoomNotificationHistoryRepository(
            dao = dao,
            io = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(
            repository.record(
                NotificationCapture(
                    packageName = "com.example.app",
                    title = "Title",
                    text = "Body",
                    postedAtEpochMillis = 10L,
                ),
            ),
        )
    }

    @Test
    fun pruneAndHardLimit_forwardPolicyBoundsToDao() = runTest {
        val dao = FakeNotificationHistoryDao()
        val repository = RoomNotificationHistoryRepository(
            dao = dao,
            io = UnconfinedTestDispatcher(testScheduler),
        )

        repository.prune(nowEpochMillis = 10L * DAY_MILLIS, retentionDays = 3)
        repository.enforceHardLimit()

        assertEquals(7L * DAY_MILLIS, dao.lastCutoff)
        assertEquals(NotificationHistoryPolicy.MAX_HISTORY_ITEMS, dao.lastMaxItems)
    }

    private class FakeNotificationHistoryDao(
        private val insertResult: Long = 1L,
    ) : NotificationHistoryDao {
        val inserted = mutableListOf<NotificationHistoryEntity>()
        var lastCutoff: Long? = null
        var lastMaxItems: Int? = null

        override suspend fun insert(entry: NotificationHistoryEntity): Long {
            inserted += entry
            return insertResult
        }

        override fun observeRecent(limit: Int): Flow<List<NotificationHistoryEntity>> =
            flowOf(emptyList())

        override suspend fun deleteAll(): Int = 0

        override suspend fun deleteForPackage(packageName: String): Int = 0

        override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int {
            lastCutoff = cutoffEpochMillis
            return 0
        }

        override suspend fun trimToNewest(maxItems: Int): Int {
            lastMaxItems = maxItems
            return 0
        }

        override suspend fun count(): Long = inserted.size.toLong()
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
