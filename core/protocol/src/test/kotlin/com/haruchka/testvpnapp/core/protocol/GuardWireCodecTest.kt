package com.haruchka.testvpnapp.core.protocol

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
            assertTrue(e.message.orEmpty().contains("Неизвестный тип"))
        }
    }

    @Test
    fun `handshake request round trip`() {
        val output = ByteArrayOutputStream()
        val request = GuardWireHandshakeRequest(token = "demo-token", mtu = 1400)

        DataOutputStream(output).use { GuardWireHandshakeCodec.writeRequest(it, request) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            GuardWireHandshakeCodec.readRequest(it)
        }

        assertEquals(request, decoded)
    }

    @Test
    fun `handshake response round trip`() {
        val output = ByteArrayOutputStream()
        val response = GuardWireHandshakeResponse(accepted = true, message = "welcome")

        DataOutputStream(output).use { GuardWireHandshakeCodec.writeResponse(it, response) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            GuardWireHandshakeCodec.readResponse(it)
        }

        assertTrue(decoded.accepted)
        assertEquals("welcome", decoded.message)
    }

    @Test
    fun `rejected handshake keeps reason`() {
        val output = ByteArrayOutputStream()
        val response = GuardWireHandshakeResponse(accepted = false, message = "bad token")

        DataOutputStream(output).use { GuardWireHandshakeCodec.writeResponse(it, response) }
        val decoded = DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            GuardWireHandshakeCodec.readResponse(it)
        }

        assertFalse(decoded.accepted)
        assertEquals("bad token", decoded.message)
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
