package com.apextuner.core.di

import android.content.Context
import androidx.room.Room
import com.apextuner.core.capability.AndroidCapabilityManager
import com.apextuner.core.capability.CapabilityManager
import com.apextuner.core.database.ApexTunerDatabase
import com.apextuner.core.database.BatteryHealthSnapshotDao
import com.apextuner.core.database.OptimizationHistoryDao
import com.apextuner.core.database.NotificationHistoryDao
import com.apextuner.core.database.DatabaseMigrations
import com.apextuner.core.database.GameSessionRecordDao
import com.apextuner.core.database.NetworkQualityRunDao
import com.apextuner.core.database.AutomationEventDao
import com.apextuner.core.database.AutomationRuleDao
import com.apextuner.core.database.ChargingSessionDao
import com.apextuner.core.database.DeviceHealthSampleDao
import com.apextuner.core.database.ScanSessionDao
import com.apextuner.core.datastore.DataStorePreferencesRepository
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.repository.DefaultDeviceRepository
import com.apextuner.core.repository.DeviceRepository
import com.apextuner.core.repository.OptimizationHistoryRepository
import com.apextuner.core.repository.RoomOptimizationHistoryRepository
import com.apextuner.core.repository.RoomScanRepository
import com.apextuner.core.repository.ScanRepository
import com.apextuner.core.security.AndroidKeystoreSecureKeyValueStore
import com.apextuner.core.security.SecureKeyValueStore
import com.apextuner.core.system.AndroidDeviceTelemetryDataSource
import com.apextuner.core.system.DeviceTelemetryDataSource
import com.apextuner.core.time.SystemTimeProvider
import com.apextuner.core.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingsModule {
    @Binds abstract fun bindCapabilityManager(impl: AndroidCapabilityManager): CapabilityManager
    @Binds abstract fun bindPreferencesRepository(impl: DataStorePreferencesRepository): PreferencesRepository
    @Binds abstract fun bindSecureStore(impl: AndroidKeystoreSecureKeyValueStore): SecureKeyValueStore
    @Binds abstract fun bindTelemetryDataSource(impl: AndroidDeviceTelemetryDataSource): DeviceTelemetryDataSource
    @Binds abstract fun bindDeviceRepository(impl: DefaultDeviceRepository): DeviceRepository
    @Binds abstract fun bindScanRepository(impl: RoomScanRepository): ScanRepository
    @Binds abstract fun bindHistoryRepository(impl: RoomOptimizationHistoryRepository): OptimizationHistoryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object CoreProvidesModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ApexTunerDatabase =
        Room.databaseBuilder(context, ApexTunerDatabase::class.java, DATABASE_NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(DatabaseMigrations.Migration1To2, DatabaseMigrations.Migration2To3, DatabaseMigrations.Migration3To4)
            .build()

    @Provides fun provideScanSessionDao(database: ApexTunerDatabase): ScanSessionDao = database.scanSessionDao()

    @Provides
    fun provideOptimizationHistoryDao(database: ApexTunerDatabase): OptimizationHistoryDao =
        database.optimizationHistoryDao()

    @Provides
    fun provideNotificationHistoryDao(database: ApexTunerDatabase): NotificationHistoryDao =
        database.notificationHistoryDao()

    @Provides
    fun provideBatteryHealthSnapshotDao(database: ApexTunerDatabase): BatteryHealthSnapshotDao =
        database.batteryHealthSnapshotDao()

    @Provides fun provideDeviceHealthSampleDao(database: ApexTunerDatabase): DeviceHealthSampleDao = database.deviceHealthSampleDao()
    @Provides fun provideChargingSessionDao(database: ApexTunerDatabase): ChargingSessionDao = database.chargingSessionDao()
    @Provides fun provideAutomationRuleDao(database: ApexTunerDatabase): AutomationRuleDao = database.automationRuleDao()
    @Provides fun provideAutomationEventDao(database: ApexTunerDatabase): AutomationEventDao = database.automationEventDao()
    @Provides fun provideNetworkQualityRunDao(database: ApexTunerDatabase): NetworkQualityRunDao = database.networkQualityRunDao()
    @Provides fun provideGameSessionRecordDao(database: ApexTunerDatabase): GameSessionRecordDao = database.gameSessionRecordDao()

    @Provides @Singleton fun provideTimeProvider(): TimeProvider = SystemTimeProvider

    @Provides @IoDispatcher fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    private const val DATABASE_NAME = "apextuner.db"
}
