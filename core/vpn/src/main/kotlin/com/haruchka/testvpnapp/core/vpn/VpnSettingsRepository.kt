package com.haruchka.testvpnapp.core.vpn

import android.content.Context
import com.haruchka.testvpnapp.core.model.VpnServerConfig

/**
 * Простое хранилище последних настроек сервера.
 *
 * В демо токен лежит в обычных SharedPreferences. В реальном приложении его
 * нужно хранить через Android Keystore / EncryptedSharedPreferences.
 */
class VpnSettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): VpnServerConfig {
        val defaults = VpnServerConfig()
        val port = prefs.getInt(KEY_PORT, defaults.port)
        return VpnServerConfig(
            host = prefs.getString(KEY_HOST, defaults.host) ?: defaults.host,
            port = if (port in 1..65_535) port else defaults.port,
            token = prefs.getString(KEY_TOKEN, defaults.token) ?: defaults.token,
            mtu = prefs.getInt(KEY_MTU, defaults.mtu),
            dnsServer = prefs.getString(KEY_DNS, defaults.dnsServer) ?: defaults.dnsServer,
            tunnelAddress = defaults.tunnelAddress,
            tunnelPrefixLength = defaults.tunnelPrefixLength,
            keepAliveSeconds = prefs.getInt(KEY_KEEP_ALIVE, defaults.keepAliveSeconds),
        )
    }

    fun save(config: VpnServerConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .putInt(KEY_MTU, config.mtu)
            .putString(KEY_DNS, config.dnsServer)
            .putInt(KEY_KEEP_ALIVE, config.keepAliveSeconds)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "guardwire_settings"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_MTU = "mtu"
        const val KEY_DNS = "dns"
        const val KEY_KEEP_ALIVE = "keep_alive_seconds"
    }
}
