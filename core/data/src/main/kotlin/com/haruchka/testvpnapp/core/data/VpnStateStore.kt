package com.haruchka.testvpnapp.core.data

import com.haruchka.testvpnapp.core.domain.VpnStateRepository
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единственный источник состояния VPN-движка в процессе приложения.
 *
 * `SelfMadeGuardVpnService` и `WireGuardController` пишут сюда, а `VpnController`
 * и UI читают.
 */
@Singleton
class VpnStateStore @Inject constructor() : VpnStateRepository {

    private val _state = MutableStateFlow(VpnRuntimeState())
    override val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    override fun markConnecting(mode: VpnMode, server: VpnServerConfig?) {
        _state.update {
            VpnRuntimeState(
                connection = VpnConnectionState.Connecting,
                server = server,
                mode = mode,
            )
        }
    }

    override fun markConnected(
        mode: VpnMode,
        sinceEpochMillis: Long,
        server: VpnServerConfig?,
    ) {
        _state.update {
            it.copy(
                connection = VpnConnectionState.Connected(sinceEpochMillis),
                server = server,
                mode = mode,
            )
        }
    }

    override fun updateStats(stats: VpnTrafficStats) {
        _state.update { it.copy(stats = stats) }
    }

    override fun markDisconnecting() {
        _state.update { current ->
            if (current.connection == VpnConnectionState.Disconnected) {
                current
            } else {
                current.copy(connection = VpnConnectionState.Disconnecting)
            }
        }
    }

    override fun markDisconnected() {
        _state.value = VpnRuntimeState()
    }

    override fun markFailed(reason: String) {
        _state.update {
            it.copy(connection = VpnConnectionState.Failed(reason))
        }
    }

}
