package com.haruchka.testvpnapp.core.vpn

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Результат успешного входа в auth/control-plane. */
data class AuthSession(
    val token: String,
    val username: String,
    val expiresAtEpochSeconds: Long,
    val wireGuardConfig: String?,
)

/** Ошибка авторизации или связи с control plane. */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Клиент простого auth-сервера GuardWire.
 *
 * Логин идёт по HTTP (тестовый режим), пароль не сохраняется. Сервер возвращает
 * сессионный токен; для WireGuard дополнительно приходит набор полей, из
 * которых собирается конфиг с приватным ключом, сгенерированным на устройстве.
 */
@Singleton
class AuthRepository @Inject constructor() {

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AuthSession = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        val keyPair = KeyPair()
        val requestBody = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("device_name", "android")
            .put("wg_public_key", keyPair.publicKey.toBase64())
            .toString()

        val connection = try {
            (URL("$normalized/auth/login").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            throw AuthException("Некорректный адрес auth-сервера", e)
        }

        try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP $status"
                throw AuthException(message)
            }

            val json = JSONObject(body)
            val token = json.optString("token").takeIf { it.isNotBlank() }
                ?: throw AuthException("Сервер не вернул токен")
            val returnedUsername = json.optString("username", username)
            val expiresAt = json.optLong("expires_at", 0L)
            val wireGuardConfig = json.optJSONObject("wireguard")?.let {
                buildWireGuardConfig(keyPair, it)
            }

            AuthSession(
                token = token,
                username = returnedUsername,
                expiresAtEpochSeconds = expiresAt,
                wireGuardConfig = wireGuardConfig,
            )
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException("Не удалось войти: ${e.message ?: "ошибка сети"}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildWireGuardConfig(keyPair: KeyPair, wireguard: JSONObject): String {
        val serverPublicKey = wireguard.optString("server_public_key")
        val endpoint = wireguard.optString("endpoint")
        val clientIp = wireguard.optString("client_ip")
        val dns = wireguard.optString("dns", "1.1.1.1")
        val mtu = wireguard.optInt("mtu", 1420)

        if (serverPublicKey.isBlank() || endpoint.isBlank() || clientIp.isBlank()) {
            throw AuthException("Auth-сервер вернул неполный WireGuard-конфиг")
        }

        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${keyPair.privateKey.toBase64()}")
            appendLine("Address = $clientIp")
            appendLine("DNS = $dns")
            appendLine("MTU = $mtu")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $serverPublicKey")
            appendLine("Endpoint = $endpoint")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("PersistentKeepalive = 25")
        }

        // Не даём сохранить IPv6-конфиг, даже если сервер ошибся.
        WireGuardConfigParser.profile(config)
        return config
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw AuthException("Укажите адрес auth-сервера")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
