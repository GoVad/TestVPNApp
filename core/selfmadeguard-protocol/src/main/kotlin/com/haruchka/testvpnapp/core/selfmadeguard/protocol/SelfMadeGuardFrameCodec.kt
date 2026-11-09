package com.haruchka.testvpnapp.core.selfmadeguard.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Чтение и запись кадров SelfMadeGuard.
 *
 * Формат кадра:
 * ```
 * +--------+------------------+-----------------+
 * | type   | payload length   | payload         |
 * | 1 байт | 2 байта (BE)     | N байт          |
 * +--------+------------------+-----------------+
 * ```
 */
object SelfMadeGuardFrameCodec {

    fun write(output: DataOutputStream, frame: SelfMadeGuardFrame) {
        require(frame.payload.size <= SelfMadeGuardProtocol.MAX_PAYLOAD_SIZE) {
            "Payload exceeds ${SelfMadeGuardProtocol.MAX_PAYLOAD_SIZE} bytes"
        }
        output.writeByte(frame.type.code)
        output.writeShort(frame.payload.size)
        output.write(frame.payload)
        output.flush()
    }

    /** Возвращает `null` при чистом EOF — сервер закрыл соединение. */
    fun read(input: DataInputStream): SelfMadeGuardFrame? {
        val typeCode = input.read()
        if (typeCode == -1) return null

        val payloadLength = input.readUnsignedShort()
        val payload = ByteArray(payloadLength)
        input.readFully(payload)

        return SelfMadeGuardFrame(
            type = SelfMadeGuardFrameType.fromCode(typeCode),
            payload = payload,
        )
    }
}
