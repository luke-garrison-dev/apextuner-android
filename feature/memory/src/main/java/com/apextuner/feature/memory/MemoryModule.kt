package com.apextuner.feature.memory

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {
    @Binds abstract fun bindMemoryRepository(impl: AndroidMemoryRepository): MemoryRepository
}
