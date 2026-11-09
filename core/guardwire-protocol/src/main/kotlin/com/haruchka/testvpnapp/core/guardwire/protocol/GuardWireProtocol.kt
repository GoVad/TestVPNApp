package com.haruchka.testvpnapp.core.guardwire.protocol

import java.io.IOException

/** Общие константы учебного протокола GuardWire. */
object GuardWireProtocol {
    /** Маркер начала рукопожатия: ASCII-строка `GUARDWIRE`. */
    val MAGIC: ByteArray = "GUARDWIRE".toByteArray(Charsets.US_ASCII)

    /** Версия протокола. Несовпадение версий приводит к отказу сервера. */
    const val VERSION: Int = 2

    /** Размер заголовка кадра: тип (1 байт) + длина payload (2 байта). */
    const val FRAME_HEADER_SIZE: Int = 3

    /** Максимальный размер payload одного кадра. */
    const val MAX_PAYLOAD_SIZE: Int = 65_535

    const val CONNECT_TIMEOUT_MS: Int = 10_000
    const val HANDSHAKE_TIMEOUT_MS: Int = 10_000
    const val STATUS_OK: Int = 0
}

/** Тип кадра GuardWire. */
enum class GuardWireFrameType(val code: Int) {
    /** IP-пакет из/в TUN-интерфейс. */
    DATA(0x01),

    /** Проверка живости без ожидания ответа. */
    KEEP_ALIVE(0x02),

    /** Штатное закрытие туннеля. */
    DISCONNECT(0x03),

    /** Ping, сервер должен ответить [PONG]. */
    PING(0x04),

    /** Ответ на [PING]. */
    PONG(0x05),
    ;

    companion object {
        fun fromCode(code: Int): GuardWireFrameType =
            entries.firstOrNull { it.code == code }
                ?: throw GuardWireException("Unknown GuardWire frame type: 0x${code.toString(16)}")
    }
}

/** Один кадр GuardWire. [payload] для [GuardWireFrameType.DATA] содержит целый IP-пакет. */
class GuardWireFrame(
    val type: GuardWireFrameType,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GuardWireFrame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    override fun toString(): String =
        "GuardWireFrame(type=$type, payloadSize=${payload.size})"
}

/** Любая ошибка протокола или транспорта GuardWire. */
class GuardWireException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
