package com.haruchka.testvpnapp.core.vpn

import com.haruchka.testvpnapp.core.model.VpnLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Общий журнал приложения.
 *
 * Сюда пишут сервисы и контроллеры, UI читает и показывает в отдельном окне.
 * Не логируем токены, приватные ключи и содержимое трафика.
 */
@Singleton
class VpnLogStore @Inject constructor() {

    private val _logs = MutableStateFlow<List<VpnLogEntry>>(emptyList())
    val logs: StateFlow<List<VpnLogEntry>> = _logs.asStateFlow()

    fun append(message: String) {
        val entry = VpnLogEntry(
            timestampMillis = System.currentTimeMillis(),
            message = message,
        )
        _logs.update { current -> (current + entry).takeLast(MAX_ENTRIES) }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
