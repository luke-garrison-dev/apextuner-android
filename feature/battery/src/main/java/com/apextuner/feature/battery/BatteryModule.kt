package com.apextuner.feature.battery

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BatteryModule {
    @Binds abstract fun bindBatteryRepository(impl: AndroidBatteryRepository): BatteryRepository
}
