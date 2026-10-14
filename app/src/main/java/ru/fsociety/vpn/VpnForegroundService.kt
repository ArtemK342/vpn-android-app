package ru.fsociety.vpn

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Уведомление WireGuard (трафик, «Отключить») и «фонового режима» (Отключено / «Подключить»).
 * У sing-box и OpenVPN — свои уведомления в их службах.
 */
class VpnForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var loopStarted = false

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localized(newBase))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Сразу и всегда: служба запущена через startForegroundService
        startForeground(VpnNotificationHelper.NOTIFICATION_ID, buildNotification())
        foreground = true
        // Перезапуск системой после выгрузки процесса (START_STICKY) — нужен только в фоновом режиме
        if (intent == null) wanted = getSharedPreferences("fsociety", MODE_PRIVATE).getBoolean("background_mode", false)
        if (!wanted) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Guard against multiple update loops: this service can be (re)started
        // repeatedly (app launch, background-mode toggle, disconnect), and each
        // onStartCommand must not spawn an additional 3s loop.
        if (!loopStarted) {
            loopStarted = true
            scope.launch {
                var lastState = ""
                while (true) {
                    // Трафик меняется только при подключении; в остальных состояниях уведомление
                    // перерисовываем лишь при смене состояния (раньше — каждые 3 с всегда)
                    val connected = VpnManager.isConnected()
                    val state = "$connected/${ConnectionManager.isConnecting.value}/${VpnManager.connectedServerName}/" +
                        prefs().getBoolean(ConnectionManager.PREF_VPN_CONSENT, false)
                    if (connected || state != lastState) {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        nm.notify(VpnNotificationHelper.NOTIFICATION_ID, buildNotification())
                        lastState = state
                    }
                    delay(3000)
                }
            }
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("fsociety", MODE_PRIVATE)

    private fun buildNotification(): android.app.Notification {
        val ctx = appStrings(this)  // язык приложения на момент обновления, а не запуска службы
        val isConnected = VpnManager.isConnected()
        val isConnecting = ConnectionManager.isConnecting.value
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

        // «Подключить»: если VPN уже разрешали — подключаемся в фоне (ресивер); иначе открываем
        // приложение — системное окно разрешения можно показать только из него (Android 12+ не даёт
        // открыть приложение из ресивера уведомления).
        val connectPi = if (prefs().getBoolean(ConnectionManager.PREF_VPN_CONSENT, false)) {
            PendingIntent.getBroadcast(
                this, 2,
                Intent(this, VpnConnectReceiver::class.java).apply {
                    action = VpnNotificationHelper.ACTION_CONNECT
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                this, 3,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(VpnNotificationHelper.EXTRA_AUTO_CONNECT, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, VpnNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPi)

        if (isConnected) {
            builder
                .setContentTitle(ctx.getString(R.string.notification_title_connected))
                .setContentText(
                    "${VpnManager.connectedServerName} · ↓ ${VpnNotificationHelper.formatBytes(ctx, rx)} ↑ ${VpnNotificationHelper.formatBytes(ctx, tx)}"
                )
                .addAction(0, ctx.getString(R.string.notification_disconnect), disconnectPi)
        } else if (isConnecting) {
            builder
                .setContentTitle(ctx.getString(R.string.notification_title_connecting))
                .setContentText(ctx.getString(R.string.notification_text_connecting))
                .setProgress(0, 0, true)
                .addAction(0, ctx.getString(R.string.home_btn_cancel), disconnectPi)
        } else {
            builder
                .setContentTitle(ctx.getString(R.string.notification_title_disconnected))
                .setContentText(ctx.getString(R.string.notification_text_disconnected))
                .setContentIntent(connectPi)
                .addAction(0, ctx.getString(R.string.notification_connect), connectPi)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        foreground = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** startForeground уже вызван — службу можно останавливать stopService. */
        @Volatile private var foreground = false
        /** Нужна ли служба сейчас (stop до startForeground не останавливает — см. [stop]). */
        @Volatile private var wanted = false

        fun start(context: Context) {
            wanted = true
            val intent = Intent(context, VpnForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * stopService до того, как служба вызвала startForeground, роняет приложение («did not
         * call startForeground») — тогда служба сама остановится, увидев wanted = false.
         */
        fun stop(context: Context) {
            wanted = false
            if (foreground) context.stopService(Intent(context, VpnForegroundService::class.java))
        }
    }
}
