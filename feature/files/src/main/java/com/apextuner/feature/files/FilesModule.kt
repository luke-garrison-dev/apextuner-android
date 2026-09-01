package com.apextuner.feature.files

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FilesModule {
    @Binds
    abstract fun bindSafFileRepository(impl: AndroidSafFileRepository): SafFileRepository
}
