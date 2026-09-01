package com.apextuner.feature.contacts

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ContactsModule {
    @Binds
    abstract fun bindContactRepository(impl: AndroidContactRepository): ContactRepository
}
