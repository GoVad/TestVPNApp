package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

/**
 * Точка входа фичи: связывает Orbit-ViewModel, системные permission-диалоги
 * и stateless-экран.
 */
@Composable
fun VpnRoute(
    modifier: Modifier = Modifier,
) {
    val viewModel: VpnViewModel = hiltViewModel()
    val state by viewModel.collectAsState()
    var showLogs by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    viewModel.collectSideEffect { effect ->
        when (effect) {
            is VpnSideEffect.LaunchVpnConsent -> consentLauncher.launch(effect.intent)
            VpnSideEffect.RequestNotificationPermission ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            is VpnSideEffect.ShowMessage ->
                snackbarHostState.showSnackbar(effect.message)
        }
    }

    if (state.startupState == StartupState.CHECKING) {
        StartupScreen(modifier = modifier.fillMaxSize())
        return
    }

    if (!state.isLoggedIn) {
        LoginScreen(
            initialBaseUrl = state.authBaseUrl,
            isLoading = state.isLoggingIn,
            error = state.authError,
            onInputChanged = { viewModel.onAuthInputChanged() },
            onLogin = { baseUrl, username, password ->
                viewModel.onLogin(baseUrl, username, password)
            },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (showLogs) {
        VpnLogsScreen(
            logs = state.logs,
            onClose = { showLogs = false },
            onClear = { viewModel.onLogsCleared() },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        VpnScreen(
            state = state,
            onConnect = { viewModel.onConnectClicked() },
            onDisconnect = { viewModel.onDisconnectClicked() },
            onModeChange = { viewModel.onModeChanged(it) },
            onShowLogs = { showLogs = true },
            onLogout = { viewModel.onLogout() },
            username = state.username,
            onWireGuardConfigSaved = { viewModel.onWireGuardConfigSaved(it) },
            onWireGuardConfigCleared = { viewModel.onWireGuardConfigCleared() },
            onHostChange = { viewModel.onHostChanged(it) },
            onPortChange = { viewModel.onPortChanged(it) },
            onTokenChange = { viewModel.onTokenChanged(it) },
            modifier = Modifier.padding(innerPadding),
        )
    }
}
