package com.apextuner.core.repository

import com.apextuner.core.model.DeviceSnapshot
import com.apextuner.core.system.DeviceTelemetryDataSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class DefaultDeviceRepository @Inject constructor(
    private val dataSource: DeviceTelemetryDataSource,
) : DeviceRepository {

    override suspend fun snapshot(): DeviceSnapshot = dataSource.readSnapshot()

    override fun observeSnapshots(refreshMillis: Long): Flow<DeviceSnapshot> {
        require(refreshMillis in 1_000L..60_000L) { "Refresh interval must be between 1 and 60 seconds." }
        return flow {
            while (true) {
                emit(dataSource.readSnapshot())
                delay(refreshMillis)
            }
        }
    }
}
