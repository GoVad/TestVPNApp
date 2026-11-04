package com.haruchka.testvpnapp.core.model

/** Полное состояние VPN-движка: статус подключения, сервер и счётчики трафика. */
data class VpnRuntimeState(
    val connection: VpnConnectionState = VpnConnectionState.Disconnected,
    val server: VpnServerConfig? = null,
    val stats: VpnTrafficStats = VpnTrafficStats(),
) {
    val isBusy: Boolean
        get() = connection is VpnConnectionState.Connecting || connection is VpnConnectionState.Disconnecting

    val isConnected: Boolean
        get() = connection is VpnConnectionState.Connected
}
