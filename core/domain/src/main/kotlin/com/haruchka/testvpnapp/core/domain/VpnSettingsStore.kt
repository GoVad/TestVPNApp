package com.haruchka.testvpnapp.core.domain

import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Контракт хранения настроек и секретов. */
interface VpnSettingsStore {
    suspend fun load(): VpnStoredSettings

    suspend fun saveMode(mode: VpnMode)

    suspend fun saveGuardWire(config: VpnServerConfig)

    suspend fun saveWireGuardConfig(config: String)

    suspend fun clearWireGuardConfig()

    suspend fun saveAuth(
        token: String,
        username: String,
        expiresAtEpochSeconds: Long,
        baseUrl: String,
    )

    suspend fun clearAuth()
}
