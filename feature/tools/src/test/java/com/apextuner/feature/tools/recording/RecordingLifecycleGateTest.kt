package com.apextuner.feature.tools.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLifecycleGateTest {
    @Test
    fun stopCannotInterleaveWithStartupTransaction() {
        val gate = RecordingLifecycleGate()
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopCompleted = CountDownLatch(1)

        val starter = thread(start = true) {
            gate.locked {
                transitionToStarting()
                startupEntered.countDown()
                assertTrue(releaseStartup.await(2, TimeUnit.SECONDS))
                transitionToRunning()
            }
        }
        assertTrue(startupEntered.await(2, TimeUnit.SECONDS))

        val stopper = thread(start = true) {
            stopAttempted.countDown()
            gate.locked { transitionToStopping() }
            stopCompleted.countDown()
        }

        assertTrue(stopAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(
            "stop must remain serialized behind startup",
            stopCompleted.await(100, TimeUnit.MILLISECONDS),
        )
        releaseStartup.countDown()
        starter.join(2_000)
        stopper.join(2_000)

        assertFalse(starter.isAlive)
        assertFalse(stopper.isAlive)
        assertTrue(stopCompleted.await(2, TimeUnit.SECONDS))
        assertEquals(RecordingLifecycleGate.Phase.Stopping, gate.snapshot())
    }

    @Test
    fun duplicateStopAndFinishAreIdempotent() {
        val gate = RecordingLifecycleGate()
        gate.locked {
            transitionToStarting()
            transitionToRunning()
            assertTrue(transitionToStopping())
            assertFalse(transitionToStopping())
            assertTrue(transitionToFinished())
            assertFalse(transitionToFinished())
            assertFalse(transitionToStopping())
        }
        assertEquals(RecordingLifecycleGate.Phase.Finished, gate.snapshot())
    }

}
