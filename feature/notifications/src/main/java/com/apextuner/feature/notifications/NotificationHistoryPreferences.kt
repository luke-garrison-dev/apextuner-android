package com.apextuner.feature.notifications

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val STORE_NAME = "apextuner_notification_history"
private val Context.notificationHistoryDataStore by preferencesDataStore(name = STORE_NAME)

interface NotificationHistoryPreferences {
    val settings: Flow<NotificationHistorySettings>

    suspend fun setEnabled(enabled: Boolean)
    suspend fun setRetentionDays(days: Int)
    suspend fun setMuted(packageName: String, muted: Boolean)
}

@Singleton
class DataStoreNotificationHistoryPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NotificationHistoryPreferences {
    override val settings: Flow<NotificationHistorySettings> =
        context.notificationHistoryDataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .map { values ->
                NotificationHistorySettings(
                    enabled = values[ENABLED] ?: false,
                    retentionDays = NotificationHistoryPolicy.normalizeRetentionDays(
                        values[RETENTION_DAYS] ?: NotificationHistoryPolicy.DEFAULT_RETENTION_DAYS,
                    ),
                    mutedPackages = NotificationHistoryPolicy.sanitizeMutedPackages(
                        values[MUTED_PACKAGES].orEmpty(),
                        context.packageName,
                    ),
                )
            }

    override suspend fun setEnabled(enabled: Boolean) {
        context.notificationHistoryDataStore.edit { values ->
            values[ENABLED] = enabled
        }
    }

    override suspend fun setRetentionDays(days: Int) {
        require(days in NotificationHistoryPolicy.allowedRetentionDays) {
            "Unsupported notification-history retention window: $days days."
        }
        context.notificationHistoryDataStore.edit { values ->
            values[RETENTION_DAYS] = days
        }
    }

    override suspend fun setMuted(packageName: String, muted: Boolean) {
        val sanitized = NotificationHistoryPolicy.sanitizePackageName(packageName)
            ?: throw IllegalArgumentException("Invalid package name.")
        require(sanitized != context.packageName) { "ApexTuner cannot mute itself in notification history." }

        context.notificationHistoryDataStore.edit { values ->
            val next = NotificationHistoryPolicy.sanitizeMutedPackages(
                values[MUTED_PACKAGES].orEmpty(),
                context.packageName,
            ).toMutableSet()
            if (muted) next += sanitized else next -= sanitized
            if (next.isEmpty()) values.remove(MUTED_PACKAGES) else values[MUTED_PACKAGES] = next
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled_v1")
        val RETENTION_DAYS = intPreferencesKey("retention_days_v1")
        val MUTED_PACKAGES = stringSetPreferencesKey("muted_packages_v1")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationHistoryPreferencesModule {
    @Binds
    abstract fun bindNotificationHistoryPreferences(
        impl: DataStoreNotificationHistoryPreferences,
    ): NotificationHistoryPreferences
}
