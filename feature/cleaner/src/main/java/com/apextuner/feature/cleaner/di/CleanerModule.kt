package com.apextuner.feature.cleaner.di

import com.apextuner.feature.cleaner.data.AndroidCleanerRepository
import com.apextuner.feature.cleaner.data.CleanerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CleanerModule {
    @Binds
    abstract fun bindCleanerRepository(implementation: AndroidCleanerRepository): CleanerRepository
}
