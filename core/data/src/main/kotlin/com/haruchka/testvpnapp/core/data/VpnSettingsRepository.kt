package com.haruchka.testvpnapp.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStoredSettings
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private val Context.vpnSettingsDataStore by preferencesDataStore(name = "vpn_settings")

/**
 * DataStore для настроек VPN.
 *
 * Не секретные поля (host, port, режим) лежат открыто; токен GuardWire и
 * WireGuard-конфиг (в нём приватный ключ) шифруются ключом Android Keystore.
 */
@Singleton
class VpnSettingsRepository @Inject constructor(@ApplicationContext context: Context) : VpnSettingsStore {

    private val appContext = context.applicationContext
    private val dataStore = appContext.vpnSettingsDataStore
    private val cipher = SecretCipher()

    override suspend fun load(): VpnStoredSettings = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val mode = prefs[KEY_MODE]?.let { runCatching { VpnMode.valueOf(it) }.getOrNull() }
            ?: VpnMode.SIMPLIFIED

        val defaults = VpnServerConfig()
        var guardWire = VpnServerConfig(
            host = prefs[KEY_HOST] ?: defaults.host,
            port = prefs[KEY_PORT] ?: defaults.port,
            token = prefs[KEY_TOKEN]?.let(cipher::decrypt) ?: defaults.token,
        )

        // Мягкая миграция со старого SharedPreferences-хранилища.
        if (prefs[KEY_HOST] == null) {
            val legacy = legacyGuardWire()
            if (legacy != null) {
                guardWire = legacy
                saveGuardWire(legacy)
            }
        }

        val wireGuardConfig = prefs[KEY_WG_CONFIG]?.let(cipher::decrypt)
        VpnStoredSettings(
            mode = mode,
            guardWire = guardWire,
            wireGuardConfig = wireGuardConfig,
            authToken = prefs[KEY_AUTH_TOKEN]?.let(cipher::decrypt),
            authRefreshToken = prefs[KEY_AUTH_REFRESH_TOKEN]?.let(cipher::decrypt),
            authUsername = prefs[KEY_AUTH_USER],
            authExpiresAt = prefs[KEY_AUTH_EXPIRES]?.toLongOrNull() ?: 0L,
            authRefreshExpiresAt = prefs[KEY_AUTH_REFRESH_EXPIRES]?.toLongOrNull() ?: 0L,
            authBaseUrl = (prefs[KEY_AUTH_URL] ?: "https://${guardWire.host}:8090")
                .replaceFirst("http://", "https://"),
        )
    }

    override suspend fun saveMode(mode: VpnMode): Unit = withContext(Dispatchers.IO) {
        dataStore.edit { it[KEY_MODE] = mode.name }
    }

    override suspend fun saveGuardWire(config: VpnServerConfig): Unit = withContext(Dispatchers.IO) {
        dataStore.edit {
            it[KEY_HOST] = config.host
            it[KEY_PORT] = config.port
            it[KEY_TOKEN] = cipher.encrypt(config.token)
        }
    }

    override suspend fun saveWireGuardConfig(config: String): Unit = withContext(Dispatchers.IO) {
        dataStore.edit { it[KEY_WG_CONFIG] = cipher.encrypt(config) }
    }

    override suspend fun clearWireGuardConfig(): Unit = withContext(Dispatchers.IO) {
        dataStore.edit { it.remove(KEY_WG_CONFIG) }
    }

    override suspend fun saveAuth(
        accessToken: String,
        refreshToken: String,
        username: String,
        accessExpiresAtEpochSeconds: Long,
        refreshExpiresAtEpochSeconds: Long,
        baseUrl: String,
    ): Unit = withContext(Dispatchers.IO) {
        dataStore.edit {
            it[KEY_AUTH_TOKEN] = cipher.encrypt(accessToken)
            it[KEY_AUTH_REFRESH_TOKEN] = cipher.encrypt(refreshToken)
            it[KEY_AUTH_USER] = username
            it[KEY_AUTH_EXPIRES] = accessExpiresAtEpochSeconds.toString()
            it[KEY_AUTH_REFRESH_EXPIRES] = refreshExpiresAtEpochSeconds.toString()
            it[KEY_AUTH_URL] = baseUrl
        }
    }

    override suspend fun clearAuth(): Unit = withContext(Dispatchers.IO) {
        dataStore.edit {
            it.remove(KEY_AUTH_TOKEN)
            it.remove(KEY_AUTH_REFRESH_TOKEN)
            it.remove(KEY_AUTH_USER)
            it.remove(KEY_AUTH_EXPIRES)
            it.remove(KEY_AUTH_REFRESH_EXPIRES)
        }
    }

    private fun legacyGuardWire(): VpnServerConfig? {
        val prefs = appContext.getSharedPreferences("guardwire_settings", Context.MODE_PRIVATE)
        if (!prefs.contains("host")) return null
        val defaults = VpnServerConfig()
        val port = prefs.getInt("port", defaults.port)
        return VpnServerConfig(
            host = prefs.getString("host", defaults.host) ?: defaults.host,
            port = if (port in 1..65_535) port else defaults.port,
            token = prefs.getString("token", defaults.token) ?: defaults.token,
        )
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("port")
        val KEY_TOKEN = stringPreferencesKey("token_encrypted")
        val KEY_WG_CONFIG = stringPreferencesKey("wireguard_config_encrypted")
        val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token_encrypted")
        val KEY_AUTH_REFRESH_TOKEN = stringPreferencesKey("auth_refresh_token_encrypted")
        val KEY_AUTH_USER = stringPreferencesKey("auth_username")
        val KEY_AUTH_EXPIRES = stringPreferencesKey("auth_expires_at")
        val KEY_AUTH_REFRESH_EXPIRES = stringPreferencesKey("auth_refresh_expires_at")
        val KEY_AUTH_URL = stringPreferencesKey("auth_base_url")
    }
}

/** AES-GCM ключ из Android Keystore; приватный материал не покидает Keystore. */
private class SecretCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, iv),
        )
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "guardwire_settings_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_SIZE = 12
    }
}
