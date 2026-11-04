package com.haruchka.testvpnapp.core.domain

import com.haruchka.testvpnapp.core.model.VpnLogEntry
import kotlinx.coroutines.flow.StateFlow

/** Контракт журнала приложения. */
interface VpnLogRepository {
    val logs: StateFlow<List<VpnLogEntry>>

    fun append(message: String)

    fun clear()
}
