package com.haruchka.testvpnapp.core.vpn

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.VpnStateRepository
import com.haruchka.testvpnapp.core.domain.WireGuardEngine
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Фасад над [GuardWireVpnService] для UI-слоя.
 *
 * Отвечает за запрос согласия системы, запуск foreground-сервиса и чтение
 * состояния. Сама логика туннеля живёт в сервисе.
 */
@Singleton
class VpnController @Inject constructor(
    @ApplicationContext context: Context,
    private val stateStore: VpnStateRepository,
    private val wireGuard: WireGuardEngine,
) : VpnSession {

    private val appContext = context.applicationContext

    override val state: StateFlow<VpnRuntimeState> = stateStore.state

    /** Запускает подключение. Вызывать только после успешного [VpnService.prepare]. */
    override fun connect(config: VpnServerConfig) {
        stateStore.markConnecting(VpnMode.SIMPLIFIED, config)
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

    /** Запускает подключение в реальном режиме WireGuard. */
    override fun connectWireGuard(configText: String) {
        wireGuard.connect(configText)
    }

    /** Останавливает активный туннель и убирает foreground-уведомление. */
    override fun disconnect() {
        if (stateStore.state.value.mode == VpnMode.REAL) {
            wireGuard.disconnect()
            return
        }

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
