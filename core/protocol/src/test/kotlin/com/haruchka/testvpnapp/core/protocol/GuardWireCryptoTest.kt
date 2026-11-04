package com.haruchka.testvpnapp.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuardWireCryptoTest {

    @Test
    fun `aes gcm round trip`() {
        val session = GuardWireSession(GuardWireCrypto.randomBytes(32))
        val payload = "secret ip packet".toByteArray()

        val encrypted = session.encrypt(GuardWireFrameType.DATA.code, payload)
        assertArrayEquals(
            payload,
            session.decrypt(GuardWireFrameType.DATA.code, encrypted),
        )
    }

    @Test
    fun `tampered frame type fails authentication`() {
        val session = GuardWireSession(GuardWireCrypto.randomBytes(32))
        val encrypted = session.encrypt(GuardWireFrameType.DATA.code, byteArrayOf(1, 2, 3))

        try {
            session.decrypt(GuardWireFrameType.PING.code, encrypted)
            fail("Ожидался GuardWireException")
        } catch (_: GuardWireException) {
            // expected
        }
    }

    @Test
    fun `client proof verifies only with correct psk`() {
        val handshake = GuardWireCrypto.createClientHandshake(token = "right", mtu = 1_400)
        val request = GuardWireCrypto.createClientRequest(handshake)

        assertTrue(GuardWireCrypto.verifyClientProof("right", request))
        assertFalse(GuardWireCrypto.verifyClientProof("wrong", request))
    }

    @Test
    fun `empty psk is rejected`() {
        try {
            GuardWireCrypto.createClientHandshake(token = "", mtu = 1_400)
            fail("Ожидался IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
