package ru.fsociety.vpn

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class VpnForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "ru") ?: "ru"
        val locale = java.util.Locale(lang)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(VpnNotificationHelper.NOTIFICATION_ID, buildNotification())
        scope.launch {
            while (true) {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(VpnNotificationHelper.NOTIFICATION_ID, buildNotification())
                delay(3000)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val isConnected = VpnManager.isConnected()
        val isConnecting = VpnEvents.isConnecting
        val (rx, tx) = VpnManager.getTrafficStats()

        val disconnectPi = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, VpnDisconnectReceiver::class.java).apply {
                action = VpnNotificationHelper.ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val connectPi = PendingIntent.getBroadcast(
            this, 2,
            Intent(this, VpnConnectReceiver::class.java).apply {
                action = VpnNotificationHelper.ACTION_CONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, VpnNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPi)

        if (isConnected) {
            builder
                .setContentTitle(getString(R.string.notification_title_connected))
                .setContentText(
                    "${VpnManager.connectedServerName} · ↓ ${VpnNotificationHelper.formatBytes(this, rx)} ↑ ${VpnNotificationHelper.formatBytes(this, tx)}"
                )
                .addAction(0, getString(R.string.notification_disconnect), disconnectPi)
        } else if (isConnecting) {
            builder
                .setContentTitle(getString(R.string.notification_title_connecting))
                .setContentText(getString(R.string.notification_text_connecting))
                .setProgress(0, 0, true)
        } else {
            builder
                .setContentTitle(getString(R.string.notification_title_disconnected))
                .setContentText(getString(R.string.notification_text_disconnected))
                .setContentIntent(connectPi)
                .addAction(0, getString(R.string.notification_connect), connectPi)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, VpnForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpnForegroundService::class.java))
        }
    }
}
