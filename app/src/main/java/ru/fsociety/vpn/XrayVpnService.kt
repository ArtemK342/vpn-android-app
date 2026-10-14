package ru.fsociety.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class XrayVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var pfd: ParcelFileDescriptor? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localized(newBase))

    companion object {
        const val ACTION_START = "ru.fsociety.vpn.XRAY_START"
        const val ACTION_STOP  = "ru.fsociety.vpn.XRAY_STOP"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_APPS_MODE = "apps_mode"
        const val EXTRA_APPS = "apps"

        /** Служба существует (поднимает или держит туннель) — ConnectionManager ждёт её остановки. */
        @Volatile var isRunning = false
            private set

        /**
         * [appsMode] — правила приложений: [SplitMode.EXCLUDE] «все, кроме [apps]»,
         * [SplitMode.INCLUDE] «только [apps]», null — все приложения через VPN.
         */
        fun start(
            context: Context, configJson: String, serverName: String,
            appsMode: SplitMode? = null, apps: Collection<String> = emptyList()
        ) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, configJson)
                putExtra(EXTRA_SERVER_NAME, serverName)
                appsMode?.let { putExtra(EXTRA_APPS_MODE, it.name) }
                putExtra(EXTRA_APPS, apps.toTypedArray())
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

    @Volatile private var intentionalStop = false
    private var startJob: Job? = null
    private var stopJob: Job? = null
    // Правила приложений последнего запуска — применяются в openTunForSingbox
    @Volatile private var appsMode: SplitMode? = null
    @Volatile private var apps: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: ""
                // Сразу: служба запущена через startForegroundService
                startForeground(VpnNotificationHelper.NOTIFICATION_ID + 1, buildNotification(serverName))
                val rawConfig = intent.getStringExtra(EXTRA_CONFIG)
                if (rawConfig == null) { stopSelf(startId); return START_NOT_STICKY }
                intentionalStop = false
                appsMode = intent.getStringExtra(EXTRA_APPS_MODE)?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() }
                apps = intent.getStringArrayExtra(EXTRA_APPS)?.toList().orEmpty()
                // Apply per-device protocol tuning (Hysteria2 BBR/Brutal). No-op
                // for VLESS/Trojan configs (no hysteria2 outbound).
                val config = ProtocolSettings.patchHysteriaConfig(applicationContext, rawConfig)

                // Команды по очереди: новый запуск отменяет прежний и ждёт остановки, запуск после
                // STOP ждёт, пока тот погасит sing-box (иначе «стоп» старого туннеля гасил новый)
                val prevStart = startJob
                val prevStop = stopJob
                startJob = scope.launch {
                    prevStart?.cancelAndJoin()
                    prevStop?.join()
                    run(config, serverName, startId) { !isActive }
                }
            }
            ACTION_STOP -> {
                intentionalStop = true
                val job = startJob
                startJob = null
                val prevStop = stopJob
                stopJob = scope.launch {
                    job?.cancelAndJoin()
                    prevStop?.join()
                    SingboxManager.disconnect()
                    closeTun()
                    // только если после STOP не пришёл новый START (переключение протокола)
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun run(config: String, serverName: String, startId: Int, cancelled: () -> Boolean) {
        val ok = SingboxManager.connectVerified(applicationContext, this, config, serverName, cancelled)
        // Отменён более новым запуском или STOP — итог публикует не этот запуск
        if (cancelled()) return
        SingboxManager.startResult.value = ok
        if (!ok) { closeTun(); stopSelf(startId); return }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        while (SingboxManager.isConnected()) {
            val (rx, tx) = SingboxManager.getTrafficStats()
            nm.notify(
                VpnNotificationHelper.NOTIFICATION_ID + 1,
                buildNotification(serverName, rx, tx)
            )
            delay(1000)
        }
        // sing-box остановился сам (не STOP и не отзыв VPN) — обрыв: Kill Switch и переподключение
        if (!intentionalStop) VpnEvents.vpnDropped.tryEmit(Unit)
        closeTun()
        stopSelf(startId)
    }

    private fun closeTun() {
        val p = pfd
        pfd = null
        try { p?.close() } catch (_: Exception) {}
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

        // Приложения: «только эти» / «все, кроме этих». Само приложение — всегда вне туннеля:
        // sing-box (и AmneziaWG в режиме прокси) подключаются к серверу напрямую.
        // Разрешённые и запрещённые нельзя смешивать — в «только эти» нас просто нет в списке.
        val listed = apps.filter { it != packageName }
        if (appsMode == SplitMode.INCLUDE) {
            var any = false
            for (p in listed) {
                try { builder.addAllowedApplication(p); any = true }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) {}   // удалено
            }
            if (!any) builder.addDisallowedApplication(packageName)
        } else {
            builder.addDisallowedApplication(packageName)
            if (appsMode == SplitMode.EXCLUDE) {
                for (p in listed) {
                    try { builder.addDisallowedApplication(p) }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) {}
                }
            }
        }

        // Configure intent
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(pi)

        // Новый интерфейс заменяет старый, старый fd закрываем после — без окна без VPN
        val old = pfd
        val fd = builder.establish() ?: throw IllegalStateException("VPN establish() returned null")
        pfd = fd
        try { old?.close() } catch (_: Exception) {}
        return fd.fd
    }

    private fun buildNotification(serverName: String, rx: Long = 0L, tx: Long = 0L): android.app.Notification {
        val ctx = appStrings(this)  // язык приложения на момент обновления, а не запуска службы
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
        intentionalStop = true
        VpnEvents.vpnStopped.tryEmit(Unit)
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()   // цикл уведомлений больше не нужен
        // Только свой sing-box: новый экземпляр службы мог уже поднять свой
        val self = this
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { SingboxManager.disconnectIfOwner(self) }
        closeTun()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}
