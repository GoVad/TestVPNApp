package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser

/** Результат входа: сессия и актуальный WireGuard-профиль, если он есть. */
data class LoginResult(
    val session: AuthSession,
    val wireGuardConfig: String?,
    val wireGuardProfile: WireGuardProfile?,
)

/**
 * Выполняет вход, сохраняет токен и выданный WireGuard-конфиг.
 *
 * Если сервер не прислал новый конфиг, используется ранее сохранённый.
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
        require(server.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty()) {
            "Заполните auth-сервер, логин и пароль"
        }

        val session = authService.login(server, user, password)
        val existingConfig = runCatching { settings.load().wireGuardConfig }.getOrNull()
        val config = session.wireGuardConfig ?: existingConfig

        runCatching {
            settings.saveAuth(
                token = session.token,
                username = session.username,
                expiresAtEpochSeconds = session.expiresAtEpochSeconds,
                baseUrl = server,
            )
        }
        if (session.wireGuardConfig != null) {
            runCatching { settings.saveWireGuardConfig(session.wireGuardConfig) }
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
