package com.apextuner.feature.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apextuner.core.time.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@Singleton
class NotificationHistoryScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<NotificationHistoryCleanupWorker>(
            24,
            TimeUnit.HOURS,
            6,
            TimeUnit.HOURS,
        )
            .addTag(TAG_NOTIFICATION_HISTORY)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NOTIFICATION_HISTORY_RETENTION,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val TAG_NOTIFICATION_HISTORY = "apextuner.notification_history"
        const val WORK_NOTIFICATION_HISTORY_RETENTION = "apextuner.notification_history_retention"
    }
}

@HiltWorker
class NotificationHistoryCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: NotificationHistoryRepository,
    private val preferences: NotificationHistoryPreferences,
    private val timeProvider: TimeProvider,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            val settings = preferences.settings.first()
            repository.prune(timeProvider.nowEpochMillis(), settings.retentionDays)
            repository.enforceHardLimit()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
}
