package com.apextuner.feature.tools.recording

/**
 * Serializes the structural lifecycle of one screen-recording session.
 *
 * MediaCodec draining is intentionally performed outside this monitor, but every transition that
 * can create, publish, stop, or release session resources is performed while holding [locked].
 * This prevents startup and teardown from overlapping even if a future caller invokes stop from a
 * different thread.
 */
internal class RecordingLifecycleGate {
    internal enum class Phase { New, Starting, Running, Stopping, Finished }

    private val monitor = Any()
    private var phase = Phase.New

    internal fun <T> locked(block: Locked.() -> T): T = synchronized(monitor) {
        Locked().block()
    }

    internal fun snapshot(): Phase = synchronized(monitor) { phase }

    internal inner class Locked internal constructor() {
        internal fun transitionToStarting() {
            check(phase == Phase.New) { "Recording session can only be started once." }
            phase = Phase.Starting
        }

        internal fun transitionToRunning() {
            check(phase == Phase.Starting) { "Recording session was not in the starting state." }
            phase = Phase.Running
        }

        internal fun transitionToStopping(): Boolean {
            if (phase == Phase.Stopping || phase == Phase.Finished) return false
            phase = Phase.Stopping
            return true
        }

        internal fun transitionToFinished(): Boolean {
            if (phase == Phase.Finished) return false
            phase = Phase.Finished
            return true
        }

    }
}
