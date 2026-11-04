package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.haruchka.testvpnapp.core.domain.usecase.LoadVpnSettingsUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoginUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LogoutUseCase
import com.haruchka.testvpnapp.core.domain.usecase.SaveWireGuardConfigUseCase
import com.haruchka.testvpnapp.core.domain.usecase.ValidateGuardWireConfigUseCase
import com.haruchka.testvpnapp.core.domain.usecase.VpnConnectionUseCase
import com.haruchka.testvpnapp.core.domain.usecase.VpnLogsUseCase
import com.haruchka.testvpnapp.core.domain.usecase.VpnSettingsUseCase
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.syntax.Syntax
import org.orbitmvi.orbit.viewmodel.orbitContainer

/**
 * MVVM + Orbit: UI отправляет интенты, ViewModel вызывает use case-и,
 * меняет состояние через `reduce` и отдаёт события через `postSideEffect`.
 */
@HiltViewModel
class VpnViewModel @Inject constructor(
    private val loadVpnSettings: LoadVpnSettingsUseCase,
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    private val connection: VpnConnectionUseCase,
    private val validateGuardWireConfig: ValidateGuardWireConfigUseCase,
    private val saveWireGuardConfig: SaveWireGuardConfigUseCase,
    private val settings: VpnSettingsUseCase,
    private val logs: VpnLogsUseCase,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel(), OrbitContainerHost<VpnUiState, VpnUiState, VpnSideEffect> {
    private var wireGuardConfig: String? = null
    private var pending: PendingConnection? = null

    override val container: OrbitContainer<VpnUiState, VpnUiState, VpnSideEffect> =
        orbitContainer(initialState = VpnUiState()) {
            val loaded = loadVpnSettings()
                .onFailure { logs.append("Не удалось загрузить настройки: ${it.message}") }
                .getOrNull()

            if (loaded != null) {
                wireGuardConfig = loaded.wireGuardConfig
                if (!loaded.isAuthValid && loaded.authToken != null) {
                    logs.append("Сессия истекла, войдите заново")
                }
                reduce {
                    state.copy(
                        mode = loaded.mode,
                        host = loaded.guardWire.host,
                        port = loaded.guardWire.port.toString(),
                        token = if (loaded.isAuthValid) {
                            loaded.authToken.orEmpty()
                        } else {
                            loaded.guardWire.token
                        },
                        isLoggedIn = loaded.isAuthValid,
                        username = loaded.authUsername.orEmpty(),
                        authBaseUrl = loaded.authBaseUrl,
                        hasWireGuardConfig = loaded.wireGuardConfig != null,
                        wireGuardEndpoint = loaded.wireGuardProfile?.endpoint,
                        wireGuardAddress = loaded.wireGuardProfile?.addresses,
                    )
                }
            }

            logs.append("Готов к подключению")

            reduce { state.copy(isLoading = false, startupState = StartupState.READY) }

            coroutineScope {
                launch {
                    connection.observeState().collect { runtime ->
                        reduce {
                            state.copy(
                                runtime = runtime,
                                nowMillis = System.currentTimeMillis(),
                                inputError = if (runtime.connection is VpnConnectionState.Failed) {
                                    null
                                } else {
                                    state.inputError
                                },
                            )
                        }
                    }
                }
                launch {
                    logs.observe().collect { entries ->
                        reduce { state.copy(logs = entries) }
                    }
                }
            }
        }

    fun onConnectClicked() = intent {
        if (state.isActive || state.isLoading) return@intent
        if (!state.isLoggedIn) {
            postSideEffect(VpnSideEffect.ShowMessage("Сначала войдите в аккаунт"))
            return@intent
        }

        when (state.mode) {
            VpnMode.SIMPLIFIED -> {
                val validation = validateGuardWireConfig(
                    host = state.host,
                    port = state.port,
                    token = state.token,
                )
                val config = validation.getOrNull()
                if (config == null) {
                    val message = validation.exceptionOrNull()?.message
                        ?: "Некорректные настройки GuardWire"
                    reduce { state.copy(inputError = message) }
                    postSideEffect(VpnSideEffect.ShowMessage(message))
                    return@intent
                }

                settings.saveGuardWire(config)
                    .onFailure { logs.append("Не удалось сохранить настройки GuardWire") }
                pending = PendingConnection.GuardWire(config)
                reduce {
                    state.copy(
                        host = config.host,
                        port = config.port.toString(),
                        token = config.token,
                        inputError = null,
                    )
                }
            }

            VpnMode.REAL -> {
                val config = wireGuardConfig
                if (config == null) {
                    val message = "Сначала вставьте WireGuard-конфиг"
                    reduce { state.copy(inputError = message) }
                    postSideEffect(VpnSideEffect.ShowMessage(message))
                    return@intent
                }
                pending = PendingConnection.WireGuard(config)
                reduce { state.copy(inputError = null) }
            }
        }

        if (needsNotificationPermission()) {
            postSideEffect(VpnSideEffect.RequestNotificationPermission)
        } else {
            requestVpnConsent()
        }
    }

    fun onLogin(baseUrl: String, username: String, password: String) = intent {
        reduce { state.copy(isLoggingIn = true, authError = null) }

        login(baseUrl, username, password)
            .onSuccess { result ->
                wireGuardConfig = result.wireGuardConfig
                reduce {
                    state.copy(
                        isLoggedIn = true,
                        isLoggingIn = false,
                        authError = null,
                        username = result.session.username,
                        authBaseUrl = baseUrl.trim(),
                        token = result.session.token,
                        hasWireGuardConfig = result.wireGuardConfig != null,
                        wireGuardEndpoint = result.wireGuardProfile?.endpoint,
                        wireGuardAddress = result.wireGuardProfile?.addresses,
                    )
                }
                logs.append("Вход выполнен: ${result.session.username}")
            }
            .onFailure { error ->
                val message = error.message ?: "Не удалось войти"
                reduce { state.copy(isLoggingIn = false, authError = message) }
                logs.append("Ошибка входа: $message")
            }
    }

    fun onAuthInputChanged() = intent {
        if (state.authError != null) {
            reduce { state.copy(authError = null) }
        }
    }

    fun onLogout() = intent {
        logout(disconnect = state.isActive)
            .onFailure { logs.append("Не удалось выйти: ${it.message}") }
        wireGuardConfig = null
        reduce {
            state.copy(
                isLoggedIn = false,
                username = "",
                token = "",
                hasWireGuardConfig = false,
                wireGuardEndpoint = null,
                wireGuardAddress = null,
                authError = null,
            )
        }
        logs.append("Выход из аккаунта")
    }

    fun onDisconnectClicked() = intent {
        if (!state.isActive) return@intent
        connection.disconnect()
    }

    fun onNotificationPermissionResult(granted: Boolean) = intent {
        if (!granted) {
            logs.append("Уведомления отключены — туннель продолжит работу")
        }
        requestVpnConsent()
    }

    fun onVpnConsentResult(granted: Boolean) = intent {
        if (!granted) {
            pending = null
            val message = "Без разрешения системы VPN-подключение невозможно"
            reduce { state.copy(inputError = message) }
            postSideEffect(VpnSideEffect.ShowMessage(message))
            return@intent
        }
        startTunnel()
    }

    fun onModeChanged(mode: VpnMode) = intent {
        if (state.isActive || state.isLoading || state.mode == mode) return@intent
        reduce { state.copy(mode = mode, inputError = null) }
        settings.saveMode(mode)
            .onFailure { logs.append("Не удалось сохранить режим") }
        logs.append(
            when (mode) {
                VpnMode.SIMPLIFIED -> "Выбран упрощённый режим GuardWire/2"
                VpnMode.REAL -> "Выбран реальный режим WireGuard"
            },
        )
    }

    fun onWireGuardConfigSaved(configText: String) = intent {
        saveWireGuardConfig(configText)
            .onSuccess { profile ->
                wireGuardConfig = configText
                reduce {
                    state.copy(
                        hasWireGuardConfig = true,
                        wireGuardEndpoint = profile.endpoint,
                        wireGuardAddress = profile.addresses,
                        inputError = null,
                    )
                }
                logs.append("WireGuard: конфиг сохранён")
                postSideEffect(VpnSideEffect.ShowMessage("WireGuard-конфиг сохранён"))
            }
            .onFailure { error ->
                val message = error.message ?: "Некорректный WireGuard-конфиг"
                reduce { state.copy(inputError = message) }
                postSideEffect(VpnSideEffect.ShowMessage(message))
            }
    }

    fun onWireGuardConfigCleared() = intent {
        if (state.isActive) return@intent
        wireGuardConfig = null
        settings.clearWireGuard()
            .onFailure { logs.append("Не удалось удалить WireGuard-конфиг") }
        reduce {
            state.copy(
                hasWireGuardConfig = false,
                wireGuardEndpoint = null,
                wireGuardAddress = null,
                inputError = null,
            )
        }
        logs.append("WireGuard: конфиг удалён")
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

    fun onLogsCleared() = intent {
        logs.clear()
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.requestVpnConsent() {
        val consentIntent = VpnService.prepare(appContext)
        if (consentIntent == null) {
            startTunnel()
        } else {
            postSideEffect(VpnSideEffect.LaunchVpnConsent(consentIntent))
        }
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.startTunnel() {
        when (val request = pending) {
            is PendingConnection.GuardWire -> {
                pending = null
                connection.connectGuardWire(request.config)
            }

            is PendingConnection.WireGuard -> {
                pending = null
                connection.connectWireGuard(request.configText)
            }

            null -> {
                postSideEffect(VpnSideEffect.ShowMessage("Настройки потеряны, попробуйте ещё раз"))
            }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    private sealed interface PendingConnection {
        data class GuardWire(val config: VpnServerConfig) : PendingConnection
        data class WireGuard(val configText: String) : PendingConnection
    }
}
