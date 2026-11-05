package com.haruchka.testvpnapp.core.data

import android.content.Context
import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.wireguard.crypto.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Ошибка авторизации или связи с control plane. */
open class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Сервер ответил конкретным HTTP-кодом. */
class AuthHttpException(
    val statusCode: Int,
    message: String,
) : AuthException(message)

/**
 * Клиент auth-сервера GuardWire.
 *
 * Логин возвращает короткий access-токен и длинный refresh-токен. Access
 * используется как PSK для GuardWire, refresh — только для продления сессии.
 * Пароль не сохраняется. Для WireGuard сервер дополнительно присылает поля,
 * из которых собирается конфиг с приватным ключом, сгенерированным на устройстве.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val profileParser: WireGuardProfileParser,
) : AuthService {

    private val appContext = context.applicationContext

    /**
     * Доверяем только локальному CA из ресурсов. Это одновременно TLS
     * trust anchor и pinning: системные/публичные CA для auth-сервера не
     * принимаются.
     */
    private val sslSocketFactory: SSLSocketFactory by lazy {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val ca = appContext.resources.openRawResource(R.raw.auth_ca).use {
            certificateFactory.generateCertificate(it)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("guardwire-auth-ca", ca)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        ).apply {
            init(keyStore)
        }
        SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, null)
        }.socketFactory
    }

    override suspend fun login(
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

        val json = postJson(normalized, "/auth/login", requestBody)
        val session = parseSession(json, fallbackUsername = username)
        val wireGuardConfig = json.optJSONObject("wireguard")?.let {
            buildWireGuardConfig(keyPair, it)
        }
        session.copy(wireGuardConfig = wireGuardConfig)
    }

    override suspend fun refresh(
        baseUrl: String,
        refreshToken: String,
    ): AuthSession = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        val requestBody = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()

        val json = try {
            postJson(normalized, "/auth/refresh", requestBody)
        } catch (error: AuthHttpException) {
            if (error.statusCode == 401) throw AuthSessionExpiredException()
            throw error
        }
        parseSession(json, fallbackUsername = "")
    }

    override suspend fun provisionWireGuard(
        baseUrl: String,
        accessToken: String,
    ): String = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        val keyPair = KeyPair()
        val requestBody = JSONObject()
            .put("wg_public_key", keyPair.publicKey.toBase64())
            .toString()
        val json = postJson(
            baseUrl = normalized,
            path = "/auth/wireguard",
            requestBody = requestBody,
            bearerToken = accessToken,
        )
        val wireguard = json.optJSONObject("wireguard")
            ?: throw AuthException(appContext.getString(R.string.auth_error_wg_missing))
        buildWireGuardConfig(keyPair, wireguard)
    }

    override suspend fun logout(
        baseUrl: String,
        refreshToken: String,
    ): Unit = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        val requestBody = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
        postJson(normalized, "/auth/logout", requestBody)
        Unit
    }

    private fun parseSession(json: JSONObject, fallbackUsername: String): AuthSession {
        val accessToken = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw AuthException(appContext.getString(R.string.auth_error_access_missing))
        val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw AuthException(appContext.getString(R.string.auth_error_refresh_missing))
        val accessExpiresAt = json.optLong(
            "access_expires_at",
            json.optLong("expires_at", 0L),
        )
        val refreshExpiresAt = json.optLong("refresh_expires_at", 0L)
        if (accessExpiresAt <= 0L || refreshExpiresAt <= 0L) {
            throw AuthException(appContext.getString(R.string.auth_error_expiry_missing))
        }

        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            username = json.optString("username", fallbackUsername),
            accessExpiresAtEpochSeconds = accessExpiresAt,
            refreshExpiresAtEpochSeconds = refreshExpiresAt,
            wireGuardConfig = null,
        )
    }

    private fun postJson(
        baseUrl: String,
        path: String,
        requestBody: String,
        bearerToken: String? = null,
    ): JSONObject {
        val connection = try {
            (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    sslSocketFactory = this@AuthRepository.sslSocketFactory
                }
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
        } catch (e: Exception) {
            throw AuthException(appContext.getString(R.string.auth_error_bad_url), e)
        }

        try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.auth_error_http_status, status)
                throw AuthHttpException(status, message)
            }

            return runCatching { JSONObject(body) }
                .getOrElse { throw AuthException(appContext.getString(R.string.auth_error_bad_response), it) }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException(
                appContext.getString(
                    R.string.auth_error_network,
                    e.message ?: appContext.getString(R.string.auth_error_network_short),
                ),
                e,
            )
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
            throw AuthException(appContext.getString(R.string.auth_error_incomplete_wg))
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
        profileParser.profile(config)
        return config
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw AuthException(appContext.getString(R.string.auth_error_url_empty))
        if (trimmed.startsWith("http://")) {
            throw AuthException(appContext.getString(R.string.auth_error_https_required))
        }
        return if (trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
