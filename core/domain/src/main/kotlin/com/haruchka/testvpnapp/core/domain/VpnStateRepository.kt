package com.haruchka.testvpnapp.core.domain

import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import kotlinx.coroutines.flow.StateFlow

/** Хранилище состояния VPN. Пишут сервисы/контроллеры, читает UI. */
interface VpnStateRepository {
    val state: StateFlow<VpnRuntimeState>

    fun markConnecting(mode: VpnMode, server: VpnServerConfig? = null)

    fun markConnected(
        mode: VpnMode,
        sinceEpochMillis: Long,
        server: VpnServerConfig? = null,
    )

    fun updateStats(stats: VpnTrafficStats)

    fun markDisconnecting()

    fun markDisconnected()

    fun markFailed(reason: String)
}
