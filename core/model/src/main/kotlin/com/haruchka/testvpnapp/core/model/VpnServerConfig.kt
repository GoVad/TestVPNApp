package com.haruchka.testvpnapp.core.model

/**
 * Настройки подключения к GuardWire-серверу.
 *
 * Все параметры, кроме [host] и [port], имеют значения по умолчанию, чтобы демо
 * можно было запустить без отдельного экрана настроек.
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
