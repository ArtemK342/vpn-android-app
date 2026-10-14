package ru.fsociety.vpn

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

object SingboxManager {

    private const val TAG = "SingboxManager"

    // 204 через прокси = трафик реально проходит через сервер.
    // Только HTTPS: cleartext HTTP запрещён network security policy (targetSdk ≥ 28).
    private val PROBE_URLS = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204"
    )

    /** Результат последнего запуска из XrayVpnService: null — ещё идёт, true/false — итог. */
    val startResult = MutableStateFlow<Boolean?>(null)

    @Volatile private var commandServer: CommandServer? = null
    /** Служба, чей sing-box сейчас работает (onDestroy старого экземпляра не гасит новый). */
    @Volatile private var owner: XrayVpnService? = null
    private val stateLock = Any()

    /** Локальный прокси рабочего sing-box для сторожа «сервер перестал отвечать». */
    private class ProbeProxy(val port: Int, val user: String, val pass: String)
    @Volatile private var monitorProxy: ProbeProxy? = null
    private var setupDone = false
    @Volatile private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** StringIterator для libbox из обычного списка. */
    private class StringList(items: List<String>) : StringIterator {
        private val list = items
        private var i = 0
        override fun hasNext() = i < list.size
        override fun len() = list.size
        override fun next() = list[i++]
    }
    var connectedServerName: String = ""

    // Трафик: baseline при подключении
    private var baseRx = 0L
    private var baseTx = 0L

    fun isConnected(): Boolean = commandServer != null

    fun connect(
        context: Context,
        service: XrayVpnService,
        configJson: String,
        serverName: String
    ): Boolean {
        return try {
            disconnect()

            // Инициализируем один раз
            if (!setupDone) {
                val opts = SetupOptions()
                opts.basePath    = context.filesDir.absolutePath
                opts.workingPath = context.filesDir.absolutePath
                opts.tempPath    = context.cacheDir.absolutePath
                opts.fixAndroidStack = true
                Libbox.setup(opts)
                setupDone = true
            }

            // auto_detect_interface: sing-box следит за основной сетью (монитор выше) и при смене
            // Wi-Fi ↔ мобильный перестраивает соединения; сокеты защищаются через protect()
            val configJson = try {
                val root = JSONObject(configJson)
                val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                if (!route.has("auto_detect_interface")) route.put("auto_detect_interface", true)
                // Журнал соединений sing-box (files/logs/singbox.log): DEV-сборка (adb run-as)
                // и «Журнал: максимум» в настройках (для отправки в поддержку)
                if (BuildConfig.DEBUG || AppLog.mode(context) == LogMode.MAX) {
                    val logFile = AppLog.singboxFile(context)
                    if (logFile.length() > 2_000_000) logFile.delete()
                    root.put("log", JSONObject().put("level", "info").put("timestamp", true)
                        .put("output", logFile.absolutePath))
                }
                root.toString()
            } catch (_: Exception) { configJson }

            // Проверяем конфиг до запуска
            try {
                Libbox.checkConfig(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "Config invalid: ${e.message}")
                return false
            }

            val server = CommandServer(makeServerHandler(), makePlatformInterface(service))
            server.startOrReloadService(configJson, OverrideOptions())

            synchronized(stateLock) {
                commandServer = server
                owner = service
            }
            connectedServerName = serverName
            // Сохраняем baseline трафика в момент подключения
            val uid = Process.myUid()
            baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            false
        }
    }

    /**
     * Запуск с проверкой: сначала sing-box стартует БЕЗ TUN, только с локальным
     * mixed-прокси (127.0.0.1, случайный порт и пароль), и через него делается
     * HTTP-запрос через сервер. TUN поднимается только если запрос прошёл —
     * иначе UI показывал «подключено», а трафик не шёл (напр. сломанный Reality).
     */
    fun connectVerified(
        context: Context,
        service: XrayVpnService,
        configJson: String,
        serverName: String,
        cancelled: () -> Boolean = { false }
    ): Boolean = synchronized(startLock) {
        // Параллельные запуски убивали друг другу sing-box, а одновременные
        // вызовы в libbox роняли Go-рантайм ("fatal error: unknown caller pc").
        val port = freeLocalPort()
        val user = UUID.randomUUID().toString()
        val pass = UUID.randomUUID().toString()
        val probeConfig = if (port > 0) withProxyInbound(configJson, port, user, pass, onlyProxy = true) else null
        if (probeConfig != null) {
            // Две попытки, каждая с новым экземпляром sing-box (новое соединение):
            // первый QUIC-хендшейк Hysteria2 из РФ иногда теряется целиком, сервер
            // его даже не видит, а повторная попытка проходит сразу.
            var works = false
            for (attempt in 1..2) {
                // «Отмена» / новый запуск: не доводим до TUN — иначе он заменил бы чужой туннель
                if (cancelled()) return@synchronized false
                if (!connect(context, service, probeConfig, serverName)) return@synchronized false
                works = probeThroughProxy(port, user, pass, cancelled)
                disconnect()
                if (works) break
                Log.w(TAG, "probe attempt $attempt failed")
            }
            if (!works) {
                Log.e(TAG, "probe failed: server does not pass traffic")
                return@synchronized false
            }
        }
        if (cancelled()) return@synchronized false
        // Рабочий конфиг + тот же локальный прокси (127.0.0.1, случайный порт и пароль) для сторожа
        val livePort = freeLocalPort()
        val live = if (livePort > 0) withProxyInbound(configJson, livePort, user, pass, onlyProxy = false) else null
        val ok = connect(context, service, live ?: configJson, serverName)
        if (ok && live != null) monitorProxy = ProbeProxy(livePort, user, pass)
        ok
    }

    private val startLock = Any()

    private fun freeLocalPort(): Int = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) { 0 }

    /**
     * Конфиг с локальным mixed-прокси с авторизацией: [onlyProxy] — вместо TUN (проверка до
     * запуска), иначе — рядом с TUN (сторож проверяет через него, что сервер отвечает).
     */
    private fun withProxyInbound(configJson: String, port: Int, user: String, pass: String, onlyProxy: Boolean): String? =
        try {
            val root = JSONObject(configJson)
            val inbound = JSONObject()
                .put("type", "mixed")
                .put("tag", "probe-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", port)
                .put("users", JSONArray().put(JSONObject().put("username", user).put("password", pass)))
            val inbounds = if (onlyProxy) JSONArray() else (root.optJSONArray("inbounds") ?: JSONArray())
            root.put("inbounds", inbounds.put(inbound))
            // Проверка и сторож — всегда через VPN-выход «proxy»: с правилом «через VPN только эти
            // сайты» по умолчанию всё идёт напрямую, и проверка ничего бы не проверила
            val outbounds = root.optJSONArray("outbounds")
            val hasProxy = outbounds != null &&
                (0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == "proxy" }
            if (hasProxy) {
                val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                val old = route.optJSONArray("rules") ?: JSONArray()
                val rules = JSONArray().put(JSONObject().put("inbound", JSONArray().put("probe-in")).put("outbound", "proxy"))
                for (i in 0 until old.length()) rules.put(old.get(i))
                route.put("rules", rules)
            }
            root.toString()
        } catch (e: Exception) {
            Log.e(TAG, "withProxyInbound: ${e.message}")
            null
        }

    /**
     * Сторож: проходит ли сейчас запрос через сервер (через локальный прокси работающего sing-box).
     * Нет прокси или sing-box уже остановлен — проверять нечего, считаем живым.
     */
    fun probeAlive(): Boolean {
        val m = monitorProxy ?: return true
        if (commandServer == null) return true
        return probeThroughProxy(m.port, m.user, m.pass) { false }
    }

    private fun probeThroughProxy(port: Int, user: String, pass: String, cancelled: () -> Boolean): Boolean {
        val auth = Credentials.basic(user, pass)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", auth).build()
            }
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        for (url in PROBE_URLS) {
            if (cancelled()) return false
            try {
                // Авторизацию для CONNECT добавляет proxyAuthenticator (OkHttp зовёт его превентивно)
                val request = Request.Builder().url(url).build()
                // Только 204 от generate_204: ошибка выхода у прокси (напр. 502) — не успех
                val code = client.newCall(request).execute().use { it.code }
                if (code == 204) return true
                Log.w(TAG, "probe $url: HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "probe $url failed: ${e.message}")
            }
        }
        return false
    }

    fun disconnect() {
        synchronized(stateLock) {
            try {
                commandServer?.closeService()
            } catch (e: Exception) {
                Log.e(TAG, "closeService error: ${e.message}")
            }
            commandServer = null
            owner = null
            monitorProxy = null
            connectedServerName = ""
        }
    }

    /** Из onDestroy службы: гасим, только если работает её sing-box, а не уже нового экземпляра. */
    fun disconnectIfOwner(service: XrayVpnService) {
        synchronized(stateLock) {
            if (owner !== service) return
            disconnect()
        }
    }

    // Трафик-статистика — дельта от момента подключения
    fun getTrafficStats(): Pair<Long, Long> {
        if (commandServer == null) return Pair(0L, 0L)
        val uid = Process.myUid()
        val rx = (TrafficStats.getUidRxBytes(uid) - baseRx).coerceAtLeast(0L)
        val tx = (TrafficStats.getUidTxBytes(uid) - baseTx).coerceAtLeast(0L)
        return Pair(rx, tx)
    }

    // ── CommandServerHandler ────────────────────────────────────────────────

    private fun makeServerHandler() = object : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().also { it.setAvailable(false); it.setEnabled(false) }
        override fun serviceReload() {}
        // Не в потоке libbox: disconnect() ждёт closeService(), который может ждать этот колбэк
        override fun serviceStop() { Thread { disconnect() }.start() }
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun writeDebugMessage(msg: String) {}
    }

    // ── PlatformInterface ───────────────────────────────────────────────────

    private fun makePlatformInterface(service: XrayVpnService) = object : PlatformInterface {

        /** sing-box вызывает openTun() при старте — мы поднимаем VPN-интерфейс */
        override fun openTun(options: TunOptions): Int {
            return service.openTunForSingbox(options)
        }

        /** sing-box просит защитить свои сокеты от зацикливания через TUN */
        override fun autoDetectInterfaceControl(fd: Int) {
            service.protect(fd)
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun useProcFS(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun underNetworkExtension(): Boolean = false

        /**
         * Чьё соединение — для правил по приложению (package_name: «Telegram целиком через VPN»,
         * включая его запасные адреса, которых нет ни в каком списке). Android 10+, мы — VPN-приложение.
         */
        override fun findConnectionOwner(
            ipProtocol: Int, sourceAddress: String, sourcePort: Int,
            destAddress: String, destPort: Int
        ): ConnectionOwner {
            val owner = ConnectionOwner()
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return owner
            try {
                val cm = service.getSystemService(android.net.ConnectivityManager::class.java) ?: return owner
                val uid = cm.getConnectionOwnerUid(ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destAddress, destPort))
                if (uid != Process.INVALID_UID) {
                    owner.userId = uid
                    val pkgs = service.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
                    if (pkgs.isNotEmpty()) owner.setAndroidPackageNames(StringList(pkgs))
                }
            } catch (_: Exception) {}
            return owner
        }

        /** Сетевые интерфейсы устройства (раньше — пустой список). */
        override fun getInterfaces(): NetworkInterfaceIterator {
            val cm = service.getSystemService(android.net.ConnectivityManager::class.java)
            val javaIfaces = try { java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
            @Suppress("DEPRECATION")
            for (network in cm?.allNetworks.orEmpty()) {
                val lp = cm?.getLinkProperties(network) ?: continue
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                val name = lp.interfaceName ?: continue
                val ji = javaIfaces.firstOrNull { it.name == name } ?: continue
                val box = io.nekohasekai.libbox.NetworkInterface()
                box.name = name
                box.index = ji.index
                try { box.mtu = ji.mtu } catch (_: Exception) {}
                box.addresses = StringList(ji.interfaceAddresses.mapNotNull { a ->
                    a.address?.hostAddress?.substringBefore('%')?.let { "$it/${a.networkPrefixLength}" }
                })
                box.dnsServer = StringList(lp.dnsServers.mapNotNull { it.hostAddress })
                box.type = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                var flags = 0
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    flags = android.system.OsConstants.IFF_UP or android.system.OsConstants.IFF_RUNNING
                if (ji.isLoopback) flags = flags or android.system.OsConstants.IFF_LOOPBACK
                if (ji.isPointToPoint) flags = flags or android.system.OsConstants.IFF_POINTOPOINT
                if (ji.supportsMulticast()) flags = flags or android.system.OsConstants.IFF_MULTICAST
                box.flags = flags
                box.metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                result.add(box)
            }
            val it = result.iterator()
            return object : NetworkInterfaceIterator {
                override fun hasNext() = it.hasNext()
                override fun next() = it.next()
            }
        }

        override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")

        /**
         * Основная сеть устройства: при смене Wi-Fi ↔ мобильный интернет sing-box узнаёт новый
         * интерфейс. Раньше монитор был пустым — после смены сети туннель мог «замереть».
         * Приложение исключено из своего VPN, поэтому default network для него — физическая сеть.
         */
        override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener) {
            val cm = service.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            fun report(network: android.net.Network?) {
                val name = network?.let { cm.getLinkProperties(it)?.interfaceName }
                if (name == null) {
                    try { l.updateDefaultInterface("", -1, false, false) } catch (_: Exception) {}
                    return
                }
                val index = try { java.net.NetworkInterface.getByName(name)?.index ?: -1 } catch (_: Exception) { -1 }
                val caps = cm.getNetworkCapabilities(network)
                val expensive = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                try { l.updateDefaultInterface(name, index, expensive, false) } catch (e: Exception) {
                    Log.w(TAG, "updateDefaultInterface: ${e.message}")
                }
            }
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = report(network)
                override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) = report(network)
                override fun onLost(network: android.net.Network) = report(null)
            }
            networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            networkCallback = cb
            try { cm.registerDefaultNetworkCallback(cb) } catch (e: Exception) { Log.w(TAG, "network callback: ${e.message}") }
            report(cm.activeNetwork)
        }

        override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener) {
            val cm = service.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            networkCallback = null
        }
        override fun clearDNSCache() {}

        override fun localDNSTransport(): LocalDNSTransport =
            object : LocalDNSTransport {
                override fun raw() = false
                override fun lookup(ctx: ExchangeContext, network: String, domain: String) {}
                override fun exchange(ctx: ExchangeContext, message: ByteArray) {}
            }

        override fun systemCertificates(): StringIterator =
            object : StringIterator {
                override fun hasNext() = false
                override fun len() = 0
                override fun next() = ""
            }

        override fun sendNotification(n: Notification) {}
    }
}
