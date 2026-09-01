package com.apextuner.feature.settings.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MonitorRuntimeState { Stopped, Starting, Active, Error }

data class MonitorRuntimeSnapshot(
    val state: MonitorRuntimeState = MonitorRuntimeState.Stopped,
    val lastError: String? = null,
)

object MonitorRuntimeRegistry {
    private val mutable = MutableStateFlow(MonitorRuntimeSnapshot())
    val state: StateFlow<MonitorRuntimeSnapshot> = mutable.asStateFlow()
    fun update(state: MonitorRuntimeState, error: String? = null) {
        mutable.value = MonitorRuntimeSnapshot(state, error?.take(240))
    }
}

internal data class NetworkRateBaseline(
    val uptimeMillis: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

internal data class NetworkRates(val rxBytesPerSecond: Long?, val txBytesPerSecond: Long?)

internal object MonitorRetryPolicy {
    private const val BASE_DELAY_MILLIS = 1_000L
    private const val MAX_DELAY_MILLIS = 30_000L

    fun delayMillis(attempt: Long): Long {
        val shift = attempt.coerceIn(0L, 5L).toInt()
        return (BASE_DELAY_MILLIS shl shift).coerceAtMost(MAX_DELAY_MILLIS)
    }
}

internal object MonitorRateCalculator {
    fun rates(previous: NetworkRateBaseline?, uptimeMillis: Long, rxBytes: Long?, txBytes: Long?): Pair<NetworkRateBaseline?, NetworkRates> {
        if (rxBytes == null || txBytes == null || rxBytes < 0 || txBytes < 0) {
            return null to NetworkRates(null, null)
        }
        val next = NetworkRateBaseline(uptimeMillis, rxBytes, txBytes)
        if (previous == null || uptimeMillis <= previous.uptimeMillis || rxBytes < previous.rxBytes || txBytes < previous.txBytes) {
            return next to NetworkRates(null, null)
        }
        val elapsed = uptimeMillis - previous.uptimeMillis
        val rx = ((rxBytes - previous.rxBytes).toDouble() * 1000.0 / elapsed).toLong().coerceAtLeast(0L)
        val tx = ((txBytes - previous.txBytes).toDouble() * 1000.0 / elapsed).toLong().coerceAtLeast(0L)
        return next to NetworkRates(rx, tx)
    }
}
