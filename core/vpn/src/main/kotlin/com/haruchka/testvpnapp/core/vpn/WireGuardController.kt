package com.haruchka.testvpnapp.core.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.haruchka.testvpnapp.core.domain.VpnLogRepository
import com.haruchka.testvpnapp.core.domain.VpnStateRepository
import com.haruchka.testvpnapp.core.domain.WireGuardEngine
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Реальный режим на официальной библиотеке WireGuard.
 *
 * GoBackend сам создаёт VpnService, TUN и шифрует трафик Noise/ChaCha20.
 * Приложение держит отдельный foreground-сервис с уведомлением.
 */
@Singleton
class WireGuardController @Inject constructor(
    @ApplicationContext context: Context,
    private val stateStore: VpnStateRepository,
    private val logStore: VpnLogRepository,
    private val configParser: WireGuardConfigParser,
) : WireGuardEngine {

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null
    private var statsJob: Job? = null
    private var activeTunnel: Tunnel? = null

    @Volatile
    private var stopping = false

    init {
        WireGuardSessionBridge.disconnect = { disconnect() }
    }

    override fun connect(configText: String) {
        val config = try {
            configParser.parse(configText)
        } catch (e: Exception) {
            val message = e.message ?: appContext.getString(R.string.error_wireguard_invalid_config)
            stateStore.markFailed(message)
            logStore.append(message)
            return
        }

        if (activeTunnel != null || connectJob?.isActive == true) return

        stopping = false
        stateStore.markConnecting(VpnMode.REAL)
        logStore.append(appContext.getString(R.string.log_wireguard_connecting))
        runCatching {
            VpnNotificationService.start(
                appContext,
                appContext.getString(R.string.wireguard_notification_connecting),
            )
        }.onFailure {
            logStore.append(appContext.getString(R.string.log_wireguard_notification_failed))
        }

        connectJob = scope.launch {
            val tunnel = WireGuardTunnel()
            activeTunnel = tunnel
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                if (stopping) {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                } else if (!stateStore.state.value.isConnected) {
                    onUp(tunnel)
                }
            } catch (e: CancellationException) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                throw e
            } catch (e: Exception) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                activeTunnel = null
                stopStats()
                VpnNotificationService.stop(appContext)
                val message = appContext.getString(
                    R.string.error_wireguard_connect_failed,
                    e.message ?: appContext.getString(R.string.error_wireguard_connect_generic),
                )
                logStore.append(message)
                stateStore.markFailed(message)
            }
        }
    }

    override fun disconnect() {
        val tunnel = activeTunnel
        stopping = true
        stateStore.markDisconnecting()
        logStore.append(appContext.getString(R.string.log_wireguard_disconnecting))
        connectJob?.cancel()
        connectJob = null
        stopStats()

        if (tunnel == null) {
            stateStore.markDisconnected()
            VpnNotificationService.stop(appContext)
            return
        }

        scope.launch {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            activeTunnel = null
            onDown()
        }
    }

    private inner class WireGuardTunnel : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(state: Tunnel.State) {
            when (state) {
                Tunnel.State.UP -> onUp(this)
                Tunnel.State.DOWN -> onDown()
                Tunnel.State.TOGGLE -> Unit
            }
        }
    }

    private fun onUp(tunnel: Tunnel) {
        if (stateStore.state.value.isConnected) return
        stateStore.markConnected(VpnMode.REAL, System.currentTimeMillis())
        logStore.append(appContext.getString(R.string.log_wireguard_tunnel_established))
        VpnNotificationService.update(
            appContext,
            appContext.getString(R.string.wireguard_notification_connected),
        )
        startStats(tunnel)
    }

    private fun onDown() {
        val wasActive = stateStore.state.value.connection !is VpnConnectionState.Disconnected
        activeTunnel = null
        stopping = false
        stopStats()
        if (wasActive) {
            stateStore.markDisconnected()
            logStore.append(appContext.getString(R.string.log_wireguard_tunnel_disconnected))
        }
        VpnNotificationService.stop(appContext)
    }

    private fun startStats(tunnel: Tunnel) {
        stopStats()
        statsJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: continue
                stateStore.updateStats(
                    VpnTrafficStats(
                        bytesIn = stats.totalRx(),
                        bytesOut = stats.totalTx(),
                    ),
                )
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    private companion object {
        const val TUNNEL_NAME = "guardwire-real"
    }
}
