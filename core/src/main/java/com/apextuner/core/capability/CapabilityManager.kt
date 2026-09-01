package com.apextuner.core.capability

import com.apextuner.core.model.Capability
import com.apextuner.core.model.CapabilityStatus

interface CapabilityManager {
    fun status(capability: Capability): CapabilityStatus
    fun allStatuses(): List<CapabilityStatus> = Capability.entries.map(::status)
}
