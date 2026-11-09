package com.haruchka.testvpnapp.core.selfmadeguard.protocol

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография SelfMadeGuard/2.
 *
 * Схема:
 * 1. Клиент и сервер генерируют эфемерные ключи ECDH P-256.
 * 2. Токен из настроек используется как PSK: обе стороны доказывают его знание
 *    через HMAC-SHA256, сам токен по сети не передаётся.
 * 3. Из общего ECDH-секрета через HKDF-SHA256 выводится AES-256 ключ.
 * 4. Кадры шифруются AES-256-GCM, IV генерируется случайно для каждого кадра.
 *
 * Это учебная, но корректная с точки зрения базовых принципов схема:
 * аутентификация по PSK, прямая секретность за счёт эфемерного ECDH и
 * аутентифицированное шифрование GCM.
 */
object SelfMadeGuardCrypto {

    const val NONCE_SIZE = 16
    const val PROOF_SIZE = 32
    const val GCM_IV_SIZE = 12
    const val GCM_TAG_BITS = 128

    private const val CURVE = "secp256r1"
    private const val AES_KEY_SIZE = 32
    private val HKDF_INFO = "SelfMadeGuard/2 session key".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    /** Состояние клиента между отправкой запроса и проверкой ответа. */
    class ClientHandshake internal constructor(
        internal val keyPair: KeyPair,
        internal val mtu: Int,
        val clientNonce: ByteArray,
        val clientPublicKey: ByteArray,
        val proof: ByteArray,
    )

    fun createClientHandshake(token: String, mtu: Int): ClientHandshake {
        require(token.isNotEmpty()) { "PSK token must not be empty" }
        val keyPair = generateKeyPair()
        val nonce = randomBytes(NONCE_SIZE)
        val publicKey = keyPair.public.encoded
        val proof = hmac(
            key = token.toByteArray(Charsets.UTF_8),
            data = requestTranscript(mtu, nonce, publicKey),
        )
        return ClientHandshake(
            keyPair = keyPair,
            mtu = mtu,
            clientNonce = nonce,
            clientPublicKey = publicKey,
            proof = proof,
        )
    }

    fun createClientRequest(handshake: ClientHandshake): SelfMadeGuardHandshakeRequest =
        SelfMadeGuardHandshakeRequest(
            mtu = handshake.mtu,
            clientNonce = handshake.clientNonce,
            clientPublicKey = handshake.clientPublicKey,
            proof = handshake.proof,
        )

    /**
     * Проверяет ответ сервера и выводит общий AES-ключ.
     *
     * @throws SelfMadeGuardException если сервер отклонил подключение или не доказал
     * знание PSK.
     */
    fun establishClientSession(
        token: String,
        handshake: ClientHandshake,
        response: SelfMadeGuardHandshakeResponse,
    ): SelfMadeGuardSession {
        if (!response.accepted) {
            throw SelfMadeGuardException(
                "Server rejected connection: ${response.message.ifBlank { "no description" }}",
            )
        }

        val expectedProof = hmac(
            key = token.toByteArray(Charsets.UTF_8),
            data = responseTranscript(
                accepted = true,
                clientNonce = handshake.clientNonce,
                clientPublicKey = handshake.clientPublicKey,
                serverNonce = response.serverNonce,
                serverPublicKey = response.serverPublicKey,
            ),
        )
        if (!MessageDigest.isEqual(expectedProof, response.proof)) {
            throw SelfMadeGuardException("Server did not confirm PSK knowledge")
        }

        return deriveSessionKey(
            privateKey = handshake.keyPair.private,
            peerPublicKey = response.serverPublicKey,
            clientNonce = handshake.clientNonce,
            serverNonce = response.serverNonce,
        )
    }

    // ---------- серверная часть (для Linux/demo-сервера и тестов) ----------

    fun generateServerKeyPair(): KeyPair = generateKeyPair()

    fun verifyClientProof(token: String, request: SelfMadeGuardHandshakeRequest): Boolean {
        val expected = hmac(
            key = token.toByteArray(Charsets.UTF_8),
            data = requestTranscript(request.mtu, request.clientNonce, request.clientPublicKey),
        )
        return MessageDigest.isEqual(expected, request.proof)
    }

    fun serverResponseProof(
        token: String,
        request: SelfMadeGuardHandshakeRequest,
        accepted: Boolean,
        serverNonce: ByteArray,
        serverPublicKey: ByteArray,
    ): ByteArray = hmac(
        key = token.toByteArray(Charsets.UTF_8),
        data = responseTranscript(
            accepted = accepted,
            clientNonce = request.clientNonce,
            clientPublicKey = request.clientPublicKey,
            serverNonce = serverNonce,
            serverPublicKey = serverPublicKey,
        ),
    )

