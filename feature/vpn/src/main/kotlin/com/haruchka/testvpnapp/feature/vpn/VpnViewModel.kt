package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
import com.haruchka.testvpnapp.core.domain.usecase.EnsureAuthSessionUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoadVpnSettingsUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoginUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LogoutUseCase
import com.haruchka.testvpnapp.core.domain.usecase.ProvisionWireGuardUseCase
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
    private val ensureAuthSession: EnsureAuthSessionUseCase,
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    private val connection: VpnConnectionUseCase,
    private val validateGuardWireConfig: ValidateGuardWireConfigUseCase,
    private val provisionWireGuard: ProvisionWireGuardUseCase,
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
                var accessToken = loaded.authToken.orEmpty()
                var isLoggedIn = loaded.isAuthValid
                var username = loaded.authUsername.orEmpty()
                val canRefresh = !isLoggedIn && loaded.isRefreshValid

                if (canRefresh) {
                    val restored = ensureAuthSession()
                    val session = restored.getOrNull()
                    if (session != null) {
                        accessToken = session.accessToken
                        username = session.username
                        isLoggedIn = true
                        logs.append("Сессия продлена без повторного входа")
                    } else if (restored.exceptionOrNull() is AuthSessionExpiredException) {
                        settings.clearAuth()
                        logs.append("Сессия истекла, войдите заново")
                    } else {
                        logs.append(
                            "Не удалось продлить сессию: " +
                                (restored.exceptionOrNull()?.message ?: "нет сети"),
                        )
                    }
                } else if (!isLoggedIn && loaded.authToken != null) {
                    settings.clearAuth()
                    logs.append("Сессия истекла, войдите заново")
                }

                reduce {
                    state.copy(
                        mode = loaded.mode,
                        host = loaded.guardWire.host,
                        port = loaded.guardWire.port.toString(),
                        token = if (isLoggedIn) accessToken else loaded.guardWire.token,
                        isLoggedIn = isLoggedIn,
                        username = username,
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

        val authResult = ensureAuthSession()
        val auth = authResult.getOrNull()
        if (auth == null) {
            val error = authResult.exceptionOrNull()
            if (error is AuthSessionExpiredException) {
                handleExpiredSession("Сессия истекла, войдите заново")
            } else {
                val message = "Не удалось продлить сессию: ${error?.message ?: "нет сети"}"
                logs.append(message)
                postSideEffect(VpnSideEffect.ShowMessage(message))
            }
            return@intent
        }
        if (auth.accessToken != state.token) {
            reduce { state.copy(token = auth.accessToken) }
            logs.append("Access-токен обновлён")
        }

        when (state.mode) {
            VpnMode.SIMPLIFIED -> {
                val validation = validateGuardWireConfig(
                    host = state.host,
                    port = state.port,
                    token = auth.accessToken,
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
                val config = wireGuardConfig ?: provisionWireGuardProfile() ?: return@intent
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
                        token = result.session.accessToken,
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

    fun onWireGuardConfigRequested() = intent {
        if (state.isActive || state.isProvisioningWireGuard) return@intent
        provisionWireGuardProfile()
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

    fun onOpenVpnSettings() = intent {
        postSideEffect(VpnSideEffect.OpenVpnSettings)
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleExpiredSession(message: String) {
        settings.clearAuth()
        settings.clearWireGuard()
        wireGuardConfig = null
        reduce {
            state.copy(
                isLoggedIn = false,
                username = "",
                token = "",
                hasWireGuardConfig = false,
                wireGuardEndpoint = null,
                wireGuardAddress = null,
                authError = message,
            )
        }
        logs.append(message)
        postSideEffect(VpnSideEffect.ShowMessage(message))
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.provisionWireGuardProfile(): String? {
        if (state.isProvisioningWireGuard) return null
        reduce { state.copy(isProvisioningWireGuard = true, inputError = null) }

        val authResult = ensureAuthSession()
        if (authResult.getOrNull() == null) {
            reduce { state.copy(isProvisioningWireGuard = false) }
            val error = authResult.exceptionOrNull()
            if (error is AuthSessionExpiredException) {
                handleExpiredSession("Сессия истекла, войдите заново")
            } else {
                val message = "Не удалось продлить сессию: ${error?.message ?: "нет сети"}"
                logs.append(message)
                postSideEffect(VpnSideEffect.ShowMessage(message))
            }
            return null
        }

        val result = provisionWireGuard()
        val provisioned = result.getOrNull()
        if (provisioned == null) {
            val message = result.exceptionOrNull()?.message
                ?: "Не удалось получить WireGuard-профиль"
            reduce {
                state.copy(
                    isProvisioningWireGuard = false,
                    inputError = message,
                )
            }
            logs.append("WireGuard: $message")
            postSideEffect(VpnSideEffect.ShowMessage(message))
            return null
        }

        wireGuardConfig = provisioned.config
        reduce {
            state.copy(
                isProvisioningWireGuard = false,
                hasWireGuardConfig = true,
                wireGuardEndpoint = provisioned.profile.endpoint,
                wireGuardAddress = provisioned.profile.addresses,
                inputError = null,
            )
        }
        logs.append("WireGuard: профиль получен с сервера")
        postSideEffect(VpnSideEffect.ShowMessage("WireGuard-профиль получен"))
        return provisioned.config
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
