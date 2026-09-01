package com.apextuner.feature.network.firewall

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apextuner.feature.network.FirewallProfile
import com.apextuner.feature.network.sanitizeFirewallPackages
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val FIREWALL_STORE_NAME = "apextuner_firewall"
private val Context.firewallDataStore by preferencesDataStore(name = FIREWALL_STORE_NAME)

interface FirewallPreferences {
    val activeProfile: Flow<FirewallProfile>
    val blockedPackages: Flow<Set<String>>
    suspend fun setActiveProfile(profile: FirewallProfile)
    suspend fun setBlockedPackages(packages: Set<String>)
}

@Singleton
class DataStoreFirewallPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FirewallPreferences {
    override val activeProfile: Flow<FirewallProfile> = context.firewallDataStore.data.map { preferences ->
        parseProfile(preferences[ACTIVE_PROFILE])
    }

    override val blockedPackages: Flow<Set<String>> = context.firewallDataStore.data.map { preferences ->
        val profile = parseProfile(preferences[ACTIVE_PROFILE])
        val configured = preferences[profileKey(profile)]
            ?: if (profile == FirewallProfile.HomeWifi) preferences[LEGACY_BLOCKED_PACKAGES].orEmpty() else emptySet()
        sanitizeFirewallPackages(configured, context.packageName)
    }

    override suspend fun setActiveProfile(profile: FirewallProfile) {
        context.firewallDataStore.edit { preferences -> preferences[ACTIVE_PROFILE] = profile.name }
    }

    override suspend fun setBlockedPackages(packages: Set<String>) {
        val sanitized = sanitizeFirewallPackages(packages, context.packageName)
        context.firewallDataStore.edit { preferences ->
            val profile = parseProfile(preferences[ACTIVE_PROFILE])
            val key = profileKey(profile)
            if (sanitized.isEmpty()) preferences.remove(key) else preferences[key] = sanitized
            if (profile == FirewallProfile.HomeWifi) preferences.remove(LEGACY_BLOCKED_PACKAGES)
        }
    }

    private fun parseProfile(value: String?): FirewallProfile = value?.let { stored ->
        FirewallProfile.entries.firstOrNull { it.name == stored }
    } ?: FirewallProfile.HomeWifi

    private fun profileKey(profile: FirewallProfile) = when (profile) {
        FirewallProfile.HomeWifi -> HOME_BLOCKED_PACKAGES
        FirewallProfile.MobileData -> MOBILE_BLOCKED_PACKAGES
        FirewallProfile.PublicWifi -> PUBLIC_BLOCKED_PACKAGES
    }

    private companion object {
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile_v2")
        val HOME_BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_home_v2")
        val MOBILE_BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_mobile_v2")
        val PUBLIC_BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_public_v2")
        val LEGACY_BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages_v1")
    }
}
