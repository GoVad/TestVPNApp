package com.haruchka.testvpnapp.core.selfmadeguard.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class SelfMadeGuardCodecTest {

    @Test
    fun `data frame survives encode decode`() {
        val frame = SelfMadeGuardFrame(
            type = SelfMadeGuardFrameType.DATA,
            payload = byteArrayOf(0x45, 0x00, 0x00, 0x14, 0x7F, 0x01),
        )

        assertEquals(frame, encodeThenDecode(frame))
    }

    @Test
    fun `empty keep alive frame survives encode decode`() {
        val frame = SelfMadeGuardFrame(SelfMadeGuardFrameType.KEEP_ALIVE, ByteArray(0))

        assertEquals(frame, encodeThenDecode(frame))
    }

    @Test
    fun `read returns null on clean eof`() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))

        assertNull(SelfMadeGuardFrameCodec.read(input))
    }

    @Test
    fun `read fails on truncated payload`() {
        val bytes = ByteArrayOutputStream().also { out ->
            out.write(SelfMadeGuardFrameType.DATA.code)
            out.write(0)
            out.write(4)
            out.write(byteArrayOf(1, 2))
        }.toByteArray()

        try {
            SelfMadeGuardFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
            fail("Ожидался EOFException")
        } catch (_: EOFException) {
            // expected
        }
    }

    @Test
    fun `unknown frame type is rejected`() {
        val bytes = ByteArrayOutputStream().also { out ->
            out.write(0x7F)
            out.write(0)
            out.write(0)
        }.toByteArray()

        try {
            SelfMadeGuardFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
            fail("Ожидался SelfMadeGuardException")
        } catch (e: SelfMadeGuardException) {
            assertTrue(e.message.orEmpty().contains("Unknown SelfMadeGuard frame type"))
        }
    }

    @Test
    fun `handshake request round trip`() {
        val output = ByteArrayOutputStream()
        val request = SelfMadeGuardHandshakeRequest(
            mtu = 1_400,
            clientNonce = ByteArray(SelfMadeGuardCrypto.NONCE_SIZE) { it.toByte() },
            clientPublicKey = ByteArray(91) { (it + 10).toByte() },
            proof = ByteArray(SelfMadeGuardCrypto.PROOF_SIZE) { (it + 20).toByte() },
        )

        DataOutputStream(output).use { SelfMadeGuardHandshakeCodec.writeRequest(it, request) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            SelfMadeGuardHandshakeCodec.readRequest(it)
        }

        assertEquals(request.mtu, decoded.mtu)
        assertArrayEquals(request.clientNonce, decoded.clientNonce)
        assertArrayEquals(request.clientPublicKey, decoded.clientPublicKey)
        assertArrayEquals(request.proof, decoded.proof)
    }

    @Test
    fun `handshake response round trip`() {
        val output = ByteArrayOutputStream()
        val response = SelfMadeGuardHandshakeResponse(
            accepted = true,
            message = "welcome",
            serverNonce = ByteArray(SelfMadeGuardCrypto.NONCE_SIZE) { (it + 1).toByte() },
            serverPublicKey = ByteArray(91) { (it + 2).toByte() },
            proof = ByteArray(SelfMadeGuardCrypto.PROOF_SIZE) { (it + 3).toByte() },
        )

        DataOutputStream(output).use { SelfMadeGuardHandshakeCodec.writeResponse(it, response) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            SelfMadeGuardHandshakeCodec.readResponse(it)
        }

        assertTrue(decoded.accepted)
        assertEquals("welcome", decoded.message)
        assertArrayEquals(response.serverNonce, decoded.serverNonce)
        assertArrayEquals(response.serverPublicKey, decoded.serverPublicKey)
        assertArrayEquals(response.proof, decoded.proof)
    }

    @Test
    fun `rejected handshake keeps reason`() {
        val output = ByteArrayOutputStream()
        val response = SelfMadeGuardHandshakeResponse(
            accepted = false,
            message = "bad psk",
            serverNonce = ByteArray(SelfMadeGuardCrypto.NONCE_SIZE),
            serverPublicKey = ByteArray(91),
            proof = ByteArray(SelfMadeGuardCrypto.PROOF_SIZE),
        )

        DataOutputStream(output).use { SelfMadeGuardHandshakeCodec.writeResponse(it, response) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            SelfMadeGuardHandshakeCodec.readResponse(it)
        }

        assertFalse(decoded.accepted)
        assertEquals("bad psk", decoded.message)
    }

    @Test
    fun `foreign server magic is rejected`() {
        val bytes = ByteArrayOutputStream().also { out ->
            out.write("NOTGUARD!".toByteArray(Charsets.US_ASCII))
            out.write(SelfMadeGuardProtocol.VERSION)
            out.write(0)
            out.write(0)
            out.write(0)
        }.toByteArray()

        try {
            SelfMadeGuardHandshakeCodec.readResponse(DataInputStream(ByteArrayInputStream(bytes)))
            fail("Ожидался SelfMadeGuardException")
        } catch (e: SelfMadeGuardException) {
            assertTrue(e.message.orEmpty().contains("SelfMadeGuard"))
        }
    }

    private fun encodeThenDecode(frame: SelfMadeGuardFrame): SelfMadeGuardFrame {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { SelfMadeGuardFrameCodec.write(it, frame) }
        return SelfMadeGuardFrameCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))!!
    }
}
