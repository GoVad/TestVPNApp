package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.haruchka.testvpnapp.core.domain.AuthSessionExpiredException
import com.haruchka.testvpnapp.core.domain.usecase.SelfMadeGuardConfigError
import com.haruchka.testvpnapp.core.domain.usecase.SelfMadeGuardConfigException
import com.haruchka.testvpnapp.core.domain.usecase.LoginValidationException
import com.haruchka.testvpnapp.core.domain.usecase.SessionStorageException
import com.haruchka.testvpnapp.core.domain.usecase.WireGuardStorageException
import com.haruchka.testvpnapp.core.domain.usecase.EnsureAuthSessionUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoadVpnSettingsUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoginUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LogoutUseCase
import com.haruchka.testvpnapp.core.domain.usecase.ProvisionWireGuardUseCase
import com.haruchka.testvpnapp.core.domain.usecase.ValidateSelfMadeGuardConfigUseCase
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.syntax.Syntax
import org.orbitmvi.orbit.viewmodel.orbitContainer

/**
 * MVVM + Orbit: UI отправляет [VpnAction] через [onAction], ViewModel вызывает
 * use case-и, меняет состояние через `reduce` и отдаёт события через `postSideEffect`.
 */
@HiltViewModel
class VpnViewModel @Inject constructor(
    private val loadVpnSettings: LoadVpnSettingsUseCase,
    private val ensureAuthSession: EnsureAuthSessionUseCase,
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    private val connection: VpnConnectionUseCase,
    private val validateSelfMadeGuardConfig: ValidateSelfMadeGuardConfigUseCase,
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
                .onFailure { logs.append(appContext.getString(R.string.vm_settings_load_failed, it.message ?: "?")) }
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
                        logs.append(appContext.getString(R.string.vm_session_refreshed))
                    } else if (restored.exceptionOrNull() is AuthSessionExpiredException) {
                        settings.clearAuth()
                        logs.append(appContext.getString(R.string.vm_session_expired))
                    } else {
                        logs.append(
                            appContext.getString(
                                R.string.vm_session_refresh_failed,
                                restored.exceptionOrNull()?.message
                                    ?: appContext.getString(R.string.vm_no_network),
                            ),
                        )
                    }
                } else if (!isLoggedIn && loaded.authToken != null) {
                    settings.clearAuth()
                    logs.append(appContext.getString(R.string.vm_session_expired))
                }

                reduce {
                    state.copy(
                        mode = loaded.mode,
                        host = loaded.selfMadeGuard.host,
                        port = loaded.selfMadeGuard.port.toString(),
                        token = if (isLoggedIn) accessToken else loaded.selfMadeGuard.token,
                        isLoggedIn = isLoggedIn,
                        username = username,
                        authBaseUrl = loaded.authBaseUrl,
                        hasWireGuardConfig = loaded.wireGuardConfig != null,
                        wireGuardEndpoint = loaded.wireGuardProfile?.endpoint,
                        wireGuardAddress = loaded.wireGuardProfile?.addresses,
                    )
                }
            }

            logs.append(appContext.getString(R.string.vm_ready))

            // Даём второму splash-экрану побыть видимым, а не мигнуть на один кадр.
            delay(STARTUP_MIN_DURATION_MS)
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

    /**
     * Единая точка входа для событий UI: экран вызывает только этот метод.
     */
    fun onAction(action: VpnAction) = intent {
        when (action) {
            VpnAction.Connect -> handleConnect()
            is VpnAction.Login -> handleLogin(action.baseUrl, action.username, action.password)
            VpnAction.AuthInputChanged ->
                if (state.authError != null) reduce { state.copy(authError = null) }
            VpnAction.Logout -> handleLogout()
            VpnAction.Disconnect ->
                if (state.isActive) connection.disconnect()
            is VpnAction.NotificationPermissionResult ->
                handleNotificationPermissionResult(action.granted)
            is VpnAction.VpnConsentResult -> handleVpnConsentResult(action.granted)
            is VpnAction.ModeChanged -> handleModeChanged(action.mode)
            VpnAction.WireGuardConfigRequested -> handleWireGuardConfigRequested()
            VpnAction.WireGuardConfigCleared -> handleWireGuardConfigCleared()
            is VpnAction.HostChanged ->
                reduce { state.copy(host = action.value, inputError = null) }
            is VpnAction.PortChanged ->
                reduce {
                    state.copy(port = action.value.filter(Char::isDigit).take(5), inputError = null)
                }
            is VpnAction.TokenChanged ->
                reduce { state.copy(token = action.value, inputError = null) }
            VpnAction.LogsCleared -> logs.clear()
            VpnAction.OpenVpnSettings -> postSideEffect(VpnSideEffect.OpenVpnSettings)
        }
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleConnect() {
        if (state.isActive || state.isLoading) return
        if (!state.isLoggedIn) {
            postSideEffect(VpnSideEffect.ShowMessage(appContext.getString(R.string.vm_login_required)))
            return
        }

        val authResult = ensureAuthSession()
        val auth = authResult.getOrNull()
        if (auth == null) {
            val error = authResult.exceptionOrNull()
            if (error is AuthSessionExpiredException) {
                handleExpiredSession(appContext.getString(R.string.vm_session_expired))
            } else {
                val message = appContext.getString(
                    R.string.vm_session_refresh_failed,
                    error?.message ?: appContext.getString(R.string.vm_no_network),
                )
                logs.append(message)
                postSideEffect(VpnSideEffect.ShowMessage(message))
            }
            return
        }
        if (auth.accessToken != state.token) {
            reduce { state.copy(token = auth.accessToken) }
            logs.append(appContext.getString(R.string.vm_access_token_refreshed))
        }

        when (state.mode) {
            VpnMode.SIMPLIFIED -> {
                val validation = validateSelfMadeGuardConfig(
                    host = state.host,
                    port = state.port,
                    token = auth.accessToken,
                )
                val config = validation.getOrNull()
                if (config == null) {
                    val error = validation.exceptionOrNull()
                    val message = if (error is SelfMadeGuardConfigException) {
                        when (error.reason) {
                            SelfMadeGuardConfigError.HOST_EMPTY ->
                                appContext.getString(R.string.validation_host_empty)
                            SelfMadeGuardConfigError.PORT_INVALID ->
                                appContext.getString(R.string.validation_port_invalid)
                            SelfMadeGuardConfigError.TOKEN_EMPTY ->
                                appContext.getString(R.string.validation_psk_empty)
                        }
                    } else {
                        error?.message
                            ?: appContext.getString(R.string.vm_selfmadeguard_settings_invalid)
                    }
                    reduce { state.copy(inputError = message) }
                    postSideEffect(VpnSideEffect.ShowMessage(message))
                    return
                }

                settings.saveSelfMadeGuard(config)
                    .onFailure { logs.append(appContext.getString(R.string.vm_selfmadeguard_settings_save_failed)) }
                pending = PendingConnection.SelfMadeGuard(config)
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
                val config = wireGuardConfig ?: provisionWireGuardProfile() ?: return
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

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleLogin(
        baseUrl: String,
        username: String,
        password: String,
    ) {
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
                logs.append(appContext.getString(R.string.vm_login_success, result.session.username))
            }
            .onFailure { error ->
                val message = when (error) {
                    is LoginValidationException ->
                        appContext.getString(R.string.login_validation_fields)
                    is SessionStorageException ->
                        appContext.getString(R.string.login_storage_failed)
                    is WireGuardStorageException ->
                        appContext.getString(R.string.wg_storage_failed)
                    else -> error.message ?: appContext.getString(R.string.vm_login_failed)
                }
                reduce { state.copy(isLoggingIn = false, authError = message) }
                logs.append(appContext.getString(R.string.vm_login_error_log, message))
            }
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleLogout() {
        logout(disconnect = state.isActive)
            .onFailure { logs.append(appContext.getString(R.string.vm_logout_failed, it.message ?: "?")) }
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
        logs.append(appContext.getString(R.string.vm_logout_log))
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleNotificationPermissionResult(
        granted: Boolean,
    ) {
        if (!granted) {
            logs.append(appContext.getString(R.string.vm_notifications_disabled))
        }
        requestVpnConsent()
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleVpnConsentResult(granted: Boolean) {
        if (!granted) {
            pending = null
            val message = appContext.getString(R.string.vm_vpn_permission_required)
            reduce { state.copy(inputError = message) }
            postSideEffect(VpnSideEffect.ShowMessage(message))
            return
        }
        startTunnel()
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleModeChanged(mode: VpnMode) {
        if (state.isActive || state.isLoading || state.mode == mode) return
        reduce { state.copy(mode = mode, inputError = null) }
        settings.saveMode(mode)
            .onFailure { logs.append(appContext.getString(R.string.vm_mode_save_failed)) }
        logs.append(
            when (mode) {
                VpnMode.SIMPLIFIED -> appContext.getString(R.string.vm_mode_simplified_log)
                VpnMode.REAL -> appContext.getString(R.string.vm_mode_real_log)
            },
        )
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleWireGuardConfigRequested() {
        if (state.isActive || state.isProvisioningWireGuard) return
        provisionWireGuardProfile()
    }

    private suspend fun Syntax<VpnUiState, VpnSideEffect>.handleWireGuardConfigCleared() {
        if (state.isActive) return
        wireGuardConfig = null
        settings.clearWireGuard()
            .onFailure { logs.append(appContext.getString(R.string.vm_wg_config_delete_failed)) }
        reduce {
            state.copy(
                hasWireGuardConfig = false,
                wireGuardEndpoint = null,
                wireGuardAddress = null,
                inputError = null,
            )
        }
        logs.append(appContext.getString(R.string.vm_wg_config_removed))
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
                handleExpiredSession(appContext.getString(R.string.vm_session_expired))
            } else {
                val message = appContext.getString(
                    R.string.vm_session_refresh_failed,
                    error?.message ?: appContext.getString(R.string.vm_no_network),
                )
                logs.append(message)
                postSideEffect(VpnSideEffect.ShowMessage(message))
            }
            return null
        }

        val result = provisionWireGuard()
        val provisioned = result.getOrNull()
        if (provisioned == null) {
            val error = result.exceptionOrNull()
            val message = if (error is WireGuardStorageException) {
                appContext.getString(R.string.wg_storage_failed)
            } else {
                error?.message ?: appContext.getString(R.string.vm_wg_profile_failed)
            }
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
        logs.append(appContext.getString(R.string.vm_wg_profile_received_log))
        postSideEffect(VpnSideEffect.ShowMessage(appContext.getString(R.string.vm_wg_profile_received)))
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
            is PendingConnection.SelfMadeGuard -> {
                pending = null
                connection.connectSelfMadeGuard(request.config)
            }

            is PendingConnection.WireGuard -> {
                pending = null
                connection.connectWireGuard(request.configText)
            }

            null -> {
                postSideEffect(VpnSideEffect.ShowMessage(appContext.getString(R.string.vm_settings_lost)))
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
        data class SelfMadeGuard(val config: VpnServerConfig) : PendingConnection
        data class WireGuard(val configText: String) : PendingConnection
    }

    private companion object {
        const val STARTUP_MIN_DURATION_MS = 2_000L
    }
}
