package com.apextuner.core.repository

import com.apextuner.core.model.DeviceSnapshot
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    suspend fun snapshot(): DeviceSnapshot
    fun observeSnapshots(refreshMillis: Long): Flow<DeviceSnapshot>
}
