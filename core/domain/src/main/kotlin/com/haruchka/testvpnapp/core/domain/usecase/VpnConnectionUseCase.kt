package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Управляет жизненным циклом VPN-сессии: подключение в обоих режимах,
 * отключение и наблюдение за состоянием.
 */
class VpnConnectionUseCase(
    private val session: VpnSession,
) {
    fun observeState(): Flow<VpnRuntimeState> = session.state

    fun connectSelfMadeGuard(config: VpnServerConfig) = session.connect(config)

    fun connectWireGuard(configText: String) = session.connectWireGuard(configText)

    fun disconnect() = session.disconnect()
}
