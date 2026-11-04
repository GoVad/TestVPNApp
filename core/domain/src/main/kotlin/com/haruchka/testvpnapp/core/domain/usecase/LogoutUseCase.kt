package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore

/**
 * Завершает сессию: отзывает refresh-токен на сервере, при необходимости
 * останавливает туннель и удаляет локальные auth-данные и WireGuard-конфиг.
 */
class LogoutUseCase(
    private val session: VpnSession,
    private val settings: VpnSettingsStore,
    private val authService: AuthService,
) {
    suspend operator fun invoke(disconnect: Boolean): Result<Unit> {
        val failures = mutableListOf<Throwable>()
        val stored = runCatching { settings.load() }.getOrNull()

        if (disconnect) {
            runCatching { session.disconnect() }.onFailure { failures += it }
        }
        val refreshToken = stored?.authRefreshToken
        if (!refreshToken.isNullOrBlank()) {
            // Logout должен работать и офлайн: серверный отзыв best-effort,
            // локальные токены удаляем в любом случае.
            runCatching { authService.logout(stored.authBaseUrl, refreshToken) }
        }
        runCatching { settings.clearAuth() }.onFailure { failures += it }
        runCatching { settings.clearWireGuardConfig() }.onFailure { failures += it }

        return failures.firstOrNull()
            ?.let { Result.failure(it) }
            ?: Result.success(Unit)
    }
}
