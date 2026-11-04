package com.haruchka.testvpnapp.core.model

/**
 * Настройки подключения к GuardWire-серверу.
 *
 * [token] — PSK: общий секрет клиента и сервера. По сети он не передаётся,
 * а используется для HMAC-аутентификации handshake.
 */
data class VpnServerConfig(
    val host: String = "10.0.2.2",
    val port: Int = 8443,
    val token: String = "demo",
    val mtu: Int = 1400,
    val dnsServer: String = "1.1.1.1",
    val tunnelAddress: String = "10.8.0.2",
    val tunnelPrefixLength: Int = 32,
    val keepAliveSeconds: Int = 15,
) {
    /** Адрес в формате `host:port` для отображения в интерфейсе. */
    val endpoint: String get() = "$host:$port"
}
