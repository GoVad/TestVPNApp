package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStoredSettings
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUseCaseTest {

    @Test
    fun `login persists access refresh and wireguard config`() = runBlocking {
        val settings = FakeSettingsStore()
        val result = useCase(settings)(" http://vpn.example:8090 ", " demo ", "demo")
            .getOrThrow()

        assertEquals("access", result.session.accessToken)
        assertEquals("refresh", result.session.refreshToken)
        assertEquals("wg-config", settings.savedWireGuardConfig)
        assertEquals("access", settings.savedAccessToken)
        assertEquals("refresh", settings.savedRefreshToken)
        assertEquals("http://vpn.example:8090", settings.savedBaseUrl)
        assertEquals("10.0.0.2/32", result.wireGuardProfile?.addresses)
    }

    @Test
    fun `empty credentials are rejected before network call`() = runBlocking {
        val settings = FakeSettingsStore()
        val auth = FakeAuthService()
        val result = LoginUseCase(auth, settings, FakeParser())("", "", "")

        assertTrue(result.isFailure)
        assertEquals(0, auth.loginCalls)
    }

    @Test
    fun `storage failure fails login`() = runBlocking {
        val settings = FakeSettingsStore(failSaveAuth = true)
        val result = useCase(settings)("http://vpn.example:8090", "demo", "demo")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionStorageException)
    }

    private fun useCase(settings: FakeSettingsStore): LoginUseCase = LoginUseCase(
        authService = FakeAuthService(),
        settings = settings,
        profileParser = FakeParser(),
    )

    private class FakeAuthService : AuthService {
        var loginCalls = 0

        override suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
        ): AuthSession {
            loginCalls++
            return AuthSession(
                accessToken = "access",
                refreshToken = "refresh",
                username = username,
                accessExpiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + 60,
                refreshExpiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600,
                wireGuardConfig = "wg-config",
            )
        }

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
        ): AuthSession = error("not used")

        override suspend fun provisionWireGuard(baseUrl: String, accessToken: String): String =
            error("not used")

        override suspend fun logout(baseUrl: String, refreshToken: String) = Unit
    }

    private class FakeParser : WireGuardProfileParser {
        override fun profile(configText: String): WireGuardProfile = WireGuardProfile(
            addresses = "10.0.0.2/32",
            endpoint = "vpn.example:51820",
        )
    }

    private class FakeSettingsStore(
        private val failSaveAuth: Boolean = false,
    ) : VpnSettingsStore {
        var savedWireGuardConfig: String? = null
        var savedAccessToken: String? = null
        var savedRefreshToken: String? = null
        var savedBaseUrl: String? = null

        override suspend fun load(): VpnStoredSettings = VpnStoredSettings()

        override suspend fun saveMode(mode: VpnMode) = Unit

        override suspend fun saveSelfMadeGuard(config: VpnServerConfig) = Unit

        override suspend fun saveWireGuardConfig(config: String) {
            savedWireGuardConfig = config
        }

        override suspend fun clearWireGuardConfig() = Unit

        override suspend fun saveAuth(
            accessToken: String,
            refreshToken: String,
            username: String,
            accessExpiresAtEpochSeconds: Long,
            refreshExpiresAtEpochSeconds: Long,
            baseUrl: String,
        ) {
            if (failSaveAuth) error("keystore is unavailable")
            savedAccessToken = accessToken
            savedRefreshToken = refreshToken
            savedBaseUrl = baseUrl
        }

        override suspend fun clearAuth() = Unit
    }
}
