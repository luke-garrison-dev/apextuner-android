package com.apextuner.core.billing

import com.apextuner.core.model.EntitlementState
import com.apextuner.core.model.EntitlementTier
import com.apextuner.core.model.EntitlementVerification
import com.apextuner.core.security.SecureKeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small encrypted entitlement mirror used only as a bounded offline grace.
 * Google Play remains authoritative whenever it is reachable.
 */
@Singleton
class EncryptedEntitlementCache @Inject constructor(
    private val secureStore: SecureKeyValueStore,
) {
    fun saveVerified(state: EntitlementState) {
        if (!state.isPremium || state.lastCheckedAtEpochMillis == null) {
            clear()
            return
        }
        secureStore.putString(KEY_TIER, state.tier.name)
        secureStore.putString(KEY_CHECKED_AT, state.lastCheckedAtEpochMillis.toString())
    }

    fun loadOfflineGrace(nowEpochMillis: Long): EntitlementState? {
        val tier = secureStore.getString(KEY_TIER)
            ?.let { stored -> EntitlementTier.entries.firstOrNull { it.name == stored } }
            ?.takeIf { it == EntitlementTier.PremiumLifetime }
            ?: return null
        val checkedAt = secureStore.getString(KEY_CHECKED_AT)?.toLongOrNull() ?: return null
        if (checkedAt <= 0L || checkedAt > nowEpochMillis + MAX_CLOCK_SKEW_MILLIS) return null
        val age = nowEpochMillis - checkedAt
        if (age !in 0..LIFETIME_OFFLINE_GRACE_MILLIS) return null
        return EntitlementState(
            tier = tier,
            verification = EntitlementVerification.CachedOfflineGrace,
            lastCheckedAtEpochMillis = checkedAt,
            message = "Using a recent locally encrypted entitlement while Google Play is unavailable.",
        )
    }

    fun clear() {
        secureStore.remove(KEY_TIER)
        secureStore.remove(KEY_CHECKED_AT)
    }

    companion object {
        const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L
        const val LIFETIME_OFFLINE_GRACE_MILLIS = 30L * 24 * 60 * 60 * 1000L
        private const val KEY_TIER = "billing.entitlement.tier.v1"
        private const val KEY_CHECKED_AT = "billing.entitlement.checked_at.v1"
    }
}
