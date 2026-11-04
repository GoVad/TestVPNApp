package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.VpnLogRepository
import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.syntax.Syntax
import org.orbitmvi.orbit.viewmodel.orbitContainer

/**
 * MVVM + Orbit: UI отправляет интенты, ViewModel меняет состояние через
 * `reduce` и отдаёт одноразовые события через `postSideEffect`.
 */
@HiltViewModel
class VpnViewModel @Inject constructor(
    private val controller: VpnSession,
    private val settingsRepository: VpnSettingsStore,
    private val authRepository: AuthService,
    private val profileParser: WireGuardProfileParser,
    private val logStore: VpnLogRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel(), OrbitContainerHost<VpnUiState, VpnUiState, VpnSideEffect> {
    private var wireGuardConfig: String? = null
    private var pending: PendingConnection? = null

    override val container: OrbitContainer<VpnUiState, VpnUiState, VpnSideEffect> =
        orbitContainer(initialState = VpnUiState()) {
            val stored = runCatching { settingsRepository.load() }
                .onFailure { logStore.append("Не удалось загрузить настройки: ${it.message}") }
                .getOrNull()

            if (stored != null) {
                wireGuardConfig = stored.wireGuardConfig
                val profile = stored.wireGuardConfig?.let {
                    runCatching { profileParser.profile(it) }.getOrNull()
                }
                val nowSeconds = System.currentTimeMillis() / 1_000L
                val authValid = stored.authToken != null && stored.authExpiresAt > nowSeconds
                if (!authValid && stored.authToken != null) {
                    logStore.append("Сессия истекла, войдите заново")
                }
                reduce {
                    state.copy(
                        mode = stored.mode,
                        host = stored.guardWire.host,
                        port = stored.guardWire.port.toString(),
                        token = if (authValid) stored.authToken.orEmpty() else stored.guardWire.token,
                        isLoggedIn = authValid,
                        username = stored.authUsername.orEmpty(),
                        authBaseUrl = stored.authBaseUrl,
                        hasWireGuardConfig = stored.wireGuardConfig != null,
                        wireGuardEndpoint = profile?.endpoint,
                        wireGuardAddress = profile?.addresses,
                    )
                }
            }

            logStore.append("Готов к подключению")

            reduce { state.copy(isLoading = false, startupState = StartupState.READY) }

            coroutineScope {
                launch {
                    controller.state.collect { runtime ->
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
                    logStore.logs.collect { logs ->
                        reduce { state.copy(logs = logs) }
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
                val config = readConfig() ?: return@intent
                runCatching { settingsRepository.saveGuardWire(config) }
                    .onFailure { logStore.append("Не удалось сохранить настройки GuardWire") }
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
        val server = baseUrl.trim()
        val user = username.trim()
        if (server.isEmpty() || user.isEmpty() || password.isEmpty()) {
            val message = "Заполните auth-сервер, логин и пароль"
            reduce { state.copy(authError = message) }
            return@intent
        }

        reduce { state.copy(isLoggingIn = true, authError = null) }
        val result = runCatching {
            authRepository.login(server, user, password)
        }

        result.onSuccess { session ->
            wireGuardConfig = session.wireGuardConfig ?: wireGuardConfig
            runCatching {
                settingsRepository.saveAuth(
                    token = session.token,
                    username = session.username,
                    expiresAtEpochSeconds = session.expiresAtEpochSeconds,
                    baseUrl = server,
                )
            }
            val provisionedConfig = session.wireGuardConfig
            if (provisionedConfig != null) {
                runCatching { settingsRepository.saveWireGuardConfig(provisionedConfig) }
            }
            val profile = wireGuardConfig?.let {
                runCatching { profileParser.profile(it) }.getOrNull()
            }
            reduce {
                state.copy(
                    isLoggedIn = true,
                    isLoggingIn = false,
                    authError = null,
                    username = session.username,
                    authBaseUrl = server,
                    token = session.token,
                    hasWireGuardConfig = wireGuardConfig != null,
                    wireGuardEndpoint = profile?.endpoint,
                    wireGuardAddress = profile?.addresses,
                )
            }
            logStore.append("Вход выполнен: ${session.username}")
        }.onFailure { error ->
            val message = error.message ?: "Не удалось войти"
            reduce { state.copy(isLoggingIn = false, authError = message) }
            logStore.append("Ошибка входа: $message")
        }
    }

    fun onAuthInputChanged() = intent {
        if (state.authError != null) {
            reduce { state.copy(authError = null) }
        }
    }

    fun onLogout() = intent {
        if (state.isActive) {
            controller.disconnect()
        }
        wireGuardConfig = null
        runCatching { settingsRepository.clearAuth() }
        runCatching { settingsRepository.clearWireGuardConfig() }
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
        logStore.append("Выход из аккаунта")
    }

    fun onDisconnectClicked() = intent {
        if (!state.isActive) return@intent
        controller.disconnect()
    }

    fun onNotificationPermissionResult(granted: Boolean) = intent {
        if (!granted) {
            logStore.append("Уведомления отключены — туннель продолжит работу")
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
        runCatching { settingsRepository.saveMode(mode) }
            .onFailure { logStore.append("Не удалось сохранить режим") }
        logStore.append(
            when (mode) {
                VpnMode.SIMPLIFIED -> "Выбран упрощённый режим GuardWire/2"
                VpnMode.REAL -> "Выбран реальный режим WireGuard"
            },
        )
    }

    fun onWireGuardConfigSaved(configText: String) = intent {
        val profile = try {
            profileParser.profile(configText)
        } catch (e: Exception) {
            val message = e.message ?: "Некорректный WireGuard-конфиг"
            reduce { state.copy(inputError = message) }
            postSideEffect(VpnSideEffect.ShowMessage(message))
            return@intent
        }

        wireGuardConfig = configText
        runCatching { settingsRepository.saveWireGuardConfig(configText) }
            .onFailure {
                logStore.append("Не удалось сохранить WireGuard-конфиг")
                reduce { state.copy(inputError = "Не удалось сохранить WireGuard-конфиг") }
                return@intent
            }
        reduce {
            state.copy(
                hasWireGuardConfig = true,
                wireGuardEndpoint = profile.endpoint,
                wireGuardAddress = profile.addresses,
                inputError = null,
            )
        }
        logStore.append("WireGuard: конфиг сохранён")
        postSideEffect(VpnSideEffect.ShowMessage("WireGuard-конфиг сохранён"))
    }

    fun onWireGuardConfigCleared() = intent {
        if (state.isActive) return@intent
        wireGuardConfig = null
        runCatching { settingsRepository.clearWireGuardConfig() }
            .onFailure { logStore.append("Не удалось удалить WireGuard-конфиг") }
        reduce {
            state.copy(
                hasWireGuardConfig = false,
                wireGuardEndpoint = null,
                wireGuardAddress = null,
                inputError = null,
            )
        }
        logStore.append("WireGuard: конфиг удалён")
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
        logStore.clear()
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.readConfig(): VpnServerConfig? {
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull()

        val error = when {
            host.isEmpty() -> "Укажите адрес GuardWire-сервера"
            port == null || port !in 1..65_535 -> "Порт должен быть числом от 1 до 65535"
            state.token.isBlank() -> "Укажите PSK-токен: он нужен для шифрования"
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
        val consentIntent = VpnService.prepare(appContext)
        if (consentIntent == null) {
            startTunnel()
        } else {
            postSideEffect(VpnSideEffect.LaunchVpnConsent(consentIntent))
        }
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.startTunnel() {
        when (val connection = pending) {
            is PendingConnection.GuardWire -> {
                pending = null
                controller.connect(connection.config)
            }

            is PendingConnection.WireGuard -> {
                pending = null
                controller.connectWireGuard(connection.configText)
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
