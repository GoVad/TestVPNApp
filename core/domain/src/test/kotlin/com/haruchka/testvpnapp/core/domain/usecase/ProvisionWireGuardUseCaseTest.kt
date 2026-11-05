package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.AuthSession
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
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

class ProvisionWireGuardUseCaseTest {

    @Test
    fun `provisioned config is validated and saved`() = runBlocking {
        val settings = FakeSettingsStore(accessToken = "access")
        val result = useCase(settings).invoke().getOrThrow()

        assertEquals("wg-config", result.config)
        assertEquals("10.0.0.2/32", result.profile.addresses)
        assertEquals("wg-config", settings.savedConfig)
    }

    @Test
    fun `missing access token requires login`() = runBlocking {
        val settings = FakeSettingsStore(accessToken = null)
        val result = useCase(settings).invoke()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthSessionExpiredException)
    }

    private fun useCase(settings: FakeSettingsStore) = ProvisionWireGuardUseCase(
        authService = FakeAuthService(),
        settings = settings,
        profileParser = FakeParser(),
    )

    private class FakeAuthService : AuthService {
        override suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
        ): AuthSession = error("not used")

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
        ): AuthSession = error("not used")

        override suspend fun provisionWireGuard(
            baseUrl: String,
            accessToken: String,
        ): String = "wg-config"

        override suspend fun logout(baseUrl: String, refreshToken: String) = Unit
    }

    private class FakeParser : WireGuardProfileParser {
        override fun profile(configText: String): WireGuardProfile = WireGuardProfile(
            addresses = "10.0.0.2/32",
            endpoint = "vpn.example:51820",
        )
    }

    private class FakeSettingsStore(
        private val accessToken: String?,
    ) : VpnSettingsStore {
        var savedConfig: String? = null

        override suspend fun load(): VpnStoredSettings = VpnStoredSettings(
            authToken = accessToken,
            authBaseUrl = "https://vpn.example:8090",
        )

        override suspend fun saveMode(mode: VpnMode) = Unit

        override suspend fun saveGuardWire(config: VpnServerConfig) = Unit

        override suspend fun saveWireGuardConfig(config: String) {
            savedConfig = config
        }

        override suspend fun clearWireGuardConfig() = Unit

        override suspend fun saveAuth(
            accessToken: String,
            refreshToken: String,
            username: String,
            accessExpiresAtEpochSeconds: Long,
            refreshExpiresAtEpochSeconds: Long,
            baseUrl: String,
        ) = Unit

        override suspend fun clearAuth() = Unit
    }
}
