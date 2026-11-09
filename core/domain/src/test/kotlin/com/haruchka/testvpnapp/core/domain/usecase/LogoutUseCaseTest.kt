package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStoredSettings
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutUseCaseTest {

    @Test
    fun `logout revokes refresh token and clears local secrets`() = runBlocking {
        val settings = FakeSettingsStore()
        val auth = FakeAuthService()
        val session = FakeVpnSession()

        val result = useCase(session, settings, auth).invoke(disconnect = true)

        assertTrue(result.isSuccess)
        assertTrue(session.disconnected)
        assertEquals("refresh", auth.revokedToken)
        assertTrue(settings.authCleared)
        assertTrue(settings.wireGuardCleared)
    }

    @Test
    fun `offline logout still clears local secrets`() = runBlocking {
        val settings = FakeSettingsStore()
        val auth = FakeAuthService(failLogout = true)
        val session = FakeVpnSession()

        val result = useCase(session, settings, auth).invoke(disconnect = false)

        assertTrue(result.isSuccess)
        assertFalse(session.disconnected)
        assertTrue(settings.authCleared)
        assertTrue(settings.wireGuardCleared)
    }

    private fun useCase(
        session: FakeVpnSession,
        settings: FakeSettingsStore,
        auth: FakeAuthService,
    ) = LogoutUseCase(session, settings, auth)

    private class FakeVpnSession : VpnSession {
        override val state: StateFlow<VpnRuntimeState> = MutableStateFlow(VpnRuntimeState())
        var disconnected = false

        override fun connect(config: VpnServerConfig) = Unit

        override fun connectWireGuard(configText: String) = Unit

        override fun disconnect() {
            disconnected = true
        }
    }

    private class FakeAuthService(
        private val failLogout: Boolean = false,
    ) : AuthService {
        var revokedToken: String? = null

        override suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
        ): AuthSession = error("not used")

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
        ): AuthSession = error("not used")

        override suspend fun provisionWireGuard(baseUrl: String, accessToken: String): String =
            error("not used")

        override suspend fun logout(baseUrl: String, refreshToken: String) {
            revokedToken = refreshToken
            if (failLogout) error("network is down")
        }
    }

    private class FakeSettingsStore : VpnSettingsStore {
        private val stored = VpnStoredSettings(
            authToken = "access",
            authRefreshToken = "refresh",
            authExpiresAt = System.currentTimeMillis() / 1_000L + 60,
            authRefreshExpiresAt = System.currentTimeMillis() / 1_000L + 3_600,
            authBaseUrl = "http://vpn.example:8090",
        )
        var authCleared = false
        var wireGuardCleared = false

        override suspend fun load(): VpnStoredSettings = stored

        override suspend fun saveMode(mode: VpnMode) = Unit

        override suspend fun saveSelfMadeGuard(config: VpnServerConfig) = Unit

        override suspend fun saveWireGuardConfig(config: String) = Unit

        override suspend fun clearWireGuardConfig() {
            wireGuardCleared = true
        }

        override suspend fun saveAuth(
            accessToken: String,
            refreshToken: String,
            username: String,
            accessExpiresAtEpochSeconds: Long,
            refreshExpiresAtEpochSeconds: Long,
            baseUrl: String,
        ) = Unit

        override suspend fun clearAuth() {
            authCleared = true
        }
    }
}
