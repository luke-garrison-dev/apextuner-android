package com.apextuner.feature.network

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATA_CAP_STORE_NAME = "apextuner_network_caps"
private val Context.dataCapDataStore by preferencesDataStore(name = DATA_CAP_STORE_NAME)

data class DataUsageObservation(
    val periodKey: String,
    val bytes: Long,
)

interface DataUsageCapPreferences {
    val caps: Flow<Map<String, Long>>
    val observations: Flow<Map<String, DataUsageObservation>>
    val usageAccessWarningSent: Flow<Boolean>
    suspend fun setCap(packageName: String, bytes: Long?)
    suspend fun replaceObservations(values: Map<String, DataUsageObservation>)
    suspend fun setUsageAccessWarningSent(sent: Boolean)
}

@Singleton
class DataStoreDataUsageCapPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DataUsageCapPreferences {
    override val caps: Flow<Map<String, Long>> = context.dataCapDataStore.data.map { prefs ->
        DataUsageCapCodec.decodeCaps(prefs[CAPS].orEmpty())
    }

    override val observations: Flow<Map<String, DataUsageObservation>> = context.dataCapDataStore.data.map { prefs ->
        DataUsageCapCodec.decodeObservations(prefs[OBSERVATIONS].orEmpty())
    }
    override val usageAccessWarningSent: Flow<Boolean> = context.dataCapDataStore.data.map { prefs ->
        prefs[USAGE_ACCESS_WARNING_SENT] ?: false
    }

    override suspend fun setCap(packageName: String, bytes: Long?) {
        require(isSafePackageName(packageName)) { "Invalid package name." }
        require(bytes == null || bytes in MIN_CAP_BYTES..MAX_CAP_BYTES) { "Data cap is outside the supported range." }
        context.dataCapDataStore.edit { prefs ->
            val current = DataUsageCapCodec.decodeCaps(prefs[CAPS].orEmpty()).toMutableMap()
            if (bytes == null) current.remove(packageName) else current[packageName] = bytes
            prefs[CAPS] = DataUsageCapCodec.encodeCaps(current)
            if (bytes == null) {
                val observations = DataUsageCapCodec.decodeObservations(prefs[OBSERVATIONS].orEmpty()).toMutableMap()
                observations.remove(packageName)
                prefs[OBSERVATIONS] = DataUsageCapCodec.encodeObservations(observations)
            }
        }
    }

    override suspend fun replaceObservations(values: Map<String, DataUsageObservation>) {
        context.dataCapDataStore.edit { it[OBSERVATIONS] = DataUsageCapCodec.encodeObservations(values) }
    }

    override suspend fun setUsageAccessWarningSent(sent: Boolean) {
        context.dataCapDataStore.edit { it[USAGE_ACCESS_WARNING_SENT] = sent }
    }

    companion object {
        const val MIN_CAP_BYTES = 1L * 1024L * 1024L
        const val MAX_CAP_BYTES = 10L * 1024L * 1024L * 1024L * 1024L
        val CAPS = stringSetPreferencesKey("monthly_caps_v1")
        val OBSERVATIONS = stringSetPreferencesKey("monthly_observations_v1")
        val USAGE_ACCESS_WARNING_SENT = booleanPreferencesKey("usage_access_warning_sent_v1")
    }
}

object DataUsageCapCodec {
    fun encodeCaps(values: Map<String, Long>): Set<String> = values.asSequence()
        .filter { (pkg, bytes) -> isSafePackageName(pkg) && bytes > 0L }
        .map { (pkg, bytes) -> "$pkg|$bytes" }
        .toSet()

    fun decodeCaps(values: Set<String>): Map<String, Long> = values.mapNotNull { encoded ->
        val parts = encoded.split('|')
        if (parts.size != 2) return@mapNotNull null
        val pkg = parts[0]
        val bytes = parts[1].toLongOrNull() ?: return@mapNotNull null
        if (!isSafePackageName(pkg) || bytes <= 0L) null else pkg to bytes
    }.toMap()

    fun encodeObservations(values: Map<String, DataUsageObservation>): Set<String> = values.asSequence()
        .filter { (pkg, observation) ->
            isSafePackageName(pkg) && observation.bytes >= 0L && PERIOD_REGEX.matches(observation.periodKey)
        }
        .map { (pkg, observation) -> "$pkg|${observation.periodKey}|${observation.bytes}" }
        .toSet()

    fun decodeObservations(values: Set<String>): Map<String, DataUsageObservation> = values.mapNotNull { encoded ->
        val parts = encoded.split('|')
        if (parts.size != 3) return@mapNotNull null
        val bytes = parts[2].toLongOrNull() ?: return@mapNotNull null
        if (!isSafePackageName(parts[0]) || !PERIOD_REGEX.matches(parts[1]) || bytes < 0L) null
        else parts[0] to DataUsageObservation(parts[1], bytes)
    }.toMap()

    private val PERIOD_REGEX = Regex("\\d{4}-\\d{2}")
}
