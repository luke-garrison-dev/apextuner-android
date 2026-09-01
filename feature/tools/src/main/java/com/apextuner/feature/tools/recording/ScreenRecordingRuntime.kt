package com.apextuner.feature.tools.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenRecordingState { Idle, Starting, Recording, Stopping, Failed }

data class ScreenRecordingSnapshot(
    val state: ScreenRecordingState = ScreenRecordingState.Idle,
    val message: String? = null,
)

object ScreenRecordingRuntime {
    private val mutable = MutableStateFlow(ScreenRecordingSnapshot())
    val state: StateFlow<ScreenRecordingSnapshot> = mutable.asStateFlow()
    fun set(state: ScreenRecordingState, message: String? = null) { mutable.value = ScreenRecordingSnapshot(state, message) }
}