    fun deriveSessionKey(
        privateKey: PrivateKey,
        peerPublicKey: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): SelfMadeGuardSession {
        try {
            val keyFactory = KeyFactory.getInstance("EC")
            val peer = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKey))

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(peer, true)
            val sharedSecret = agreement.generateSecret()

            val key = hkdfSha256(
                ikm = sharedSecret,
                salt = clientNonce + serverNonce,
                info = HKDF_INFO,
                length = AES_KEY_SIZE,
            )
            return SelfMadeGuardSession(key)
        } catch (e: Exception) {
            throw SelfMadeGuardException("Failed to derive session key: ${e.message}", e)
        }
    }

    // ---------- публичные утилиты ----------

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    // ---------- внутренние примитивы ----------

    private fun generateKeyPair(): KeyPair {
        try {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            return generator.generateKeyPair()
        } catch (e: Exception) {
            throw SelfMadeGuardException("Failed to generate EC key: ${e.message}", e)
        }
    }

    internal fun requestTranscript(
        mtu: Int,
        clientNonce: ByteArray,
        clientPublicKey: ByteArray,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(SelfMadeGuardProtocol.MAGIC)
        write(SelfMadeGuardProtocol.VERSION)
        writeU16(mtu)
        write(clientNonce)
        writeU16(clientPublicKey.size)
        write(clientPublicKey)
    }.toByteArray()

    internal fun responseTranscript(
        accepted: Boolean,
        clientNonce: ByteArray,
        clientPublicKey: ByteArray,
        serverNonce: ByteArray,
        serverPublicKey: ByteArray,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(SelfMadeGuardProtocol.MAGIC)
        write(SelfMadeGuardProtocol.VERSION)
        write(if (accepted) SelfMadeGuardProtocol.STATUS_OK else 1)
        write(clientNonce)
        writeU16(clientPublicKey.size)
        write(clientPublicKey)
        write(serverNonce)
        writeU16(serverPublicKey.size)
        write(serverPublicKey)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prk = hmac(salt, ikm)
        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1

        while (output.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter++
        }

        return output.toByteArray().copyOf(length)
    }
}

/**
 * Симметричный сессионный ключ SelfMadeGuard/2.
 *
 * Каждый кадр шифруется отдельным случайным IV. Тип кадра передаётся в
 * associated data, поэтому подмена типа приводит к ошибке аутентификации GCM.
 */
class SelfMadeGuardSession internal constructor(private val key: ByteArray) {

    init {
        require(key.size == 32) { "AES-256 key expected" }
    }

    fun encrypt(frameType: Int, payload: ByteArray): ByteArray {
        val iv = SelfMadeGuardCrypto.randomBytes(SelfMadeGuardCrypto.GCM_IV_SIZE)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(SelfMadeGuardCrypto.GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(byteArrayOf(frameType.toByte()))
            val ciphertext = cipher.doFinal(payload)
            return iv + ciphertext
        } catch (e: Exception) {
            throw SelfMadeGuardException("Failed to encrypt frame: ${e.message}", e)
        }
    }

    fun decrypt(frameType: Int, encryptedPayload: ByteArray): ByteArray {
        if (encryptedPayload.size < SelfMadeGuardCrypto.GCM_IV_SIZE + SelfMadeGuardCrypto.GCM_TAG_BITS / 8) {
            throw SelfMadeGuardException("Encrypted frame is too short")
        }
        val iv = encryptedPayload.copyOfRange(0, SelfMadeGuardCrypto.GCM_IV_SIZE)
        val ciphertext = encryptedPayload.copyOfRange(SelfMadeGuardCrypto.GCM_IV_SIZE, encryptedPayload.size)

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(SelfMadeGuardCrypto.GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(byteArrayOf(frameType.toByte()))
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SelfMadeGuardException("Failed to decrypt frame: ${e.message}", e)
        }
    }

    fun encryptFrame(frame: SelfMadeGuardFrame): SelfMadeGuardFrame = SelfMadeGuardFrame(
        type = frame.type,
        payload = encrypt(frame.type.code, frame.payload),
    )

    fun decryptFrame(frame: SelfMadeGuardFrame): SelfMadeGuardFrame = SelfMadeGuardFrame(
        type = frame.type,
        payload = decrypt(frame.type.code, frame.payload),
    )
}
