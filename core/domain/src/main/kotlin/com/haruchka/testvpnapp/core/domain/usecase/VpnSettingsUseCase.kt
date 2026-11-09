package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Сохраняет выбранный режим и параметры SelfMadeGuard-сервера. */
class VpnSettingsUseCase(
    private val settings: VpnSettingsStore,
) {
    suspend fun saveMode(mode: VpnMode): Result<Unit> =
        runCatching { settings.saveMode(mode) }

    suspend fun saveSelfMadeGuard(config: VpnServerConfig): Result<Unit> =
        runCatching { settings.saveSelfMadeGuard(config) }

    suspend fun clearWireGuard(): Result<Unit> =
        runCatching { settings.clearWireGuardConfig() }

    suspend fun clearAuth(): Result<Unit> =
        runCatching { settings.clearAuth() }
}
