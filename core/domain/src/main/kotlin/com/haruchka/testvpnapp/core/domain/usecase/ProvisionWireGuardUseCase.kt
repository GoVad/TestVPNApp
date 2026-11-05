package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser

/** Конфиг, полученный от сервера, и его краткий профиль для UI. */
data class ProvisionedWireGuard(
    val config: String,
    val profile: WireGuardProfile,
)

/**
 * Запрашивает WireGuard-профиль у auth-сервера и сохраняет его в Keystore-
 * зашифрованный DataStore. Пользователю не нужно вставлять .conf вручную.
 */
class ProvisionWireGuardUseCase(
    private val authService: AuthService,
    private val settings: VpnSettingsStore,
    private val profileParser: WireGuardProfileParser,
) {
    suspend operator fun invoke(): Result<ProvisionedWireGuard> = runCatching {
        val stored = settings.load()
        val accessToken = stored.authToken?.takeIf { it.isNotBlank() }
            ?: throw AuthSessionExpiredException()

        val config = authService.provisionWireGuard(stored.authBaseUrl, accessToken)
        val profile = profileParser.profile(config)
        try {
            settings.saveWireGuardConfig(config)
        } catch (error: Exception) {
            throw WireGuardStorageException(error)
        }

        ProvisionedWireGuard(config = config, profile = profile)
    }
}
