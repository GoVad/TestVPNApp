package com.haruchka.testvpnapp.core.domain

/** Краткая информация о WireGuard-профиле для UI. Без приватного ключа. */
data class WireGuardProfile(
    val addresses: String,
    val endpoint: String,
)

/** Контракт разбора/проверки WireGuard-конфига. */
interface WireGuardProfileParser {
    fun profile(configText: String): WireGuardProfile
}
