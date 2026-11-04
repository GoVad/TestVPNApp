package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore

/**
 * Завершает сессию: при необходимости останавливает туннель и удаляет
 * сохранённые auth-данные и WireGuard-конфиг.
 */
class LogoutUseCase(
    private val session: VpnSession,
    private val settings: VpnSettingsStore,
) {
    suspend operator fun invoke(disconnect: Boolean): Result<Unit> {
        val failures = mutableListOf<Throwable>()

        if (disconnect) {
            runCatching { session.disconnect() }.onFailure { failures += it }
        }
        runCatching { settings.clearAuth() }.onFailure { failures += it }
        runCatching { settings.clearWireGuardConfig() }.onFailure { failures += it }

        return failures.firstOrNull()
            ?.let { Result.failure(it) }
            ?: Result.success(Unit)
    }
}
