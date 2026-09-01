package com.apextuner.feature.network.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkDiagnosticsModule {
    @Binds
    abstract fun bindNetworkDiagnosticsRepository(
        impl: AndroidNetworkDiagnosticsRepository,
    ): NetworkDiagnosticsRepository
}
