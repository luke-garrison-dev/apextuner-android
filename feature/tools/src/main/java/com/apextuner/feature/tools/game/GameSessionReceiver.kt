package com.apextuner.feature.tools.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apextuner.core.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GameSessionReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: GameSessionController
    @Inject @field:IoDispatcher lateinit var io: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + io).launch {
            try { controller.stop("notification") } finally { pending.finish() }
        }
    }

    companion object { const val ACTION_STOP = "com.apextuner.action.END_GAME_SESSION" }
}
