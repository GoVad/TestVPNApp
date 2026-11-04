package com.haruchka.testvpnapp.core.domain

import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Настройки, сохранённые между запусками. Секреты уже расшифрованы. */
data class VpnStoredSettings(
    val mode: VpnMode = VpnMode.SIMPLIFIED,
    val guardWire: VpnServerConfig = VpnServerConfig(),
    val wireGuardConfig: String? = null,
    val authToken: String? = null,
    val authUsername: String? = null,
    val authExpiresAt: Long = 0L,
    val authBaseUrl: String = "http://${VpnServerConfig().host}:8090",
)
