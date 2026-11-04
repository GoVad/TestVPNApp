package com.haruchka.testvpnapp.feature.vpn

import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnLogEntry
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnRuntimeState

/** Этап инициализации приложения до показа логина/основного экрана. */
enum class StartupState {
    CHECKING,
    READY,
}

/** Состояние экрана VPN. */
data class VpnUiState(
    val startupState: StartupState = StartupState.CHECKING,
    val isLoading: Boolean = true,
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val authBaseUrl: String = "https://10.0.2.2:8090",
    val isLoggingIn: Boolean = false,
    val authError: String? = null,
    val mode: VpnMode = VpnMode.SIMPLIFIED,
    val host: String = "",
    val port: String = "",
    val token: String = "",
    val hasWireGuardConfig: Boolean = false,
    val isProvisioningWireGuard: Boolean = false,
    val wireGuardEndpoint: String? = null,
    val wireGuardAddress: String? = null,
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
        get() = !isActive && !isLoading

    val connectedDurationSeconds: Long
        get() = (connection as? VpnConnectionState.Connected)?.let {
            ((nowMillis - it.sinceEpochMillis) / 1_000L).coerceAtLeast(0L)
        } ?: 0L

    val endpoint: String?
        get() = when (mode) {
            VpnMode.SIMPLIFIED -> runtime.server?.endpoint
            VpnMode.REAL -> wireGuardEndpoint
        }
}
