package com.haruchka.testvpnapp.core.protocol

import com.haruchka.testvpnapp.core.model.VpnServerConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GuardWireClientTest {

    @Test
    fun `client performs handshake and exchanges data ping pong`() {
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val payload = byteArrayOf(0x45, 0x00, 0x00, 0x14, 0x01, 0x02)

        val serverTask = executor.submit {
            serverSocket.accept().use { socket ->
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                val request = GuardWireHandshakeCodec.readRequest(input)
                assertEquals("demo", request.token)
                assertEquals(1_400, request.mtu)

                GuardWireHandshakeCodec.writeResponse(
                    output,
                    GuardWireHandshakeResponse(accepted = true, message = "welcome"),
                )

                val dataFrame = GuardWireFrameCodec.read(input)!!
                assertEquals(GuardWireFrameType.DATA, dataFrame.type)
                assertArrayEquals(payload, dataFrame.payload)

                GuardWireFrameCodec.write(
                    output,
                    GuardWireFrame(GuardWireFrameType.PING, ByteArray(0)),
                )

                val pong = GuardWireFrameCodec.read(input)!!
                assertEquals(GuardWireFrameType.PONG, pong.type)
            }
        }

        try {
            val config = VpnServerConfig(
                host = "127.0.0.1",
                port = serverSocket.localPort,
                token = "demo",
                keepAliveSeconds = 5,
            )

            GuardWireClient(config).connect().use { connection ->
                connection.sendData(payload, payload.size)

                val ping = connection.receive()
                assertEquals(GuardWireFrameType.PING, ping.type)
                connection.sendPong()
            }

            serverTask.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }
}
