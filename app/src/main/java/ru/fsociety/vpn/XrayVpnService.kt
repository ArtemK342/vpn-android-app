package ru.fsociety.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XrayVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var pfd: ParcelFileDescriptor? = null

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config     = intent.getStringExtra(EXTRA_CONFIG)     ?: return START_NOT_STICKY
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: ""

                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID + 1,
                    buildNotification(serverName)
                )

                scope.launch {
                    val ok = SingboxManager.connect(applicationContext, this@XrayVpnService, config, serverName)
                    if (!ok) {
                        Log.e(TAG, "SingboxManager.connect failed — stopping service")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    SingboxManager.disconnect()
                    pfd?.close(); pfd = null
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Вызывается из SingboxManager.PlatformInterface.openTun().
     * sing-box передаёт параметры TUN (адреса, MTU) — мы поднимаем VPN-интерфейс
     * и возвращаем fd.
     */
    fun openTunForSingbox(options: TunOptions): Int {
        val builder = Builder()
            .setSession("FsocietySingbox")
            .setMtu(options.mtu)

        // Адреса интерфейса из конфига sing-box
        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val p = v4.next()
            builder.addAddress(p.address(), p.prefix())
            Log.d(TAG, "TUN addr: ${p.address()}/${p.prefix()}")
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val p = v6.next()
            builder.addAddress(p.address(), p.prefix())
        }

        // Маршруты: весь трафик через TUN
        builder.addRoute("0.0.0.0", 0)

        // DNS
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")

        // Исключаем наш пакет — sing-box подключается к серверу напрямую
        builder.addDisallowedApplication(packageName)

        // Configure intent
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(pi)

        pfd?.close()
        pfd = builder.establish() ?: throw IllegalStateException("VPN establish() returned null")
        Log.d(TAG, "TUN established, fd=${pfd!!.fd}")
        return pfd!!.fd
    }

    private fun buildNotification(serverName: String): android.app.Notification {
        val openPi = PendingIntent.getActivity(
            this, 11,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectPi = PendingIntent.getBroadcast(
            this, 12,
            Intent(this, VpnDisconnectReceiver::class.java).apply {
                action = VpnNotificationHelper.ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, VpnNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("[f]society VPN — VLESS подключён")
            .setContentText(serverName)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, "ОТКЛЮЧИТЬСЯ", disconnectPi)
            .build()
    }

    override fun onDestroy() {
        scope.launch { SingboxManager.disconnect() }
        pfd?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}
