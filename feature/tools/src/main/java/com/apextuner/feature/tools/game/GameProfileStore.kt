package com.apextuner.feature.tools.game

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameProfileStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun optionsFor(packageName: String): GameSessionOptions? {
        if (!PACKAGE_PATTERN.matches(packageName)) return null
        if (!preferences.contains(key(packageName, SUFFIX_PRESENT))) return null
        return GameSessionOptions(
            useGamingProfile = preferences.getBoolean(key(packageName, SUFFIX_PROFILE), true),
            silenceInterruptions = preferences.getBoolean(key(packageName, SUFFIX_DND), true),
            thermalWarningCelsius = preferences.getFloat(key(packageName, SUFFIX_THERMAL), DEFAULT_THERMAL_WARNING.toFloat()).toDouble()
                .coerceIn(MIN_THERMAL_WARNING, MAX_THERMAL_WARNING),
        )
    }

    fun save(packageName: String, options: GameSessionOptions) {
        if (!PACKAGE_PATTERN.matches(packageName)) return
        preferences.edit()
            .putBoolean(key(packageName, SUFFIX_PRESENT), true)
            .putBoolean(key(packageName, SUFFIX_PROFILE), options.useGamingProfile)
            .putBoolean(key(packageName, SUFFIX_DND), options.silenceInterruptions)
            .putFloat(key(packageName, SUFFIX_THERMAL), options.thermalWarningCelsius.coerceIn(MIN_THERMAL_WARNING, MAX_THERMAL_WARNING).toFloat())
            .apply()
    }

    private fun key(packageName: String, suffix: String): String = "$packageName::$suffix"

    companion object {
        const val DEFAULT_THERMAL_WARNING = 42.0
        const val MIN_THERMAL_WARNING = 35.0
        const val MAX_THERMAL_WARNING = 50.0
        private const val PREFERENCES_NAME = "apextuner_game_profiles"
        private const val SUFFIX_PRESENT = "present"
        private const val SUFFIX_PROFILE = "gaming_profile"
        private const val SUFFIX_DND = "dnd"
        private const val SUFFIX_THERMAL = "thermal_warning"
        private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    }
}
