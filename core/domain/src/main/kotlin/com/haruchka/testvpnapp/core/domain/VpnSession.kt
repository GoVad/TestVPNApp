package com.haruchka.testvpnapp.core.domain

import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.flow.StateFlow

/** Контракт VPN-сессии для UI. Разрешения запрашивает платформенный слой. */
interface VpnSession {
    val state: StateFlow<VpnRuntimeState>

    fun connect(config: VpnServerConfig)

    fun connectWireGuard(configText: String)

    fun disconnect()
}
