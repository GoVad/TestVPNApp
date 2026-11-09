package com.haruchka.testvpnapp.core.guardwire.protocol

import com.haruchka.testvpnapp.core.model.VpnServerConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Устанавливает TCP-соединение с GuardWire-сервером и выполняет рукопожатие.
 *
 * [protectSocket] должен вызывать `VpnService.protect(socket)`, иначе пакеты
 * самого туннеля попадут в созданный TUN и соединение зациклится.
 */
class GuardWireClient(
    private val config: VpnServerConfig,
    private val protectSocket: (Socket) -> Boolean = { true },
) {

    /**
     * Блокирующий вызов. Должен выполняться на IO-диспетчере.
     *
     * @throws GuardWireException если сокет не защищён, сервер недоступен,
     * рукопожатие не прошло или версия протокола не совпала.
     */
    fun connect(): GuardWireConnection {
        if (config.token.isEmpty()) {
            throw GuardWireException("PSK token is not set: GuardWire/2 cannot start without it")
        }

        val socket = Socket()
        try {
            socket.tcpNoDelay = true

            if (!protectSocket(socket)) {
                throw GuardWireException("VpnService.protect() failed to protect the tunnel socket")
            }

            socket.connect(
                InetSocketAddress(config.host, config.port),
                GuardWireProtocol.CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = GuardWireProtocol.HANDSHAKE_TIMEOUT_MS

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val handshake = GuardWireCrypto.createClientHandshake(
                token = config.token,
                mtu = config.mtu,
            )
            GuardWireHandshakeCodec.writeRequest(
                output = output,
                request = GuardWireCrypto.createClientRequest(handshake),
            )
            val response = GuardWireHandshakeCodec.readResponse(input)

            val session = GuardWireCrypto.establishClientSession(
                token = config.token,
                handshake = handshake,
                response = response,
            )

            // Если сервер молчит дольше четырёх интервалов keep-alive, считаем туннель мёртвым.
            val readTimeoutMs = config.keepAliveSeconds.coerceAtLeast(5) * 4 * 1_000
            socket.soTimeout = readTimeoutMs

            return GuardWireConnection(socket, input, output, session)
        } catch (e: Exception) {
            runCatching { socket.close() }
            if (e is GuardWireException) throw e

            val reason = when (e) {
                is java.net.ConnectException -> "server unavailable (connection refused)"
                is java.net.UnknownHostException -> "failed to resolve host name"
                is java.net.SocketTimeoutException ->
                    if (socket.isConnected) "server did not respond to handshake" else "connection timeout"
                else -> e.message ?: "unknown error"
            }
            throw GuardWireException(
                message = "Failed to connect to ${config.endpoint}: $reason",
                cause = e,
            )
        }
    }
}
