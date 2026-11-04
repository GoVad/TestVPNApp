package com.haruchka.testvpnapp.core.protocol

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
        val socket = Socket()
        try {
            socket.tcpNoDelay = true

            if (!protectSocket(socket)) {
                throw GuardWireException("VpnService.protect() не смог защитить сокет туннеля")
            }

            socket.connect(
                InetSocketAddress(config.host, config.port),
                GuardWireProtocol.CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = GuardWireProtocol.HANDSHAKE_TIMEOUT_MS

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            GuardWireHandshakeCodec.writeRequest(
                output = output,
                request = GuardWireHandshakeRequest(token = config.token, mtu = config.mtu),
            )
            val response = GuardWireHandshakeCodec.readResponse(input)
            if (!response.accepted) {
                val reason = response.message.ifBlank { "без описания" }
                throw GuardWireException("Сервер отклонил подключение: $reason")
            }

            // Если сервер молчит дольше четырёх интервалов keep-alive, считаем туннель мёртвым.
            val readTimeoutMs = config.keepAliveSeconds.coerceAtLeast(5) * 4 * 1_000
            socket.soTimeout = readTimeoutMs

            return GuardWireConnection(socket, input, output)
        } catch (e: Exception) {
            runCatching { socket.close() }
            if (e is GuardWireException) throw e

            val reason = when (e) {
                is java.net.ConnectException -> "сервер недоступен (connection refused)"
                is java.net.UnknownHostException -> "не удалось разрешить имя хоста"
                is java.net.SocketTimeoutException ->
                    if (socket.isConnected) "сервер не ответил на рукопожатие" else "таймаут подключения"
                else -> e.message ?: "неизвестная ошибка"
            }
            throw GuardWireException(
                message = "Не удалось подключиться к ${config.endpoint}: $reason",
                cause = e,
            )
        }
    }
}
