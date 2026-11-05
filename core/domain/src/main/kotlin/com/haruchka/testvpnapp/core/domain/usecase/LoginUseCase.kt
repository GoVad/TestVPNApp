package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser

class LoginValidationException : IllegalArgumentException("Invalid login fields")

class SessionStorageException(cause: Throwable) :
    IllegalStateException("Failed to save session", cause)

class WireGuardStorageException(cause: Throwable) :
    IllegalStateException("Failed to save WireGuard config", cause)

/** Результат входа: сессия и актуальный WireGuard-профиль, если он есть. */
data class LoginResult(
    val session: AuthSession,
    val wireGuardConfig: String?,
    val wireGuardProfile: WireGuardProfile?,
)

/**
 * Выполняет вход, сохраняет access/refresh-токены и выданный
 * WireGuard-конфиг. Если сервер не прислал новый конфиг, используется
 * ранее сохранённый.
 */
class LoginUseCase(
    private val authService: AuthService,
    private val settings: VpnSettingsStore,
    private val profileParser: WireGuardProfileParser,
) {
    suspend operator fun invoke(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<LoginResult> = runCatching {
        val server = baseUrl.trim()
        val user = username.trim()
        if (server.isEmpty() || user.isEmpty() || password.isEmpty()) {
            throw LoginValidationException()
        }

        val session = authService.login(server, user, password)
        val existingConfig = runCatching { settings.load().wireGuardConfig }.getOrNull()
        val config = session.wireGuardConfig ?: existingConfig

        try {
            settings.saveAuth(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                username = session.username,
                accessExpiresAtEpochSeconds = session.accessExpiresAtEpochSeconds,
                refreshExpiresAtEpochSeconds = session.refreshExpiresAtEpochSeconds,
                baseUrl = server,
            )
        } catch (error: Exception) {
            throw SessionStorageException(error)
        }
        if (session.wireGuardConfig != null) {
            try {
                settings.saveWireGuardConfig(session.wireGuardConfig)
            } catch (error: Exception) {
                throw WireGuardStorageException(error)
            }
        }

        val profile = config?.let {
            runCatching { profileParser.profile(it) }.getOrNull()
        }

        LoginResult(
            session = session,
            wireGuardConfig = config,
            wireGuardProfile = profile,
        )
    }
}
