package com.apextuner.feature.tools.performance

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PerformanceModule {
    @Binds abstract fun bindPerformanceRepository(impl: AndroidPerformanceRepository): PerformanceRepository
}
