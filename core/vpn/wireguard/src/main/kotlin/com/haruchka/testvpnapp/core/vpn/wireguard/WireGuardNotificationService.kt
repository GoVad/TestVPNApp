package com.haruchka.testvpnapp.core.vpn.wireguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Мостик между уведомлением и WireGuard-контроллером.
 *
 * Библиотека WireGuard сама не показывает foreground-уведомление, поэтому
 * приложение держит отдельный foreground-сервис. Кнопка «Отключить» в нём
 * вызывает зарегистрированный контроллер.
 */
object WireGuardSessionBridge {
    @Volatile
    var disconnect: (() -> Unit)? = null
}

/** Foreground-уведомление для режима WireGuard. */
class WireGuardNotificationService : android.app.Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(getString(R.string.wireguard_notification_connecting))
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WireGuardSessionBridge.disconnect?.invoke()
                stopSelf()
            }

            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT)
                    ?: getString(R.string.wireguard_notification_connected)
                updateNotification(text)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wireguard_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.wireguard_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, WireGuardNotificationService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, REQUEST_OPEN, it, flags)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wireguard)
            .setContentTitle(getString(R.string.wireguard_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.wireguard_notification_disconnect), stopIntent)
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
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val CHANNEL_ID = "guardwire_wireguard"
        private const val NOTIFICATION_ID = 1002
        private const val REQUEST_OPEN = 20
        private const val REQUEST_STOP = 21
        private const val ACTION_STOP = "com.haruchka.testvpnapp.action.WIREGUARD_STOP"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, WireGuardNotificationService::class.java)
                .putExtra(EXTRA_TEXT, text)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, text: String) {
            val intent = Intent(context, WireGuardNotificationService::class.java)
                .putExtra(EXTRA_TEXT, text)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WireGuardNotificationService::class.java))
        }
    }
}
