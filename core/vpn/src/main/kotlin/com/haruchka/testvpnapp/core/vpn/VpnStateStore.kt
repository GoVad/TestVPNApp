package com.haruchka.testvpnapp.core.vpn

import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Единственный источник состояния VPN-движка в процессе приложения.
 *
 * [GuardWireVpnService] пишет сюда, а `VpnController` и UI читают. Это упрощённая
 * замена bound-сервису и DI-графу: для демо достаточно, но в реальном приложении
 * состояние лучше отдавать через Binder/DI и хранить вне глобального объекта.
 *
 * [companion] `shared` — тот же экземпляр, который создаёт сервис системой.
 */
class VpnStateStore {

    private val _state = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    internal fun markConnecting(server: VpnServerConfig) {
        _state.update {
            VpnRuntimeState(
                connection = VpnConnectionState.Connecting,
                server = server,
            )
        }
    }

    internal fun markConnected(server: VpnServerConfig, sinceEpochMillis: Long) {
        _state.update {
            it.copy(
                connection = VpnConnectionState.Connected(sinceEpochMillis),
                server = server,
            )
        }
    }

    internal fun updateStats(stats: VpnTrafficStats) {
        _state.update { it.copy(stats = stats) }
    }

    internal fun markDisconnecting() {
        _state.update { current ->
            if (current.connection == VpnConnectionState.Disconnected) {
                current
            } else {
                current.copy(
                    connection = VpnConnectionState.Disconnecting,
                    stats = current.stats,
                )
            }
        }
    }

    internal fun markDisconnected() {
        _state.value = VpnRuntimeState()
    }

    internal fun markFailed(reason: String) {
        _state.update {
            it.copy(connection = VpnConnectionState.Failed(reason))
        }
    }

    companion object {
        /** Экземпляр по умолчанию для демо без DI. */
        val shared: VpnStateStore = VpnStateStore()
    }
}
