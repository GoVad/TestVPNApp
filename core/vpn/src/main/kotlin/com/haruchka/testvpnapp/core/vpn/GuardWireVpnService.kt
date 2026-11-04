package com.haruchka.testvpnapp.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.haruchka.testvpnapp.core.model.VpnMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.protocol.GuardWireClient
import com.haruchka.testvpnapp.core.protocol.GuardWireConnection
import com.haruchka.testvpnapp.core.protocol.GuardWireException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * VPN-сервис демонстрационного приложения.
 *
 * Наследуется от [VpnService], создаёт TUN-интерфейс через
 * [VpnService.Builder], устанавливает GuardWire-соединение и запускает
 * [GuardWireTunnel]. Сокет туннеля обязательно проходит через [protect],
 * иначе его пакеты попадут в собственный TUN.
 */
@AndroidEntryPoint
class GuardWireVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()

    @Inject
    lateinit var stateStore: VpnStateStore

    @Inject
    lateinit var logStore: VpnLogStore

    @Volatile
    private var stopping = false

    private var tunnel: GuardWireTunnel? = null
    private var connectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(getString(R.string.guardwire_notification_preparing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect(intent)
            ACTION_DISCONNECT -> shutdown(reason = null)
            else -> shutdown(reason = null)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        shutdown(reason = "VPN-подключение отозвано системой")
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.cancel()
        connectJob?.cancel()
        connectJob = null
        tunnel?.close()
        tunnel = null
        if (!stopping) {
            stateStore.markDisconnected()
        }
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun handleConnect(intent: Intent) {
        synchronized(lifecycleLock) {
            if (connectJob?.isActive == true || tunnel != null) return
            stopping = false
        }

        val server = intent.toServerConfig()
        if (server == null) {
            shutdown(reason = "Некорректные настройки GuardWire-сервера")
            return
        }

        logStore.append("GuardWire: подключение к ${server.endpoint}…")
        stateStore.markConnecting(VpnMode.SIMPLIFIED, server)
        updateNotification(
            getString(R.string.guardwire_notification_connecting, server.endpoint),
        )
        connectJob = scope.launch { runConnect(server) }
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
                scope = scope,
                descriptor = descriptor,
                connection = connection,
                keepAliveMillis = server.keepAliveSeconds.coerceAtLeast(5) * 1_000L,
                onStats = stateStore::updateStats,
                onFailure = { message -> shutdown(reason = message) },
            )

            synchronized(lifecycleLock) {
                if (stopping) {
                    newTunnel.close()
                    return
                }
                tunnel = newTunnel
                stateStore.markConnected(VpnMode.SIMPLIFIED, System.currentTimeMillis(), server)
                logStore.append("GuardWire: туннель установлен")
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
            shutdown(
                reason = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось подключиться",
            )
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
            ?: throw GuardWireException("VpnService.establish() вернул null")
    }

    private fun shutdown(reason: String?) {
        synchronized(lifecycleLock) {
            if (stopping) return
            stopping = true
        }

        connectJob?.cancel()
        connectJob = null
        tunnel?.close()
        tunnel = null

        if (reason == null) {
            stateStore.markDisconnected()
            logStore.append("GuardWire: туннель отключён")
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
