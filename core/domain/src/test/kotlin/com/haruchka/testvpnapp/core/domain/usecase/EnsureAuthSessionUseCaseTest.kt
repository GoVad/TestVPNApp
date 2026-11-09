package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStoredSettings
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsureAuthSessionUseCaseTest {

    @Test
    fun `valid access token is returned without refresh`() = runBlocking {
        val now = System.currentTimeMillis() / 1_000L
        val settings = FakeSettingsStore(
            VpnStoredSettings(
                authToken = "access",
                authRefreshToken = "refresh",
                authUsername = "demo",
                authExpiresAt = now + 60,
                authRefreshExpiresAt = now + 3_600,
                authBaseUrl = "http://vpn.example:8090",
            ),
        )
        val auth = FakeAuthService()

        val session = useCase(settings, auth).invoke().getOrThrow()

        assertEquals("access", session.accessToken)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun `expired access token is refreshed and saved`() = runBlocking {
        val now = System.currentTimeMillis() / 1_000L
        val settings = FakeSettingsStore(
            VpnStoredSettings(
                authToken = "old-access",
                authRefreshToken = "old-refresh",
                authUsername = "demo",
                authExpiresAt = 1L,
                authRefreshExpiresAt = now + 3_600,
                authBaseUrl = "http://vpn.example:8090",
            ),
        )
        val auth = FakeAuthService()

        val session = useCase(settings, auth).invoke().getOrThrow()

        assertEquals("new-access", session.accessToken)
        assertEquals("new-refresh", session.refreshToken)
        assertEquals(1, auth.refreshCalls)
        assertEquals("old-refresh", auth.lastRefreshToken)
        assertEquals("new-access", settings.savedAccessToken)
        assertEquals("new-refresh", settings.savedRefreshToken)
    }

    @Test
    fun `expired refresh token requires a new login`() = runBlocking {
        val settings = FakeSettingsStore(
            VpnStoredSettings(
                authToken = "old-access",
                authRefreshToken = "old-refresh",
                authExpiresAt = 1L,
                authRefreshExpiresAt = 2L,
            ),
        )
        val auth = FakeAuthService()

        val result = useCase(settings, auth).invoke()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthSessionExpiredException)
        assertEquals(0, auth.refreshCalls)
    }

    private fun useCase(
        settings: FakeSettingsStore,
        auth: FakeAuthService,
    ) = EnsureAuthSessionUseCase(settings, auth)

    private class FakeAuthService : AuthService {
        var refreshCalls = 0
        var lastRefreshToken: String? = null

        override suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
        ): AuthSession = error("not used")

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
        ): AuthSession {
            refreshCalls++
            lastRefreshToken = refreshToken
            val now = System.currentTimeMillis() / 1_000L
            return AuthSession(
                accessToken = "new-access",
                refreshToken = "new-refresh",
                username = "demo",
                accessExpiresAtEpochSeconds = now + 60,
                refreshExpiresAtEpochSeconds = now + 3_600,
                wireGuardConfig = null,
            )
        }

        override suspend fun provisionWireGuard(baseUrl: String, accessToken: String): String =
            error("not used")

        override suspend fun logout(baseUrl: String, refreshToken: String) = Unit
    }

    private class FakeSettingsStore(
        private val stored: VpnStoredSettings,
    ) : VpnSettingsStore {
        var savedAccessToken: String? = null
        var savedRefreshToken: String? = null

        override suspend fun load(): VpnStoredSettings = stored

        override suspend fun saveMode(mode: VpnMode) = Unit

        override suspend fun saveSelfMadeGuard(config: VpnServerConfig) = Unit

        override suspend fun saveWireGuardConfig(config: String) = Unit

        override suspend fun clearWireGuardConfig() = Unit

        override suspend fun saveAuth(
            accessToken: String,
            refreshToken: String,
            username: String,
            accessExpiresAtEpochSeconds: Long,
            refreshExpiresAtEpochSeconds: Long,
            baseUrl: String,
        ) {
            savedAccessToken = accessToken
            savedRefreshToken = refreshToken
        }

        override suspend fun clearAuth() = Unit
    }
}
