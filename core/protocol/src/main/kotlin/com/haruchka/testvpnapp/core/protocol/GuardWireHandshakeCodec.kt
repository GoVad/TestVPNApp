package com.haruchka.testvpnapp.core.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Запрос клиента GuardWire/2.
 *
 * Токен (PSK) по сети не передаётся: клиент доказывает его знание полем [proof],
 * которое является HMAC-SHA256 от транскрипта рукопожатия.
 */
class GuardWireHandshakeRequest(
    val mtu: Int,
    val clientNonce: ByteArray,
    val clientPublicKey: ByteArray,
    val proof: ByteArray,
)

/** Ответ сервера GuardWire/2. */
class GuardWireHandshakeResponse(
    val accepted: Boolean,
    val message: String,
    val serverNonce: ByteArray,
    val serverPublicKey: ByteArray,
    val proof: ByteArray,
)

/**
 * Формат рукопожатия GuardWire/2.
 *
 * Клиент:
 * ```
 * magic (9) | version=2 (1) | mtu (2) | clientNonce (16) |
 * clientPubLen (2) | clientPub (N) | proof (32)
 * ```
 *
 * Сервер:
 * ```
 * magic (9) | version=2 (1) | status (1) | messageLen (2) | message (N) |
 * serverNonce (16) | serverPubLen (2) | serverPub (N) | proof (32)
 * ```
 *
 * `status = 0` — успех, любое другое значение — отказ.
 */
object GuardWireHandshakeCodec {

    private const val MAX_PUBLIC_KEY_SIZE = 1_024
    private const val MAX_MESSAGE_SIZE = 4_096

    fun writeRequest(output: DataOutputStream, request: GuardWireHandshakeRequest) {
        require(request.clientNonce.size == GuardWireCrypto.NONCE_SIZE) {
            "Некорректный размер clientNonce"
        }
        require(request.proof.size == GuardWireCrypto.PROOF_SIZE) {
            "Некорректный размер proof"
        }
        require(request.clientPublicKey.size in 1..MAX_PUBLIC_KEY_SIZE) {
            "Некорректный размер публичного ключа"
        }
        require(request.mtu in 576..GuardWireProtocol.MAX_PAYLOAD_SIZE) {
            "Некорректный MTU: ${request.mtu}"
        }

        output.write(GuardWireProtocol.MAGIC)
        output.writeByte(GuardWireProtocol.VERSION)
        output.writeShort(request.mtu)
        output.write(request.clientNonce)
        output.writeShort(request.clientPublicKey.size)
        output.write(request.clientPublicKey)
        output.write(request.proof)
        output.flush()
    }

    fun readRequest(input: DataInputStream): GuardWireHandshakeRequest {
        readAndCheckMagic(input)
        requireVersion(input.readUnsignedByte())

        val mtu = input.readUnsignedShort()
        val clientNonce = ByteArray(GuardWireCrypto.NONCE_SIZE)
        input.readFully(clientNonce)

        val publicKeySize = input.readUnsignedShort()
        if (publicKeySize !in 1..MAX_PUBLIC_KEY_SIZE) {
            throw GuardWireException("Некорректный размер публичного ключа: $publicKeySize")
        }
        val clientPublicKey = ByteArray(publicKeySize)
        input.readFully(clientPublicKey)

        val proof = ByteArray(GuardWireCrypto.PROOF_SIZE)
        input.readFully(proof)

        return GuardWireHandshakeRequest(
            mtu = mtu,
            clientNonce = clientNonce,
            clientPublicKey = clientPublicKey,
            proof = proof,
        )
    }

    fun writeResponse(output: DataOutputStream, response: GuardWireHandshakeResponse) {
        val messageBytes = response.message.toByteArray(Charsets.UTF_8)
        require(messageBytes.size <= MAX_MESSAGE_SIZE) { "Сообщение сервера слишком длинное" }
        require(response.serverNonce.size == GuardWireCrypto.NONCE_SIZE) {
            "Некорректный размер serverNonce"
        }
        require(response.proof.size == GuardWireCrypto.PROOF_SIZE) {
            "Некорректный размер proof"
        }
        require(response.serverPublicKey.size in 1..MAX_PUBLIC_KEY_SIZE) {
            "Некорректный размер публичного ключа"
        }

        output.write(GuardWireProtocol.MAGIC)
        output.writeByte(GuardWireProtocol.VERSION)
        output.writeByte(if (response.accepted) GuardWireProtocol.STATUS_OK else 1)
        output.writeShort(messageBytes.size)
        output.write(messageBytes)
        output.write(response.serverNonce)
        output.writeShort(response.serverPublicKey.size)
        output.write(response.serverPublicKey)
        output.write(response.proof)
        output.flush()
    }

    fun readResponse(input: DataInputStream): GuardWireHandshakeResponse {
        readAndCheckMagic(input)
        requireVersion(input.readUnsignedByte())

        val status = input.readUnsignedByte()

        val messageLength = input.readUnsignedShort()
        if (messageLength > MAX_MESSAGE_SIZE) {
            throw GuardWireException("Слишком длинное сообщение сервера: $messageLength")
        }
        val messageBytes = ByteArray(messageLength)
        input.readFully(messageBytes)

        val serverNonce = ByteArray(GuardWireCrypto.NONCE_SIZE)
        input.readFully(serverNonce)

        val publicKeySize = input.readUnsignedShort()
        if (publicKeySize !in 1..MAX_PUBLIC_KEY_SIZE) {
            throw GuardWireException("Некорректный размер публичного ключа: $publicKeySize")
        }
        val serverPublicKey = ByteArray(publicKeySize)
        input.readFully(serverPublicKey)

        val proof = ByteArray(GuardWireCrypto.PROOF_SIZE)
        input.readFully(proof)

        return GuardWireHandshakeResponse(
            accepted = status == GuardWireProtocol.STATUS_OK,
            message = messageBytes.toString(Charsets.UTF_8),
            serverNonce = serverNonce,
            serverPublicKey = serverPublicKey,
            proof = proof,
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
