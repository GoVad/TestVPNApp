package com.haruchka.testvpnapp.core.selfmadeguard.protocol

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

class SelfMadeGuardClientTest {

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

                val request = SelfMadeGuardHandshakeCodec.readRequest(input)
                assertTrue(SelfMadeGuardCrypto.verifyClientProof(token, request))

                val serverKeys = SelfMadeGuardCrypto.generateServerKeyPair()
                val serverNonce = SelfMadeGuardCrypto.randomBytes(SelfMadeGuardCrypto.NONCE_SIZE)
                val serverPublicKey = serverKeys.public.encoded
                val serverSession = SelfMadeGuardCrypto.deriveSessionKey(
                    privateKey = serverKeys.private,
                    peerPublicKey = request.clientPublicKey,
                    clientNonce = request.clientNonce,
                    serverNonce = serverNonce,
                )

                SelfMadeGuardHandshakeCodec.writeResponse(
                    output,
                    SelfMadeGuardHandshakeResponse(
                        accepted = true,
                        message = "welcome",
                        serverNonce = serverNonce,
                        serverPublicKey = serverPublicKey,
                        proof = SelfMadeGuardCrypto.serverResponseProof(
                            token = token,
                            request = request,
                            accepted = true,
                            serverNonce = serverNonce,
                            serverPublicKey = serverPublicKey,
                        ),
                    ),
                )

                val encryptedData = SelfMadeGuardFrameCodec.read(input)!!
                assertEquals(SelfMadeGuardFrameType.DATA, encryptedData.type)
                assertArrayEquals(
                    payload,
                    serverSession.decrypt(encryptedData.type.code, encryptedData.payload),
                )

                SelfMadeGuardFrameCodec.write(
                    output,
                    SelfMadeGuardFrame(
                        SelfMadeGuardFrameType.PING,
                        serverSession.encrypt(SelfMadeGuardFrameType.PING.code, ByteArray(0)),
                    ),
                )

                val encryptedPong = SelfMadeGuardFrameCodec.read(input)!!
                assertEquals(SelfMadeGuardFrameType.PONG, encryptedPong.type)
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

            SelfMadeGuardClient(config).connect().use { connection ->
                connection.sendData(payload, payload.size)

                val ping = connection.receive()
                assertEquals(SelfMadeGuardFrameType.PING, ping.type)
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
                val request = SelfMadeGuardHandshakeCodec.readRequest(input)
                val serverKeys = SelfMadeGuardCrypto.generateServerKeyPair()
                val serverNonce = SelfMadeGuardCrypto.randomBytes(SelfMadeGuardCrypto.NONCE_SIZE)
                val serverPublicKey = serverKeys.public.encoded
                val accepted = SelfMadeGuardCrypto.verifyClientProof("correct-token", request)

                SelfMadeGuardHandshakeCodec.writeResponse(
                    output,
                    SelfMadeGuardHandshakeResponse(
                        accepted = accepted,
                        message = "bad psk",
                        serverNonce = serverNonce,
                        serverPublicKey = serverPublicKey,
                        proof = SelfMadeGuardCrypto.serverResponseProof(
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
                SelfMadeGuardClient(config).connect().use { }
                throw AssertionError("Ожидался отказ из-за неверного PSK")
            } catch (e: SelfMadeGuardException) {
                assertTrue(e.message.orEmpty().contains("rejected"))
            }

            serverTask.get(5, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }
}
