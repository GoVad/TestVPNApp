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
 * из разных корутин.
 */
class GuardWireConnection internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : Closeable {

    private val writeLock = Any()

    /** Отправляет один IP-пакет как DATA-кадр. */
    fun sendData(packet: ByteArray, length: Int) {
        require(length in 0..packet.size) { "Некорректная длина пакета: $length" }
        writeFrame(typeCode = GuardWireFrameType.DATA.code, payload = packet, length = length)
    }

    fun sendKeepAlive() {
        writeFrame(typeCode = GuardWireFrameType.KEEP_ALIVE.code, payload = EMPTY, length = 0)
    }

    fun sendPing() {
        writeFrame(typeCode = GuardWireFrameType.PING.code, payload = EMPTY, length = 0)
    }

    fun sendPong() {
        writeFrame(typeCode = GuardWireFrameType.PONG.code, payload = EMPTY, length = 0)
    }

    fun sendDisconnect() {
        writeFrame(typeCode = GuardWireFrameType.DISCONNECT.code, payload = EMPTY, length = 0)
    }

    /** Блокирующее чтение следующего кадра. Бросает [java.io.EOFException] при EOF. */
    fun receive(): GuardWireFrame =
        GuardWireFrameCodec.read(input)
            ?: throw GuardWireException("Сервер закрыл GuardWire-соединение")

    override fun close() {
        runCatching { socket.close() }
    }

    private fun writeFrame(typeCode: Int, payload: ByteArray, length: Int) {
        synchronized(writeLock) {
            output.writeByte(typeCode)
            output.writeShort(length)
            output.write(payload, 0, length)
            output.flush()
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
