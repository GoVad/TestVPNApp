package com.haruchka.testvpnapp.core.vpn.guardwire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.haruchka.testvpnapp.core.domain.VpnLogRepository
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStateRepository
import com.haruchka.testvpnapp.core.domain.usecase.EnsureAuthSessionUseCase
import com.haruchka.testvpnapp.core.model.VpnMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.guardwire.protocol.GuardWireClient
import com.haruchka.testvpnapp.core.guardwire.protocol.GuardWireConnection
import com.haruchka.testvpnapp.core.guardwire.protocol.GuardWireException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * VPN-сервис демонстрационного приложения.
 *
 * Наследуется от [VpnService], создаёт TUN-интерфейс через
 * [VpnService.Builder], устанавливает GuardWire-соединение и запускает
 * [GuardWireTunnel]. Сокет туннеля обязательно проходит через [protect],
 * иначе его пакеты попадут в собственный TUN.
 *
 * Сервис также поддерживает системный always-on: Android запускает его с
 * action [VpnService.SERVICE_INTERFACE], после чего сервис сам читает
 * сохранённый профиль и продлевает auth-сессию.
 */
@AndroidEntryPoint
class GuardWireVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()

    @Inject
    lateinit var stateStore: VpnStateRepository

    @Inject
    lateinit var logStore: VpnLogRepository

    @Inject
    lateinit var settingsStore: VpnSettingsStore

    @Inject
    lateinit var ensureAuthSession: EnsureAuthSessionUseCase

    @Volatile
    private var stopping = false

    private var tunnel: GuardWireTunnel? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var serverConfig: VpnServerConfig? = null
    private var reconnectAttempt = 0
    private var networkLost = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
        startForegroundCompat(getString(R.string.guardwire_notification_preparing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val server = intent.toServerConfig()
                if (server == null) {
                    shutdown(reason = getString(R.string.error_guardwire_invalid_settings))
                } else {
                    handleConnect(server)
                }
            }

            ACTION_DISCONNECT -> shutdown(reason = null)

            VpnService.SERVICE_INTERFACE -> handleAlwaysOn()

            else -> shutdown(reason = null)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        shutdown(reason = getString(R.string.error_guardwire_revoked))
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        scope.cancel()
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        tunnel?.close()
        tunnel = null
        serverConfig = null
        if (!stopping) {
            stateStore.markDisconnected()
        }
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun handleConnect(server: VpnServerConfig) {
        synchronized(lifecycleLock) {
            if (connectJob?.isActive == true || tunnel != null || reconnectJob?.isActive == true) return
            stopping = false
            serverConfig = server
            reconnectAttempt = 0
            networkLost = false
        }

        logStore.append(getString(R.string.log_guardwire_connecting, server.endpoint))
        stateStore.markConnecting(VpnMode.SIMPLIFIED, server)
        updateNotification(
            getString(R.string.guardwire_notification_connecting, server.endpoint),
        )
        startConnect(server)
    }

    /**
     * Always-on запускается системой без extras. Берём сохранённый GuardWire-
     * профиль и актуальный access-токен; при необходимости продлеваем сессию.
     */
    private fun handleAlwaysOn() {
        scope.launch {
            val stored = runCatching { settingsStore.load() }.getOrNull()
            if (stored == null) {
                shutdown(reason = getString(R.string.error_guardwire_settings_load_failed))
                return@launch
            }
            if (stored.mode != VpnMode.SIMPLIFIED) {
                shutdown(reason = getString(R.string.error_guardwire_always_on_mode))
                return@launch
            }

            val session = ensureAuthSession().getOrNull()
            if (session == null) {
                shutdown(reason = getString(R.string.error_guardwire_session_expired))
                return@launch
            }

            val config = stored.guardWire.copy(token = session.accessToken)
            if (config.host.isBlank() || config.token.isBlank()) {
                shutdown(reason = getString(R.string.error_guardwire_no_profile))
                return@launch
            }
            handleConnect(config)
        }
    }

    private fun startConnect(server: VpnServerConfig) {
        connectJob = scope.launch {
            val session = ensureAuthSession().getOrNull()
            if (session == null && server.token.isBlank()) {
                shutdown(reason = getString(R.string.error_guardwire_session_expired))
                return@launch
            }
            val actual = if (session != null) {
                server.copy(token = session.accessToken)
            } else {
                server
            }
            serverConfig = actual
            runConnect(actual)
        }
    }

    private suspend fun runConnect(server: VpnServerConfig) {
        var connection: GuardWireConnection? = null
        var descriptor: ParcelFileDescriptor? = null

        try {
            val client = GuardWireClient(
                config = server,
                protectSocket = { socket -> protect(socket) },
            )
            connection = client.connect()
            currentCoroutineContext().ensureActive()

            descriptor = establishTun(server)
            currentCoroutineContext().ensureActive()

            val newTunnel = GuardWireTunnel(
                context = this@GuardWireVpnService,
                scope = scope,
                descriptor = descriptor,
                connection = connection,
                keepAliveMillis = server.keepAliveSeconds.coerceAtLeast(5) * 1_000L,
                onStats = stateStore::updateStats,
                onFailure = { message ->
                    // Запускаем отдельную корутину: закрытие tunnel отменит
                    // корутину, из которой пришёл onFailure.
                    scope.launch { scheduleReconnect(message) }
                },
            )

            synchronized(lifecycleLock) {
                if (stopping) {
                    newTunnel.close()
                    return
                }
                tunnel = newTunnel
                reconnectAttempt = 0
                networkLost = false
                stateStore.markConnected(VpnMode.SIMPLIFIED, System.currentTimeMillis(), server)
                logStore.append(getString(R.string.log_guardwire_tunnel_established))
                updateNotification(
                    getString(R.string.guardwire_notification_connected, server.endpoint),
                )
            }

            // Владение ресурсами перешло к tunnel.
            connection = null
            descriptor = null
            newTunnel.start()
        } catch (e: CancellationException) {
            runCatching { connection?.close() }
            runCatching { descriptor?.close() }
            throw e
        } catch (e: Exception) {
            runCatching { connection?.close() }
            runCatching { descriptor?.close() }
            val reason = e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.error_guardwire_connect_failed)
            if (stopping) {
                shutdown(reason = reason)
            } else {
                scope.launch { scheduleReconnect(reason) }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        var target: VpnServerConfig? = null
        var delayMillis = 0L
        var shouldStop = false

        synchronized(lifecycleLock) {
            if (stopping || serverConfig == null) return
            if (reconnectJob?.isActive == true) return
            if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                shouldStop = true
            } else {
                reconnectAttempt++
                delayMillis = reconnectDelayMillis(reconnectAttempt)
                target = serverConfig
                tunnel?.close()
                tunnel = null
                connectJob?.cancel()
                connectJob = null
            }
        }

        if (shouldStop) {
            shutdown(reason = getString(R.string.error_guardwire_reconnect_failed, reason))
            return
        }

        val server = target ?: return
        stateStore.markConnecting(VpnMode.SIMPLIFIED, server)
        logStore.append(
            getString(
                R.string.log_guardwire_reconnecting,
                (delayMillis / 1_000).toInt(),
                reason,
            ),
        )
        updateNotification(
            getString(R.string.guardwire_notification_connecting, server.endpoint),
        )
        reconnectJob = scope.launch {
            delay(delayMillis)
            if (stopping) return@launch
            startConnect(server)
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val seconds = 1L shl (attempt - 1).coerceIn(0, 5)
        return seconds.coerceAtMost(MAX_RECONNECT_DELAY_SECONDS) * 1_000L
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (stopping || serverConfig == null) return
                networkLost = true
                scope.launch { scheduleReconnect(getString(R.string.log_guardwire_network_unavailable)) }
            }

            override fun onAvailable(network: Network) {
                if (!networkLost || stopping || serverConfig == null) return
                networkLost = false
                scope.launch { scheduleReconnect(getString(R.string.log_guardwire_network_restored)) }
            }
        }
        networkCallback = callback
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { logStore.append(getString(R.string.log_guardwire_network_monitor_failed)) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    private fun establishTun(server: VpnServerConfig): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("GuardWire Demo")
            .setMtu(server.mtu)
            .addAddress(server.tunnelAddress, server.tunnelPrefixLength)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(server.dnsServer)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return builder.establish()
            ?: throw GuardWireException(getString(R.string.error_guardwire_establish_null))
    }

    private fun shutdown(reason: String?) {
        synchronized(lifecycleLock) {
            if (stopping) return
            stopping = true
            serverConfig = null
            reconnectJob?.cancel()
            reconnectJob = null
            connectJob?.cancel()
            connectJob = null
            tunnel?.close()
            tunnel = null
        }

        if (reason == null) {
            stateStore.markDisconnected()
            logStore.append(getString(R.string.log_guardwire_tunnel_disconnected))
        } else {
            stateStore.markFailed(reason)
            logStore.append("GuardWire: $reason")
        }

        stopForegroundCompat()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.guardwire_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.guardwire_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, REQUEST_OPEN_APP, it, flags)
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            disconnectIntent(this),
            flags,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guardwire)
            .setContentTitle(getString(R.string.guardwire_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.guardwire_notification_disconnect), disconnectPendingIntent)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            type,
        )
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun Intent.toServerConfig(): VpnServerConfig? {
        val host = getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val port = getIntExtra(EXTRA_PORT, -1)
        if (host.isEmpty() || port !in 1..65_535) return null

        return VpnServerConfig(
            host = host,
            port = port,
            token = getStringExtra(EXTRA_TOKEN).orEmpty(),
            mtu = getIntExtra(EXTRA_MTU, 1_400).coerceIn(576, 9_000),
            dnsServer = getStringExtra(EXTRA_DNS).orEmpty().ifBlank { "1.1.1.1" },
            keepAliveSeconds = getIntExtra(EXTRA_KEEP_ALIVE, 15).coerceIn(5, 300),
        )
    }

    companion object {
        const val ACTION_CONNECT = "com.haruchka.testvpnapp.action.GUARDWIRE_CONNECT"
        const val ACTION_DISCONNECT = "com.haruchka.testvpnapp.action.GUARDWIRE_DISCONNECT"

        private const val CHANNEL_ID = "guardwire_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_DISCONNECT = 11
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val MAX_RECONNECT_DELAY_SECONDS = 30L

        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_MTU = "mtu"
        private const val EXTRA_DNS = "dns"
        private const val EXTRA_KEEP_ALIVE = "keep_alive"

        fun connectIntent(context: Context, config: VpnServerConfig): Intent =
            Intent(context, GuardWireVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, config.host)
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_TOKEN, config.token)
                putExtra(EXTRA_MTU, config.mtu)
                putExtra(EXTRA_DNS, config.dnsServer)
                putExtra(EXTRA_KEEP_ALIVE, config.keepAliveSeconds)
            }

        fun disconnectIntent(context: Context): Intent =
            Intent(context, GuardWireVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
    }
}
