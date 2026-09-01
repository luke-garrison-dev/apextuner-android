package com.apextuner.feature.network.firewall

import com.apextuner.feature.network.FirewallRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FirewallRuntime(
    val state: FirewallRuntimeState = FirewallRuntimeState.Stopped,
    val packages: Set<String> = emptySet(),
    val error: String? = null,
)

object FirewallRuntimeRegistry {
    private val _runtime = MutableStateFlow(FirewallRuntime())
    val runtime: StateFlow<FirewallRuntime> = _runtime.asStateFlow()

    fun update(state: FirewallRuntimeState, packages: Set<String> = emptySet(), error: String? = null) {
        _runtime.value = FirewallRuntime(state, packages.toSet(), error?.take(240))
    }
}
