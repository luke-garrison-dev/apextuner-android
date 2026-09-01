package com.apextuner.feature.tools.game

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class GameSessionMonitorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val controller: GameSessionController,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        controller.sampleActiveSession()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        Result.retry()
    }
}
