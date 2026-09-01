package com.apextuner.feature.billing.data

import com.apextuner.core.billing.EntitlementRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingBindingsModule {
    @Binds
    @Singleton
    abstract fun bindEntitlementRepository(impl: GooglePlayEntitlementRepository): EntitlementRepository
}
