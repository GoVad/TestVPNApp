package com.haruchka.testvpnapp.core.selfmadeguard.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelfMadeGuardCryptoTest {

    @Test
    fun `aes gcm round trip`() {
        val session = SelfMadeGuardSession(SelfMadeGuardCrypto.randomBytes(32))
        val payload = "secret ip packet".toByteArray()

        val encrypted = session.encrypt(SelfMadeGuardFrameType.DATA.code, payload)
        assertArrayEquals(
            payload,
            session.decrypt(SelfMadeGuardFrameType.DATA.code, encrypted),
        )
    }

    @Test
    fun `tampered frame type fails authentication`() {
        val session = SelfMadeGuardSession(SelfMadeGuardCrypto.randomBytes(32))
        val encrypted = session.encrypt(SelfMadeGuardFrameType.DATA.code, byteArrayOf(1, 2, 3))

        try {
            session.decrypt(SelfMadeGuardFrameType.PING.code, encrypted)
            fail("Ожидался SelfMadeGuardException")
        } catch (_: SelfMadeGuardException) {
            // expected
        }
    }

    @Test
    fun `client proof verifies only with correct psk`() {
        val handshake = SelfMadeGuardCrypto.createClientHandshake(token = "right", mtu = 1_400)
        val request = SelfMadeGuardCrypto.createClientRequest(handshake)

        assertTrue(SelfMadeGuardCrypto.verifyClientProof("right", request))
        assertFalse(SelfMadeGuardCrypto.verifyClientProof("wrong", request))
    }

    @Test
    fun `empty psk is rejected`() {
        try {
            SelfMadeGuardCrypto.createClientHandshake(token = "", mtu = 1_400)
            fail("Ожидался IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
