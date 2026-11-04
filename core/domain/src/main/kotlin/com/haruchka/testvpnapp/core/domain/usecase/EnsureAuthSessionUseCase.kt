package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore

/**
 * Возвращает активную access-сессию. Если access-токен истёк, но refresh
 * ещё жив, продлевает сессию и сохраняет новые токены.
 */
class EnsureAuthSessionUseCase(
    private val settings: VpnSettingsStore,
    private val authService: AuthService,
) {
    suspend operator fun invoke(): Result<AuthSession> = runCatching {
        val stored = settings.load()
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val accessToken = stored.authToken
        if (accessToken != null && stored.authExpiresAt > nowSeconds) {
            return@runCatching AuthSession(
                accessToken = accessToken,
                refreshToken = stored.authRefreshToken.orEmpty(),
                username = stored.authUsername.orEmpty(),
                accessExpiresAtEpochSeconds = stored.authExpiresAt,
                refreshExpiresAtEpochSeconds = stored.authRefreshExpiresAt,
                wireGuardConfig = null,
            )
        }

        val refreshToken = stored.authRefreshToken
        if (refreshToken.isNullOrBlank() || stored.authRefreshExpiresAt <= nowSeconds) {
            throw AuthSessionExpiredException()
        }

        val session = authService.refresh(stored.authBaseUrl, refreshToken)
        settings.saveAuth(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            username = session.username,
            accessExpiresAtEpochSeconds = session.accessExpiresAtEpochSeconds,
            refreshExpiresAtEpochSeconds = session.refreshExpiresAtEpochSeconds,
            baseUrl = stored.authBaseUrl,
        )
        if (session.wireGuardConfig != null) {
            settings.saveWireGuardConfig(session.wireGuardConfig)
        }
        session
    }
}
