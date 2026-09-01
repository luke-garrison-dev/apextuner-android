package com.apextuner.core.system

import com.apextuner.core.model.DeviceSnapshot

interface DeviceTelemetryDataSource {
    suspend fun readSnapshot(): DeviceSnapshot
}
