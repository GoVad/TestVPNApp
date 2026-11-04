package com.haruchka.testvpnapp.core.protocol

import com.haruchka.testvpnapp.core.model.VpnServerConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `client performs encrypted handshake and exchanges data ping pong`() {
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val token = "demo-psk"
        val payload = byteArrayOf(0x45, 0x00, 0x00, 0x14, 0x01, 0x02)

        val serverTask = executor.submit {
            serverSocket.accept().use { socket ->
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                val request = GuardWireHandshakeCodec.readRequest(input)
                assertTrue(GuardWireCrypto.verifyClientProof(token, request))

                val serverKeys = GuardWireCrypto.generateServerKeyPair()
                val serverNonce = GuardWireCrypto.randomBytes(GuardWireCrypto.NONCE_SIZE)
                val serverPublicKey = serverKeys.public.encoded
                val serverSession = GuardWireCrypto.deriveSessionKey(
                    privateKey = serverKeys.private,
                    peerPublicKey = request.clientPublicKey,
                    clientNonce = request.clientNonce,
                    serverNonce = serverNonce,
                )

                GuardWireHandshakeCodec.writeResponse(
                    output,
                    GuardWireHandshakeResponse(
                        accepted = true,
                        message = "welcome",
                        serverNonce = serverNonce,
                        serverPublicKey = serverPublicKey,
                        proof = GuardWireCrypto.serverResponseProof(
                            token = token,
                            request = request,
                            accepted = true,
                            serverNonce = serverNonce,
                            serverPublicKey = serverPublicKey,
                        ),
                    ),
                )

                val encryptedData = GuardWireFrameCodec.read(input)!!
                assertEquals(GuardWireFrameType.DATA, encryptedData.type)
                assertArrayEquals(
                    payload,
                    serverSession.decrypt(encryptedData.type.code, encryptedData.payload),
                )

                GuardWireFrameCodec.write(
                    output,
                    GuardWireFrame(
                        GuardWireFrameType.PING,
                        serverSession.encrypt(GuardWireFrameType.PING.code, ByteArray(0)),
                    ),
                )

                val encryptedPong = GuardWireFrameCodec.read(input)!!
                assertEquals(GuardWireFrameType.PONG, encryptedPong.type)
                assertArrayEquals(
                    ByteArray(0),
                    serverSession.decrypt(encryptedPong.type.code, encryptedPong.payload),
                )
            }
        }

        try {
            val config = VpnServerConfig(
                host = "127.0.0.1",
                port = serverSocket.localPort,
                token = token,
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

    @Test
    fun `wrong psk is rejected`() {
        val serverSocket = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()

        val serverTask = executor.submit {
            serverSocket.accept().use { socket ->
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val request = GuardWireHandshakeCodec.readRequest(input)
                val serverKeys = GuardWireCrypto.generateServerKeyPair()
                val serverNonce = GuardWireCrypto.randomBytes(GuardWireCrypto.NONCE_SIZE)
                val serverPublicKey = serverKeys.public.encoded
                val accepted = GuardWireCrypto.verifyClientProof("correct-token", request)

                GuardWireHandshakeCodec.writeResponse(
                    output,
                    GuardWireHandshakeResponse(
                        accepted = accepted,
                        message = "bad psk",
                        serverNonce = serverNonce,
                        serverPublicKey = serverPublicKey,
                        proof = GuardWireCrypto.serverResponseProof(
                            token = "correct-token",
                            request = request,
                            accepted = accepted,
                            serverNonce = serverNonce,
                            serverPublicKey = serverPublicKey,
                        ),
                    ),
                )
            }
        }

        try {
            val config = VpnServerConfig(
                host = "127.0.0.1",
                port = serverSocket.localPort,
                token = "wrong-token",
                keepAliveSeconds = 5,
            )

            try {
                GuardWireClient(config).connect().use { }
                throw AssertionError("Ожидался отказ из-за неверного PSK")
            } catch (e: GuardWireException) {
                assertTrue(e.message.orEmpty().contains("отклонил"))
            }

            serverTask.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }
}
