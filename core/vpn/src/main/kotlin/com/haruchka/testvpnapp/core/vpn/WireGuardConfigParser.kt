package com.haruchka.testvpnapp.core.vpn

import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader
import java.net.Inet6Address

/** Краткая информация о WireGuard-профиле для UI. Без приватного ключа. */
data class WireGuardProfile(
    val addresses: String,
    val endpoint: String,
)

/**
 * Разбор и проверка WireGuard-конфига.
 *
 * IPv6 намеренно запрещён: приложение работает только с IPv4, чтобы не было
 * утечек в сеть оператора.
 */
object WireGuardConfigParser {

    fun parse(text: String): Config {
        if (text.isBlank()) throw WireGuardConfigException("Конфиг WireGuard пуст")
        val config = try {
            Config.parse(BufferedReader(StringReader(text)))
        } catch (e: Exception) {
            throw WireGuardConfigException("Не удалось разобрать WireGuard-конфиг: ${e.message}")
        }

        validateIpv4Only(config)
        return config
    }

    fun profile(text: String): WireGuardProfile {
        val config = parse(text)
        val addresses = config.getInterface().addresses
            .joinToString(", ") { it.toString() }
            .ifBlank { "—" }
        val endpoint = config.peers.firstOrNull()
            ?.endpoint
            ?.orElse(null)
            ?.toString()
            ?: "—"
        return WireGuardProfile(addresses = addresses, endpoint = endpoint)
    }

    private fun validateIpv4Only(config: Config) {
        val ipv6Address = config.getInterface().addresses.any {
            it.address is Inet6Address
        }
        val ipv6Dns = config.getInterface().dnsServers.any { it is Inet6Address }
        val ipv6AllowedIp = config.peers.any { peer ->
            peer.allowedIps.any { it.address is Inet6Address }
        }
        val ipv6Endpoint = config.peers.any { peer ->
            peer.endpoint.orElse(null)?.host?.contains(':') == true
        }

        if (ipv6Address || ipv6Dns || ipv6AllowedIp || ipv6Endpoint) {
            throw WireGuardConfigException(
                "IPv6 отключён в этом приложении: удалите IPv6-адреса, DNS, AllowedIPs и IPv6 endpoint из конфига",
            )
        }
    }
}

class WireGuardConfigException(message: String) : IllegalArgumentException(message)
