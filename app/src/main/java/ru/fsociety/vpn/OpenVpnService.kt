package ru.fsociety.vpn

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.vpn.protocol.ProtocolState
import org.json.JSONObject

class OpenVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var openVpn: OpenVpnWithCloak? = null
    private var connectedServerName: String = ""
    private val state = MutableStateFlow(ProtocolState.UNKNOWN)
    @Volatile private var stopping = false
    private var startJob: Job? = null
    private var stopJob: Job? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localized(newBase))

    companion object {
        const val ACTION_START = "ru.fsociety.vpn.OPENVPN_START"
        const val ACTION_STOP = "ru.fsociety.vpn.OPENVPN_STOP"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"
        private const val TAG = "OpenVpnService"

        /** Служба существует (поднимает или держит туннель) — ConnectionManager ждёт её остановки. */
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

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                connectedServerName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: ""
                // Сразу: служба запущена через startForegroundService
                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID + 2,
                    buildNotification(connectedServerName)
                )
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson == null) { stopSelf(startId); return START_NOT_STICKY }
                stopping = false
                // Команды по очереди (как в XrayVpnService): новый запуск отменяет прежний,
                // запуск после STOP ждёт, пока тот погасит OpenVPN
                val prevStart = startJob
                val prevStop = stopJob
                startJob = scope.launch {
                    prevStart?.cancelAndJoin()
                    prevStop?.join()
                    run(configJson, startId)
                }
            }
            ACTION_STOP -> {
                stopping = true
                val job = startJob
                startJob = null
                val prevStop = stopJob
                stopJob = scope.launch {
                    job?.cancelAndJoin()
                    prevStop?.join()
                    stopOpenVpn()
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun run(configJson: String, startId: Int) {
        // Состояние прошлого запуска (DISCONNECTED) иначе сразу засчитывалось как «не поднялся»
        state.value = ProtocolState.UNKNOWN
        try {
            val ovpn = OpenVpnWithCloak()
            synchronized(this) { openVpn = ovpn }
            ovpn.initialize(applicationContext, state) { msg -> android.util.Log.w(TAG, msg) }
            ovpn.startVpn(
                config = JSONObject(configJson),
                vpnBuilder = Builder(),
                protect = { sock -> protect(sock) }
            )
            VpnManager.connectedServerName = connectedServerName
        } catch (e: CancellationException) {
            stopOpenVpn()
            throw e
        } catch (e: Exception) {
            AppErrors.log("openvpn start", e)
            stopOpenVpn()
            currentCoroutineContext().ensureActive()   // отменён новым запуском — итог не наш
            startResult.value = false
            stopSelf(startId)
            return
        }

        try {
            // «Подключено» — только когда OpenVPN сообщил CONNECTED (раньше — сразу после запуска)
            val connected = withTimeoutOrNull(45_000) {
                state.first { it == ProtocolState.CONNECTED || it == ProtocolState.DISCONNECTED }
            } == ProtocolState.CONNECTED
            if (!connected) {
                stopOpenVpn()
                currentCoroutineContext().ensureActive()
                startResult.value = false
                stopSelf(startId)
                return
            }
            currentCoroutineContext().ensureActive()
            startResult.value = true

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val uid = android.os.Process.myUid()
            val rxBase = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            val txBase = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            // Сторож: OpenVPN сам переподключается при потере сервера; если не вышло за 90 с
            // (время без сна телефона) — это обрыв: Kill Switch и наше переподключение
            var notConnectedSince = 0L
            while (state.value != ProtocolState.DISCONNECTED) {
                if (state.value == ProtocolState.CONNECTED) {
                    notConnectedSince = 0L
                } else if (notConnectedSince == 0L) {
                    notConnectedSince = SystemClock.uptimeMillis()
                } else if (SystemClock.uptimeMillis() - notConnectedSince > 90_000) {
                    AppErrors.log("openvpn watchdog", IllegalStateException("reconnecting for 90 s"))
                    break
                }
                val rx = (android.net.TrafficStats.getUidRxBytes(uid) - rxBase).coerceAtLeast(0)
                val tx = (android.net.TrafficStats.getUidTxBytes(uid) - txBase).coerceAtLeast(0)
                nm.notify(
                    VpnNotificationHelper.NOTIFICATION_ID + 2,
                    buildNotification(connectedServerName, rx, tx)
                )
                delay(1000)
            }
        } catch (e: CancellationException) {
            stopOpenVpn()
            throw e
        }
        stopOpenVpn()
        // Не STOP и не отзыв VPN — обрыв
        if (!stopping) VpnEvents.vpnDropped.tryEmit(Unit)
        stopSelf(startId)
    }

    /** Остановить OpenVPN этого запуска (из любого потока, повторно — без вреда). */
    private fun stopOpenVpn() {
        val ovpn = synchronized(this) { openVpn.also { openVpn = null } } ?: return
        try { ovpn.stopVpn() } catch (e: Exception) { AppErrors.log("openvpn stop", e) }
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

    // VPN забрало другое приложение или его выключили в настройках Android: это не обрыв —
    // без переподключения и Kill Switch, но приложение должно узнать, что туннеля больше нет
    override fun onRevoke() {
        stopping = true
        VpnEvents.vpnStopped.tryEmit(Unit)
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()   // запуск/цикл уведомлений этого экземпляра
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { stopOpenVpn() }
        super.onDestroy()
    }
}
