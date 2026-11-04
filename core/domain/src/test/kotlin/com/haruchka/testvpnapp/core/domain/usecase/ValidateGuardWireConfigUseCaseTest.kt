package com.haruchka.testvpnapp.core.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateGuardWireConfigUseCaseTest {

    private val useCase = ValidateGuardWireConfigUseCase()

    @Test
    fun `valid values are trimmed and accepted`() {
        val config = useCase("  vpn.example  ", " 8443 ", " psk ").getOrThrow()

        assertEquals("vpn.example", config.host)
        assertEquals(8443, config.port)
        assertEquals("psk", config.token)
    }

    @Test
    fun `port outside range is rejected`() {
        val result = useCase("vpn.example", "70000", "psk")

        assertTrue(result.isFailure)
        assertEquals(
            "Порт должен быть числом от 1 до 65535",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `blank token is rejected`() {
        val result = useCase("vpn.example", "8443", "   ")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
    }
}
