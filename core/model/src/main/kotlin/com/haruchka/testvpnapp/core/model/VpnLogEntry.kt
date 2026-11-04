package com.haruchka.testvpnapp.core.model

/** Запись журнала приложения. Секреты и содержимое трафика сюда не пишутся. */
data class VpnLogEntry(
    val timestampMillis: Long,
    val message: String,
)
