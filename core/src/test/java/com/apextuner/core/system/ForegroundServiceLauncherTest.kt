package com.apextuner.core.system

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceLauncherTest {
    @Test
    fun successfulStartIsReported() {
        assertEquals(
            ForegroundServiceLaunchResult.Started,
            ForegroundServiceLauncher.attempt { Unit },
        )
    }

    @Test
    fun illegalStateIsRecoverablePlatformRestriction() {
        assertEquals(
            ForegroundServiceLaunchResult.PlatformRestricted,
            ForegroundServiceLauncher.attempt { throw IllegalStateException("blocked") },
        )
    }

    @Test
    fun securityExceptionIsRecoverableSecurityRejection() {
        assertEquals(
            ForegroundServiceLaunchResult.SecurityRejected,
            ForegroundServiceLauncher.attempt { throw SecurityException("blocked") },
        )
    }

    @Test
    fun otherRuntimeFailureIsRecoverable() {
        assertEquals(
            ForegroundServiceLaunchResult.Failed,
            ForegroundServiceLauncher.attempt { throw RuntimeException("failed") },
        )
    }

    @Test(expected = AssertionError::class)
    fun seriousErrorsAreNotSwallowed() {
        ForegroundServiceLauncher.attempt { throw AssertionError("fatal") }
    }
}
