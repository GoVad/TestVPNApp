package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.vpn.VpnController
import com.haruchka.testvpnapp.core.vpn.VpnSettingsRepository
import kotlinx.coroutines.flow.collect
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.syntax.Syntax
import org.orbitmvi.orbit.viewmodel.orbitContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MVVM + Orbit: UI отправляет интенты, ViewModel меняет состояние через
 * `reduce` и отдаёт одноразовые события через `postSideEffect`.
 */
class VpnViewModel(
    private val controller: VpnController,
    private val settingsRepository: VpnSettingsRepository,
    private val appContext: Context,
) : ViewModel(), OrbitContainerHost<VpnUiState, VpnUiState, VpnSideEffect> {

    private val savedConfig = settingsRepository.load()
    private var pendingConfig: VpnServerConfig? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override val container: OrbitContainer<VpnUiState, VpnUiState, VpnSideEffect> =
        orbitContainer(
            initialState = VpnUiState(
                host = savedConfig.host,
                port = savedConfig.port.toString(),
                token = savedConfig.token,
                logs = listOf(log("Готов к подключению")),
            ),
        ) {
            var previousState: VpnConnectionState = VpnConnectionState.Disconnected

            controller.state.collect { runtime ->
                val previous = previousState
                previousState = runtime.connection
                val newLog = if (previous != runtime.connection) {
                    log(describe(runtime))
                } else {
                    null
                }

                reduce {
                    state.copy(
                        runtime = runtime,
                        nowMillis = System.currentTimeMillis(),
                        inputError = if (runtime.connection is VpnConnectionState.Failed) {
                            null
                        } else {
                            state.inputError
                        },
                        logs = newLog?.let { appendLog(state.logs, it) } ?: state.logs,
                    )
                }
            }
        }

    fun onConnectClicked() = intent {
        if (state.isActive) return@intent

        val config = readConfig() ?: return@intent
        pendingConfig = config
        settingsRepository.save(config)

        reduce {
            state.copy(
                host = config.host,
                port = config.port.toString(),
                token = config.token,
                inputError = null,
                logs = appendLog(state.logs, log("Проверяем разрешения…")),
            )
        }

        if (needsNotificationPermission()) {
            postSideEffect(VpnSideEffect.RequestNotificationPermission)
        } else {
            requestVpnConsent()
        }
    }

    fun onDisconnectClicked() = intent {
        if (!state.isActive) return@intent

        controller.disconnect()
        reduce {
            state.copy(logs = appendLog(state.logs, log("Запрошено отключение")))
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) = intent {
        if (!granted) {
            reduce {
                state.copy(
                    logs = appendLog(
                        state.logs,
                        log("Уведомления отключены — туннель продолжит работу"),
                    ),
                )
            }
        }
        requestVpnConsent()
    }

    fun onVpnConsentResult(granted: Boolean) = intent {
        if (!granted) {
            pendingConfig = null
            val message = "Без разрешения системы VPN-подключение невозможно"
            reduce {
                state.copy(
                    inputError = message,
                    logs = appendLog(state.logs, log(message)),
                )
            }
            postSideEffect(VpnSideEffect.ShowMessage(message))
            return@intent
        }
        startTunnel()
    }

    fun onHostChanged(value: String) = intent {
        reduce { state.copy(host = value, inputError = null) }
    }

    fun onPortChanged(value: String) = intent {
        val filtered = value.filter(Char::isDigit).take(5)
        reduce { state.copy(port = filtered, inputError = null) }
    }

    fun onTokenChanged(value: String) = intent {
        reduce { state.copy(token = value, inputError = null) }
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.readConfig(): VpnServerConfig? {
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull()

        val error = when {
            host.isEmpty() -> "Укажите адрес GuardWire-сервера"
            port == null || port !in 1..65_535 -> "Порт должен быть числом от 1 до 65535"
            else -> null
        }

        if (error != null) {
            reduce { state.copy(inputError = error) }
            postSideEffect(VpnSideEffect.ShowMessage(error))
            return null
        }

        return VpnServerConfig(
            host = host,
            port = port!!,
            token = state.token.trim(),
        )
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.requestVpnConsent() {
        val consentIntent = controller.createPermissionIntent()
        if (consentIntent == null) {
            startTunnel()
        } else {
            postSideEffect(VpnSideEffect.LaunchVpnConsent(consentIntent))
        }
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.startTunnel() {
        val config = pendingConfig
        if (config == null) {
            postSideEffect(VpnSideEffect.ShowMessage("Настройки потеряны, попробуйте ещё раз"))
            return
        }

        pendingConfig = null
        controller.connect(config)
        reduce {
            state.copy(logs = appendLog(state.logs, log("Запуск VPN-сервиса…")))
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    private fun describe(runtime: VpnRuntimeState): String {
        val endpoint = runtime.server?.endpoint ?: "сервер"
        return when (val connection = runtime.connection) {
            VpnConnectionState.Disconnected -> "Туннель отключён"
            VpnConnectionState.Connecting -> "Подключение к $endpoint…"
            is VpnConnectionState.Connected -> "Туннель установлен: $endpoint"
            VpnConnectionState.Disconnecting -> "Отключение…"
            is VpnConnectionState.Failed -> "Ошибка: ${connection.reason}"
        }
    }

    private fun log(message: String): VpnLogEntry =
        VpnLogEntry(time = timeFormat.format(Date()), message = message)

    private fun appendLog(logs: List<VpnLogEntry>, entry: VpnLogEntry): List<VpnLogEntry> =
        (logs + entry).takeLast(MAX_LOG_LINES)

    companion object {
        private const val MAX_LOG_LINES = 8

        fun factory(
            controller: VpnController,
            settingsRepository: VpnSettingsRepository,
            appContext: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VpnViewModel(controller, settingsRepository, appContext)
            }
        }
    }
}
