package com.haruchka.testvpnapp.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Фасад над [GuardWireVpnService] для UI-слоя.
 *
 * Отвечает за запрос согласия системы, запуск foreground-сервиса и чтение
 * состояния. Сама логика туннеля живёт в сервисе.
 */
class VpnController(
    context: Context,
    private val stateStore: VpnStateStore = VpnStateStore.shared,
) {

    private val appContext = context.applicationContext

    val state: StateFlow<VpnRuntimeState> = stateStore.state

    /**
     * Возвращает Intent для системного диалога согласия на VPN или `null`,
     * если разрешение уже выдано.
     */
    fun createPermissionIntent(): Intent? = VpnService.prepare(appContext)

    /** Запускает подключение. Вызывать только после успешного [VpnService.prepare]. */
    fun connect(config: VpnServerConfig) {
        stateStore.markConnecting(config)
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                GuardWireVpnService.connectIntent(appContext, config),
            )
        }.onFailure { error ->
            stateStore.markFailed(
                "Не удалось запустить VPN-сервис: ${error.message ?: "неизвестная ошибка"}",
            )
        }
    }

    /** Останавливает туннель и убирает foreground-уведомление. */
    fun disconnect() {
        stateStore.markDisconnecting()
        runCatching {
            appContext.startService(GuardWireVpnService.disconnectIntent(appContext))
        }.onFailure { error ->
            stateStore.markFailed(
                "Не удалось остановить VPN-сервис: ${error.message ?: "неизвестная ошибка"}",
            )
        }
    }
}
