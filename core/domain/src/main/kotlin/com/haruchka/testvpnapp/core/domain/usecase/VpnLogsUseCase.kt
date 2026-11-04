package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.domain.VpnLogRepository
import com.haruchka.testvpnapp.core.model.VpnLogEntry
import kotlinx.coroutines.flow.Flow

/** Наблюдение за журналом и запись событий приложения. */
class VpnLogsUseCase(
    private val logs: VpnLogRepository,
) {
    fun observe(): Flow<List<VpnLogEntry>> = logs.logs

    fun append(message: String) = logs.append(message)

    fun clear() = logs.clear()
}
