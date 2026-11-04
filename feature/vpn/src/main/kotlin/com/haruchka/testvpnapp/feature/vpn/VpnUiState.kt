package com.haruchka.testvpnapp.feature.vpn

import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnRuntimeState

/** Запись в журнале событий на экране. */
data class VpnLogEntry(
    val time: String,
    val message: String,
)

/** Состояние экрана VPN. */
data class VpnUiState(
    val host: String = "",
    val port: String = "",
    val token: String = "",
    val runtime: VpnRuntimeState = VpnRuntimeState(),
    val nowMillis: Long = System.currentTimeMillis(),
    val inputError: String? = null,
    val logs: List<VpnLogEntry> = emptyList(),
) {
    val connection: VpnConnectionState
        get() = runtime.connection

    val isActive: Boolean
        get() = connection is VpnConnectionState.Connecting ||
            connection is VpnConnectionState.Connected ||
            connection is VpnConnectionState.Disconnecting

    val fieldsEnabled: Boolean
        get() = !isActive

    val connectedDurationSeconds: Long
        get() = (connection as? VpnConnectionState.Connected)?.let {
            ((nowMillis - it.sinceEpochMillis) / 1_000L).coerceAtLeast(0L)
        } ?: 0L
}
