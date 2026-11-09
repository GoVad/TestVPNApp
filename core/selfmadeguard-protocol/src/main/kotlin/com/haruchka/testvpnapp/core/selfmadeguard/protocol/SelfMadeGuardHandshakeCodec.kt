package com.haruchka.testvpnapp.core.selfmadeguard.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Запрос клиента SelfMadeGuard/2.
 *
 * Токен (PSK) по сети не передаётся: клиент доказывает его знание полем [proof],
 * которое является HMAC-SHA256 от транскрипта рукопожатия.
 */
class SelfMadeGuardHandshakeRequest(
    val mtu: Int,
    val clientNonce: ByteArray,
    val clientPublicKey: ByteArray,
    val proof: ByteArray,
)

/** Ответ сервера SelfMadeGuard/2. */
class SelfMadeGuardHandshakeResponse(
    val accepted: Boolean,
    val message: String,
    val serverNonce: ByteArray,
    val serverPublicKey: ByteArray,
    val proof: ByteArray,
)

/**
 * Формат рукопожатия SelfMadeGuard/2.
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
object SelfMadeGuardHandshakeCodec {

    private const val MAX_PUBLIC_KEY_SIZE = 1_024
    private const val MAX_MESSAGE_SIZE = 4_096

    fun writeRequest(output: DataOutputStream, request: SelfMadeGuardHandshakeRequest) {
        require(request.clientNonce.size == SelfMadeGuardCrypto.NONCE_SIZE) {
            "Invalid clientNonce size"
        }
        require(request.proof.size == SelfMadeGuardCrypto.PROOF_SIZE) {
            "Invalid proof size"
        }
        require(request.clientPublicKey.size in 1..MAX_PUBLIC_KEY_SIZE) {
            "Invalid public key size"
        }
        require(request.mtu in 576..SelfMadeGuardProtocol.MAX_PAYLOAD_SIZE) {
            "Invalid MTU: ${request.mtu}"
        }

        output.write(SelfMadeGuardProtocol.MAGIC)
        output.writeByte(SelfMadeGuardProtocol.VERSION)
        output.writeShort(request.mtu)
        output.write(request.clientNonce)
        output.writeShort(request.clientPublicKey.size)
        output.write(request.clientPublicKey)
        output.write(request.proof)
        output.flush()
    }

    fun readRequest(input: DataInputStream): SelfMadeGuardHandshakeRequest {
        readAndCheckMagic(input)
        requireVersion(input.readUnsignedByte())

        val mtu = input.readUnsignedShort()
        val clientNonce = ByteArray(SelfMadeGuardCrypto.NONCE_SIZE)
        input.readFully(clientNonce)

        val publicKeySize = input.readUnsignedShort()
        if (publicKeySize !in 1..MAX_PUBLIC_KEY_SIZE) {
            throw SelfMadeGuardException("Invalid public key size: $publicKeySize")
        }
        val clientPublicKey = ByteArray(publicKeySize)
        input.readFully(clientPublicKey)

        val proof = ByteArray(SelfMadeGuardCrypto.PROOF_SIZE)
        input.readFully(proof)

        return SelfMadeGuardHandshakeRequest(
            mtu = mtu,
            clientNonce = clientNonce,
            clientPublicKey = clientPublicKey,
            proof = proof,
        )
    }

    fun writeResponse(output: DataOutputStream, response: SelfMadeGuardHandshakeResponse) {
        val messageBytes = response.message.toByteArray(Charsets.UTF_8)
        require(messageBytes.size <= MAX_MESSAGE_SIZE) { "Server message is too long" }
        require(response.serverNonce.size == SelfMadeGuardCrypto.NONCE_SIZE) {
            "Invalid serverNonce size"
        }
        require(response.proof.size == SelfMadeGuardCrypto.PROOF_SIZE) {
            "Invalid proof size"
        }
        require(response.serverPublicKey.size in 1..MAX_PUBLIC_KEY_SIZE) {
            "Invalid public key size"
        }

        output.write(SelfMadeGuardProtocol.MAGIC)
        output.writeByte(SelfMadeGuardProtocol.VERSION)
        output.writeByte(if (response.accepted) SelfMadeGuardProtocol.STATUS_OK else 1)
        output.writeShort(messageBytes.size)
        output.write(messageBytes)
        output.write(response.serverNonce)
        output.writeShort(response.serverPublicKey.size)
        output.write(response.serverPublicKey)
        output.write(response.proof)
        output.flush()
    }

    fun readResponse(input: DataInputStream): SelfMadeGuardHandshakeResponse {
        readAndCheckMagic(input)
        requireVersion(input.readUnsignedByte())

        val status = input.readUnsignedByte()

        val messageLength = input.readUnsignedShort()
        if (messageLength > MAX_MESSAGE_SIZE) {
            throw SelfMadeGuardException("Server message is too long: $messageLength")
        }
        val messageBytes = ByteArray(messageLength)
        input.readFully(messageBytes)

        val serverNonce = ByteArray(SelfMadeGuardCrypto.NONCE_SIZE)
        input.readFully(serverNonce)

        val publicKeySize = input.readUnsignedShort()
        if (publicKeySize !in 1..MAX_PUBLIC_KEY_SIZE) {
            throw SelfMadeGuardException("Invalid public key size: $publicKeySize")
        }
        val serverPublicKey = ByteArray(publicKeySize)
        input.readFully(serverPublicKey)

        val proof = ByteArray(SelfMadeGuardCrypto.PROOF_SIZE)
        input.readFully(proof)

        return SelfMadeGuardHandshakeResponse(
            accepted = status == SelfMadeGuardProtocol.STATUS_OK,
            message = messageBytes.toString(Charsets.UTF_8),
            serverNonce = serverNonce,
            serverPublicKey = serverPublicKey,
            proof = proof,
        )
    }

    private fun readAndCheckMagic(input: DataInputStream) {
        val magic = ByteArray(SelfMadeGuardProtocol.MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(SelfMadeGuardProtocol.MAGIC)) {
            throw SelfMadeGuardException("Server is not a SelfMadeGuard server")
        }
    }

    private fun requireVersion(version: Int) {
        if (version != SelfMadeGuardProtocol.VERSION) {
            throw SelfMadeGuardException(
                "Unsupported SelfMadeGuard version: $version, expected ${SelfMadeGuardProtocol.VERSION}",
            )
        }
    }
}
