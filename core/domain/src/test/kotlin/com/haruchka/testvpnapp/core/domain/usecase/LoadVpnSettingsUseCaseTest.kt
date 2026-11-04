package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStoredSettings
import com.haruchka.testvpnapp.core.domain.WireGuardProfile
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadVpnSettingsUseCaseTest {

    @Test
    fun `expired auth token is reported as invalid`() = runBlocking {
        val result = useCase(
            token = "token",
            expiresAt = 1L,
        ).invoke().getOrThrow()

        assertFalse(result.isAuthValid)
        assertEquals("demo", result.authUsername)
        assertEquals("10.0.0.2/32", result.wireGuardProfile?.addresses)
    }

    @Test
    fun `fresh auth token is reported as valid`() = runBlocking {
        val result = useCase(
            token = "token",
            expiresAt = System.currentTimeMillis() / 1_000L + 60,
        ).invoke().getOrThrow()

        assertTrue(result.isAuthValid)
    }

    private fun useCase(
        token: String?,
        expiresAt: Long,
    ): LoadVpnSettingsUseCase = LoadVpnSettingsUseCase(
        settings = FakeSettingsStore(
            VpnStoredSettings(
                mode = VpnMode.REAL,
                guardWire = VpnServerConfig(host = "vpn.example", port = 8443, token = "psk"),
                wireGuardConfig = "config",
                authToken = token,
                authUsername = "demo",
                authExpiresAt = expiresAt,
                authBaseUrl = "http://vpn.example:8090",
            ),
        ),
        profileParser = FakeProfileParser(),
    )

    private class FakeSettingsStore(
        private val stored: VpnStoredSettings,
    ) : VpnSettingsStore {
        override suspend fun load(): VpnStoredSettings = stored

        override suspend fun saveMode(mode: VpnMode) = Unit

        override suspend fun saveGuardWire(config: VpnServerConfig) = Unit

        override suspend fun saveWireGuardConfig(config: String) = Unit

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

    private class FakeProfileParser : WireGuardProfileParser {
        override fun profile(configText: String): WireGuardProfile = WireGuardProfile(
            addresses = "10.0.0.2/32",
            endpoint = "vpn.example:51820",
        )
    }
}
