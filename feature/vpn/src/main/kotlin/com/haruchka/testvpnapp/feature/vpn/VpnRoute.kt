package com.haruchka.testvpnapp.feature.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onAction(VpnAction.VpnConsentResult(result.resultCode == Activity.RESULT_OK))
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onAction(VpnAction.NotificationPermissionResult(granted))
    }

    viewModel.collectSideEffect { effect ->
        when (effect) {
            is VpnSideEffect.LaunchVpnConsent -> consentLauncher.launch(effect.intent)
            VpnSideEffect.RequestNotificationPermission ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            VpnSideEffect.OpenVpnSettings -> {
                val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
            is VpnSideEffect.ShowMessage ->
                snackbarHostState.showSnackbar(effect.message)
        }
    }

    val destination = when {
        state.startupState == StartupState.CHECKING -> VpnDestination.Startup
        !state.isLoggedIn -> VpnDestination.Login
        showLogs -> VpnDestination.Logs
        else -> VpnDestination.Main
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                (
                    slideInHorizontally(
                        animationSpec = tween(260),
                        initialOffsetX = { width -> if (forward) width / 5 else -width / 5 },
                    ) + fadeIn(animationSpec = tween(220))
                    ).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(260),
                        targetOffsetX = { width -> if (forward) -width / 5 else width / 5 },
                    ) + fadeOut(animationSpec = tween(180)),
                )
            },
            label = "vpn-navigation",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { target ->
            when (target) {
                VpnDestination.Startup -> StartupScreen(modifier = Modifier.fillMaxSize())

                VpnDestination.Login -> LoginScreen(
                    initialBaseUrl = state.authBaseUrl,
                    isLoading = state.isLoggingIn,
                    error = state.authError,
                    onInputChanged = { viewModel.onAction(VpnAction.AuthInputChanged) },
                    onLogin = { baseUrl, username, password ->
                        viewModel.onAction(VpnAction.Login(baseUrl, username, password))
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                VpnDestination.Logs -> VpnLogsScreen(
                    logs = state.logs,
                    onClose = { showLogs = false },
                    onClear = { viewModel.onAction(VpnAction.LogsCleared) },
                    modifier = Modifier.fillMaxSize(),
                )

                VpnDestination.Main -> VpnScreen(
                    state = state,
                    onConnect = { viewModel.onAction(VpnAction.Connect) },
                    onDisconnect = { viewModel.onAction(VpnAction.Disconnect) },
                    onModeChange = { viewModel.onAction(VpnAction.ModeChanged(it)) },
                    onShowLogs = { showLogs = true },
                    onLogout = { viewModel.onAction(VpnAction.Logout) },
                    username = state.username,
                    onWireGuardConfigRequested = {
                        viewModel.onAction(VpnAction.WireGuardConfigRequested)
                    },
                    onWireGuardConfigCleared = {
                        viewModel.onAction(VpnAction.WireGuardConfigCleared)
                    },
                    onHostChange = { viewModel.onAction(VpnAction.HostChanged(it)) },
                    onPortChange = { viewModel.onAction(VpnAction.PortChanged(it)) },
                    onTokenChange = { viewModel.onAction(VpnAction.TokenChanged(it)) },
                    onOpenVpnSettings = { viewModel.onAction(VpnAction.OpenVpnSettings) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private enum class VpnDestination {
    Startup,
    Login,
    Main,
    Logs,
}
