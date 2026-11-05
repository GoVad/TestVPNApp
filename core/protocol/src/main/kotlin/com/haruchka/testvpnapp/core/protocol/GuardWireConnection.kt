package com.haruchka.testvpnapp.core.protocol

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Установленное GuardWire-соединение.
 *
 * Класс намеренно блокирующий: его вызывают из IO-корутин VPN-сервиса.
 * Запись синхронизирована, потому что keep-alive и данные отправляются
 * из разных корутин. Payload кадров шифруется сессионным AES-GCM ключом.
 */
class GuardWireConnection internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val session: GuardWireSession,
) : Closeable {

    private val writeLock = Any()

    /** Отправляет один IP-пакет как зашифрованный DATA-кадр. */
    fun sendData(packet: ByteArray, length: Int) {
        require(length in 0..packet.size) { "Invalid packet length: $length" }
        val payload = if (length == packet.size) packet else packet.copyOf(length)
        writeEncryptedFrame(GuardWireFrameType.DATA.code, payload)
    }

    fun sendKeepAlive() {
        writeEncryptedFrame(GuardWireFrameType.KEEP_ALIVE.code, EMPTY)
    }

    fun sendPing() {
        writeEncryptedFrame(GuardWireFrameType.PING.code, EMPTY)
    }

    fun sendPong() {
        writeEncryptedFrame(GuardWireFrameType.PONG.code, EMPTY)
    }

    fun sendDisconnect() {
        writeEncryptedFrame(GuardWireFrameType.DISCONNECT.code, EMPTY)
    }

    /** Блокирующее чтение и расшифровка следующего кадра. */
    fun receive(): GuardWireFrame {
        val frame = GuardWireFrameCodec.read(input)
            ?: throw GuardWireException("Server closed GuardWire connection")
        return GuardWireFrame(
            type = frame.type,
            payload = session.decrypt(frame.type.code, frame.payload),
        )
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun writeEncryptedFrame(typeCode: Int, payload: ByteArray) {
        val encrypted = session.encrypt(typeCode, payload)
        synchronized(writeLock) {
            output.writeByte(typeCode)
            output.writeShort(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
