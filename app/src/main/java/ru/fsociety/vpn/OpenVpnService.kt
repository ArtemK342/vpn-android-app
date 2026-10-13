package ru.fsociety.vpn

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.amnezia.vpn.protocol.ProtocolState
import org.json.JSONObject

class OpenVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var openVpn: OpenVpnWithCloak? = null
    private var connectedServerName: String = ""
    private val state = MutableStateFlow(ProtocolState.UNKNOWN)
    @Volatile private var stopping = false

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "ru") ?: "ru"
        val locale = java.util.Locale(lang)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    companion object {
        const val ACTION_START = "ru.fsociety.vpn.OPENVPN_START"
        const val ACTION_STOP = "ru.fsociety.vpn.OPENVPN_STOP"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Итог запуска: null — идёт, true — OpenVPN сообщил CONNECTED, false — не поднялся. */
        val startResult = MutableStateFlow<Boolean?>(null)

        fun start(context: Context, configJson: String, serverName: String) {
            val intent = Intent(context, OpenVpnService::class.java).apply {
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
            context.startService(Intent(context, OpenVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                stopping = false
                val configJson = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                connectedServerName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: ""

                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID + 2,
                    buildNotification(connectedServerName)
                )

                startResult.value = null
                scope.launch {
                    try {
                        val ovpn = OpenVpnWithCloak()
                        ovpn.initialize(applicationContext, state) { msg -> android.util.Log.w("OpenVpnService", msg) }
                        val builder = Builder()
                        val parsedConfig = JSONObject(configJson)
                        openVpn = ovpn
                        isRunning = true
                        ovpn.startVpn(
                            config = parsedConfig,
                            vpnBuilder = builder,
                            protect = { sock -> protect(sock) }
                        )
                        VpnManager.connectedServerName = connectedServerName
                    } catch (e: Exception) {
                        AppErrors.log("openvpn start", e)
                        startResult.value = false
                        stopSelf()
                        return@launch
                    }

                    // «Подключено» — только когда OpenVPN сообщил CONNECTED (раньше — сразу после запуска)
                    val connected = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                        state.first { it == ProtocolState.CONNECTED || it == ProtocolState.DISCONNECTED }
                    } == ProtocolState.CONNECTED
                    if (!connected) {
                        try { openVpn?.stopVpn() } catch (e: Exception) { AppErrors.log("openvpn stop", e) }
                        openVpn = null
                        startResult.value = false
                        stopSelf()
                        return@launch
                    }
                    startResult.value = true

                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val uid = android.os.Process.myUid()
                    val rxBase = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                    val txBase = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                    while (state.value != ProtocolState.DISCONNECTED) {
                        val rx = (android.net.TrafficStats.getUidRxBytes(uid) - rxBase).coerceAtLeast(0)
                        val tx = (android.net.TrafficStats.getUidTxBytes(uid) - txBase).coerceAtLeast(0)
                        nm.notify(
                            VpnNotificationHelper.NOTIFICATION_ID + 2,
                            buildNotification(connectedServerName, rx, tx)
                        )
                        delay(1000)
                    }
                    if (!stopping) VpnEvents.vpnDropped.tryEmit(Unit)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopping = true
                scope.launch {
                    try { openVpn?.stopVpn() } catch (e: Exception) { AppErrors.log("openvpn stop", e) }
                    openVpn = null
                    VpnEvents.disconnectRequested.tryEmit(Unit)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(serverName: String, rx: Long = 0L, tx: Long = 0L): android.app.Notification {
        val ctx = appStrings(this)  // язык приложения на момент обновления, а не запуска службы
        val openPi = android.app.PendingIntent.getActivity(
            this, 21,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectPi = android.app.PendingIntent.getBroadcast(
            this, 22,
            Intent(this, VpnDisconnectReceiver::class.java).apply {
                action = VpnNotificationHelper.ACTION_DISCONNECT
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, VpnNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle(ctx.getString(R.string.notification_title_connected))
            .setContentText(
                if (rx == 0L && tx == 0L) serverName
                else "$serverName · ↓ ${VpnNotificationHelper.formatBytes(ctx, rx)} ↑ ${VpnNotificationHelper.formatBytes(ctx, tx)}"
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, ctx.getString(R.string.notification_disconnect), disconnectPi)
            .build()
    }

    // Другое VPN-приложение забрало VPN: это не обрыв — без переподключения и Kill Switch
    override fun onRevoke() {
        stopping = true
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        val ovpn = openVpn
        openVpn = null
        scope.cancel()   // цикл уведомлений больше не нужен
        if (ovpn != null) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try { ovpn.stopVpn() } catch (e: Exception) { AppErrors.log("openvpn stop", e) }
            }
        }
        super.onDestroy()
    }
}
