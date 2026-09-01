package com.apextuner.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLaunchContractTest {
    @Test
    fun sanitizeDestination_acceptsEverySupportedDestination() {
        val destinations = listOf(
            AppLaunchContract.DESTINATION_OPTIMIZE,
            AppLaunchContract.DESTINATION_NETWORK,
            AppLaunchContract.DESTINATION_SETTINGS,
        )

        destinations.forEach { destination ->
            assertEquals(destination, AppLaunchContract.sanitizeDestination(destination))
        }
    }

    @Test
    fun sanitizeDestination_rejectsMissingOrUntrustedRoutes() {
        assertNull(AppLaunchContract.sanitizeDestination(null))
        assertNull(AppLaunchContract.sanitizeDestination(""))
        assertNull(AppLaunchContract.sanitizeDestination("dashboard"))
        assertNull(AppLaunchContract.sanitizeDestination("tools/network/../settings"))
    }
}
