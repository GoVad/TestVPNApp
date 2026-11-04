package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Сохраняет выбранный режим и параметры GuardWire-сервера. */
class VpnSettingsUseCase(
    private val settings: VpnSettingsStore,
) {
    suspend fun saveMode(mode: VpnMode): Result<Unit> =
        runCatching { settings.saveMode(mode) }

    suspend fun saveGuardWire(config: VpnServerConfig): Result<Unit> =
        runCatching { settings.saveGuardWire(config) }

    suspend fun clearWireGuard(): Result<Unit> =
        runCatching { settings.clearWireGuardConfig() }
}
