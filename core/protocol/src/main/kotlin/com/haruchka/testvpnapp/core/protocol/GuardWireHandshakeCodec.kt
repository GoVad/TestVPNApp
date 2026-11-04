package com.haruchka.testvpnapp.core.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/** Первое сообщение клиента после установки TCP-соединения. */
data class GuardWireHandshakeRequest(
    val token: String,
    val mtu: Int,
)

/** Ответ сервера на рукопожатие. */
data class GuardWireHandshakeResponse(
    val accepted: Boolean,
    val message: String,
)

/**
 * Рукопожатие GuardWire.
 *
 * Клиент:
 * ```
 * magic (9 байт) | version (1) | token length (2) | token (N) | mtu (2)
 * ```
 *
 * Сервер:
 * ```
 * magic (9 байт) | version (1) | status (1) | message length (2) | message (N)
 * ```
 *
 * `status = 0` означает успех, любое другое значение — отказ.
 */
object GuardWireHandshakeCodec {

    fun writeRequest(output: DataOutputStream, request: GuardWireHandshakeRequest) {
        val tokenBytes = request.token.toByteArray(Charsets.UTF_8)
        require(tokenBytes.size <= GuardWireProtocol.MAX_PAYLOAD_SIZE) {
            "Токен слишком длинный"
        }
        require(request.mtu in 576..GuardWireProtocol.MAX_PAYLOAD_SIZE) {
            "Некорректный MTU: ${request.mtu}"
        }

        output.write(GuardWireProtocol.MAGIC)
        output.writeByte(GuardWireProtocol.VERSION)
        output.writeShort(tokenBytes.size)
        output.write(tokenBytes)
        output.writeShort(request.mtu)
        output.flush()
    }

    fun readRequest(input: DataInputStream): GuardWireHandshakeRequest {
        readAndCheckMagic(input)
        val version = input.readUnsignedByte()
        requireVersion(version)

        val tokenLength = input.readUnsignedShort()
        val tokenBytes = ByteArray(tokenLength)
        input.readFully(tokenBytes)

        val mtu = input.readUnsignedShort()
        return GuardWireHandshakeRequest(
            token = tokenBytes.toString(Charsets.UTF_8),
            mtu = mtu,
        )
    }

    fun writeResponse(output: DataOutputStream, response: GuardWireHandshakeResponse) {
        val messageBytes = response.message.toByteArray(Charsets.UTF_8)
        require(messageBytes.size <= GuardWireProtocol.MAX_PAYLOAD_SIZE) {
            "Сообщение сервера слишком длинное"
        }

        output.write(GuardWireProtocol.MAGIC)
        output.writeByte(GuardWireProtocol.VERSION)
        output.writeByte(if (response.accepted) GuardWireProtocol.STATUS_OK else 1)
        output.writeShort(messageBytes.size)
        output.write(messageBytes)
        output.flush()
    }

    fun readResponse(input: DataInputStream): GuardWireHandshakeResponse {
        readAndCheckMagic(input)
        val version = input.readUnsignedByte()
        requireVersion(version)

        val status = input.readUnsignedByte()
        val messageLength = input.readUnsignedShort()
        val messageBytes = ByteArray(messageLength)
        input.readFully(messageBytes)

        return GuardWireHandshakeResponse(
            accepted = status == GuardWireProtocol.STATUS_OK,
            message = messageBytes.toString(Charsets.UTF_8),
        )
    }

    private fun readAndCheckMagic(input: DataInputStream) {
        val magic = ByteArray(GuardWireProtocol.MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(GuardWireProtocol.MAGIC)) {
            throw GuardWireException("Сервер не является GuardWire-сервером")
        }
    }

    private fun requireVersion(version: Int) {
        if (version != GuardWireProtocol.VERSION) {
            throw GuardWireException(
                "Неподдерживаемая версия GuardWire: $version, ожидается ${GuardWireProtocol.VERSION}",
            )
        }
    }
}
