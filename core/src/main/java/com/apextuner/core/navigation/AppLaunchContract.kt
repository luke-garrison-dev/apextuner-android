package com.apextuner.core.navigation

/**
 * Shared protocol for one-shot commands that open the main ApexTuner activity.
 *
 * Destinations are deliberately allow-listed before they reach Navigation Compose. This keeps
 * exported launcher intents from becoming a generic route-injection surface and lets feature
 * modules open the screen that can actually resolve a Quick Settings tile prerequisite.
 */
object AppLaunchContract {
    const val EXTRA_DESTINATION = "com.apextuner.extra.DESTINATION"
    const val EXTRA_QUICK_SCAN = "com.apextuner.extra.QUICK_SCAN"
    const val EXTRA_REQUEST_TOKEN = "com.apextuner.extra.REQUEST_TOKEN"

    const val DESTINATION_OPTIMIZE = "optimize"
    const val DESTINATION_NETWORK = "tools/network"
    const val DESTINATION_SETTINGS = "settings"

    private val supportedDestinations = setOf(
        DESTINATION_OPTIMIZE,
        DESTINATION_NETWORK,
        DESTINATION_SETTINGS,
    )

    fun sanitizeDestination(destination: String?): String? =
        destination?.takeIf(supportedDestinations::contains)
}
