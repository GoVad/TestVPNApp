package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser

/**
 * Проверяет WireGuard-конфиг, строит профиль для UI и сохраняет конфиг
 * в зашифрованное хранилище.
 */
class SaveWireGuardConfigUseCase(
    private val settings: VpnSettingsStore,
    private val profileParser: WireGuardProfileParser,
) {
    suspend operator fun invoke(configText: String): Result<WireGuardProfile> = runCatching {
        val profile = profileParser.profile(configText)
        try {
            settings.saveWireGuardConfig(configText)
        } catch (error: Exception) {
            throw IllegalStateException("Не удалось сохранить WireGuard-конфиг", error)
        }
        profile
    }
}
