package com.haruchka.testvpnapp.core.guardwire.protocol

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

class GuardWireCodecTest {

    @Test
    fun `data frame survives encode decode`() {
        val frame = GuardWireFrame(
            type = GuardWireFrameType.DATA,
            payload = byteArrayOf(0x45, 0x00, 0x00, 0x14, 0x7F, 0x01),
        )

        assertEquals(frame, encodeThenDecode(frame))
    }

    @Test
    fun `empty keep alive frame survives encode decode`() {
        val frame = GuardWireFrame(GuardWireFrameType.KEEP_ALIVE, ByteArray(0))

        assertEquals(frame, encodeThenDecode(frame))
    }

    @Test
    fun `read returns null on clean eof`() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))

        assertNull(GuardWireFrameCodec.read(input))
    }

    @Test
    fun `read fails on truncated payload`() {
        val bytes = ByteArrayOutputStream().also { out ->
            out.write(GuardWireFrameType.DATA.code)
            out.write(0)
            out.write(4)
            out.write(byteArrayOf(1, 2))
        }.toByteArray()

        try {
            GuardWireFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
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
            GuardWireFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
            fail("Ожидался GuardWireException")
        } catch (e: GuardWireException) {
            assertTrue(e.message.orEmpty().contains("Unknown GuardWire frame type"))
        }
    }

    @Test
    fun `handshake request round trip`() {
        val output = ByteArrayOutputStream()
        val request = GuardWireHandshakeRequest(
            mtu = 1_400,
            clientNonce = ByteArray(GuardWireCrypto.NONCE_SIZE) { it.toByte() },
            clientPublicKey = ByteArray(91) { (it + 10).toByte() },
            proof = ByteArray(GuardWireCrypto.PROOF_SIZE) { (it + 20).toByte() },
        )

        DataOutputStream(output).use { GuardWireHandshakeCodec.writeRequest(it, request) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            GuardWireHandshakeCodec.readRequest(it)
        }

        assertEquals(request.mtu, decoded.mtu)
        assertArrayEquals(request.clientNonce, decoded.clientNonce)
        assertArrayEquals(request.clientPublicKey, decoded.clientPublicKey)
        assertArrayEquals(request.proof, decoded.proof)
    }

    @Test
    fun `handshake response round trip`() {
        val output = ByteArrayOutputStream()
        val response = GuardWireHandshakeResponse(
            accepted = true,
            message = "welcome",
            serverNonce = ByteArray(GuardWireCrypto.NONCE_SIZE) { (it + 1).toByte() },
            serverPublicKey = ByteArray(91) { (it + 2).toByte() },
            proof = ByteArray(GuardWireCrypto.PROOF_SIZE) { (it + 3).toByte() },
        )

        DataOutputStream(output).use { GuardWireHandshakeCodec.writeResponse(it, response) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            GuardWireHandshakeCodec.readResponse(it)
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
        val response = GuardWireHandshakeResponse(
            accepted = false,
            message = "bad psk",
            serverNonce = ByteArray(GuardWireCrypto.NONCE_SIZE),
            serverPublicKey = ByteArray(91),
            proof = ByteArray(GuardWireCrypto.PROOF_SIZE),
        )

        DataOutputStream(output).use { GuardWireHandshakeCodec.writeResponse(it, response) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            GuardWireHandshakeCodec.readResponse(it)
        }

        assertFalse(decoded.accepted)
        assertEquals("bad psk", decoded.message)
    }

    @Test
    fun `foreign server magic is rejected`() {
        val bytes = ByteArrayOutputStream().also { out ->
            out.write("NOTGUARD!".toByteArray(Charsets.US_ASCII))
            out.write(GuardWireProtocol.VERSION)
            out.write(0)
            out.write(0)
            out.write(0)
        }.toByteArray()

        try {
            GuardWireHandshakeCodec.readResponse(DataInputStream(ByteArrayInputStream(bytes)))
            fail("Ожидался GuardWireException")
        } catch (e: GuardWireException) {
            assertTrue(e.message.orEmpty().contains("GuardWire"))
        }
    }

    private fun encodeThenDecode(frame: GuardWireFrame): GuardWireFrame {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { GuardWireFrameCodec.write(it, frame) }
        return GuardWireFrameCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))!!
    }
}
