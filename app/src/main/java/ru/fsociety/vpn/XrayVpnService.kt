package ru.fsociety.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class XrayVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pfd: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START = "ru.fsociety.vpn.XRAY_START"
        const val ACTION_STOP  = "ru.fsociety.vpn.XRAY_STOP"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"
        private const val TAG = "XrayVpnService"

        fun start(context: Context, configJson: String, serverName: String) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, configJson)
                putExtra(EXTRA_SERVER_NAME, serverName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config     = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: ""
                val notifId = VpnNotificationHelper.NOTIFICATION_ID + 1
                startForeground(notifId, buildNotification(serverName, 0L, 0L))
                scope.launch {
                    XrayManager.connect(applicationContext, this@XrayVpnService, config, serverName)
                    // Обновляем нотификацию с трафиком каждую секунду
                    while (isActive && XrayManager.isConnected()) {
                        val (rx, tx) = XrayManager.getTrafficStats()
                        val notif = buildNotification(serverName, rx, tx)
                        getSystemService(NotificationManager::class.java)
                            .notify(notifId, notif)
                        delay(1000)
                    }
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    XrayManager.disconnect()
                    pfd?.close()
                    pfd = null
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Создаёт TUN-интерфейс и возвращает его fd для xray */
    fun setupVpnInterface(): Long {
        return try {
            val builder = Builder()
                .setSession("FsocietyXray")
                .addAddress("26.26.26.1", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                // Исключаем наш пакет — xray подключается к серверу напрямую, минуя TUN
                .addDisallowedApplication(packageName)

            val openAppPi = PendingIntent.getActivity(
                this, 10,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setConfigureIntent(openAppPi)

            pfd?.close()
            pfd = builder.establish()
            pfd?.fd?.toLong() ?: -1L
        } catch (e: Exception) {
            Log.e(TAG, "setupVpnInterface failed: ${e.message}", e)
            -1L
        }
    }

    private fun buildNotification(serverName: String, rx: Long, tx: Long): android.app.Notification {
        val openAppPi = PendingIntent.getActivity(
            this, 11,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectPi = PendingIntent.getBroadcast(
            this, 12,
            Intent(this, VpnDisconnectReceiver::class.java).apply {
                action = VpnNotificationHelper.ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trafficText = if (rx == 0L && tx == 0L) serverName
            else "$serverName · ↓ ${VpnNotificationHelper.formatBytes(rx)} ↑ ${VpnNotificationHelper.formatBytes(tx)}"
        return androidx.core.app.NotificationCompat.Builder(this, VpnNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("[f]society VPN — VLESS подключён")
            .setContentText(trafficText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPi)
            .addAction(0, "ОТКЛЮЧИТЬСЯ", disconnectPi)
            .build()
    }

    override fun onDestroy() {
        scope.launch { XrayManager.disconnect() }
        pfd?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}
