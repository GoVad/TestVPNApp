package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Настройки и сессия, восстановленные при старте приложения. */
data class LoadedVpnSettings(
    val mode: VpnMode,
    val guardWire: VpnServerConfig,
    val wireGuardConfig: String?,
    val wireGuardProfile: WireGuardProfile?,
    val authToken: String?,
    val authUsername: String?,
    val authExpiresAtEpochSeconds: Long,
    val authRefreshExpiresAtEpochSeconds: Long,
    val authBaseUrl: String,
    val isAuthValid: Boolean,
    val isRefreshValid: Boolean,
)

/**
 * Загружает сохранённые настройки, проверяет срок auth-сессии и разбирает
 * WireGuard-профиль. Не знает, как выглядит UI.
 */
class LoadVpnSettingsUseCase(
    private val settings: VpnSettingsStore,
    private val profileParser: WireGuardProfileParser,
) {
    suspend operator fun invoke(): Result<LoadedVpnSettings> = runCatching {
        val stored = settings.load()
        val profile = stored.wireGuardConfig?.let {
            runCatching { profileParser.profile(it) }.getOrNull()
        }
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val authValid = stored.authToken != null && stored.authExpiresAt > nowSeconds
        val refreshValid = stored.authRefreshToken != null && stored.authRefreshExpiresAt > nowSeconds

        LoadedVpnSettings(
            mode = stored.mode,
            guardWire = stored.guardWire,
            wireGuardConfig = stored.wireGuardConfig,
            wireGuardProfile = profile,
            authToken = stored.authToken,
            authUsername = stored.authUsername,
            authExpiresAtEpochSeconds = stored.authExpiresAt,
            authRefreshExpiresAtEpochSeconds = stored.authRefreshExpiresAt,
            authBaseUrl = stored.authBaseUrl,
            isAuthValid = authValid,
            isRefreshValid = refreshValid,
        )
    }
}
