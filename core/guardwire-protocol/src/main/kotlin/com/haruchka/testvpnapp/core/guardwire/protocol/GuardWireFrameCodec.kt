package com.haruchka.testvpnapp.core.guardwire.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Чтение и запись кадров GuardWire.
 *
 * Формат кадра:
 * ```
 * +--------+------------------+-----------------+
 * | type   | payload length   | payload         |
 * | 1 байт | 2 байта (BE)     | N байт          |
 * +--------+------------------+-----------------+
 * ```
 */
object GuardWireFrameCodec {

    fun write(output: DataOutputStream, frame: GuardWireFrame) {
        require(frame.payload.size <= GuardWireProtocol.MAX_PAYLOAD_SIZE) {
            "Payload exceeds ${GuardWireProtocol.MAX_PAYLOAD_SIZE} bytes"
        }
        output.writeByte(frame.type.code)
        output.writeShort(frame.payload.size)
        output.write(frame.payload)
        output.flush()
    }

    /** Возвращает `null` при чистом EOF — сервер закрыл соединение. */
    fun read(input: DataInputStream): GuardWireFrame? {
        val typeCode = input.read()
        if (typeCode == -1) return null

        val payloadLength = input.readUnsignedShort()
        val payload = ByteArray(payloadLength)
        input.readFully(payload)

        return GuardWireFrame(
            type = GuardWireFrameType.fromCode(typeCode),
            payload = payload,
        )
    }
}
