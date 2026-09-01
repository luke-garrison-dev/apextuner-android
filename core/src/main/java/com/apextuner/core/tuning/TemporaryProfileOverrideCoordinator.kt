package com.apextuner.core.tuning

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates short-lived profile overrides that intentionally sit on top of another ApexTuner
 * profile (currently Game Booster). Persistent ownership lets background automation distinguish
 * a temporary override from a newer manual profile selection even if ApexTuner's process is
 * recreated while the external app is still open.
 *
 * This class never changes Android settings itself. It only prevents independent ApexTuner
 * features from restoring or replacing a profile while a temporary owner is active.
 */
@Singleton
class TemporaryProfileOverrideCoordinator @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _activeOwner = MutableStateFlow<String?>(null)
    val activeOwnerFlow: StateFlow<String?> = _activeOwner.asStateFlow()

    init {
        _activeOwner.value = readActiveOwner(System.currentTimeMillis())
    }

    fun begin(owner: String, expiresAtEpochMillis: Long) {
        val normalizedOwner = owner.trim().take(MAX_OWNER_LENGTH)
        if (normalizedOwner.isEmpty()) return
        val safeExpiry = expiresAtEpochMillis.coerceAtLeast(System.currentTimeMillis() + MIN_LEASE_MILLIS)
        synchronized(lock) {
            preferences.edit()
                .putString(KEY_OWNER, normalizedOwner)
                .putLong(KEY_EXPIRES_AT, safeExpiry)
                .apply()
            _activeOwner.value = normalizedOwner
        }
    }

    fun activeOwner(nowEpochMillis: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        val owner = readActiveOwner(nowEpochMillis)
        if (_activeOwner.value != owner) _activeOwner.value = owner
        owner
    }

    fun isActive(nowEpochMillis: Long = System.currentTimeMillis()): Boolean = activeOwner(nowEpochMillis) != null

    fun end(owner: String) {
        synchronized(lock) {
            if (preferences.getString(KEY_OWNER, null) == owner) {
                clearLocked()
                _activeOwner.value = null
            }
        }
    }

    private fun readActiveOwner(nowEpochMillis: Long): String? {
        val owner = preferences.getString(KEY_OWNER, null)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= nowEpochMillis) {
            clearLocked()
            return null
        }
        return owner
    }

    private fun clearLocked() {
        preferences.edit().remove(KEY_OWNER).remove(KEY_EXPIRES_AT).apply()
    }

    companion object {
        const val OWNER_GAME_SESSION = "game_session"
        private const val PREFERENCES_NAME = "apextuner_profile_override"
        private const val KEY_OWNER = "owner"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val MAX_OWNER_LENGTH = 64
        private const val MIN_LEASE_MILLIS = 60_000L
    }
}
