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

    private var commandServer: CommandServer? = null
    private var setupDone = false
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

            // Проверяем конфиг до запуска
            try {
                Libbox.checkConfig(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "Config invalid: ${e.message}")
                return false
            }

            val server = CommandServer(makeServerHandler(), makePlatformInterface(service))
            server.startOrReloadService(configJson, OverrideOptions())

            commandServer = server
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
        serverName: String
    ): Boolean = synchronized(startLock) {
        // Параллельные запуски убивали друг другу sing-box, а одновременные
        // вызовы в libbox роняли Go-рантайм ("fatal error: unknown caller pc").
        val port = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        } catch (_: Exception) { 0 }
        val user = UUID.randomUUID().toString()
        val pass = UUID.randomUUID().toString()
        val probeConfig = if (port > 0) buildProbeConfig(configJson, port, user, pass) else null
        if (probeConfig != null) {
            if (!connect(context, service, probeConfig, serverName)) return@synchronized false
            val works = probeThroughProxy(port, user, pass)
            disconnect()
            if (!works) {
                Log.e(TAG, "probe failed: server does not pass traffic")
                return@synchronized false
            }
        }
        connect(context, service, configJson, serverName)
    }

    private val startLock = Any()

    /** Тот же конфиг, но вместо TUN — локальный mixed-прокси с авторизацией. */
    private fun buildProbeConfig(configJson: String, port: Int, user: String, pass: String): String? =
        try {
            val root = JSONObject(configJson)
            val inbound = JSONObject()
                .put("type", "mixed")
                .put("tag", "probe-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", port)
                .put("users", JSONArray().put(JSONObject().put("username", user).put("password", pass)))
            root.put("inbounds", JSONArray().put(inbound))
            root.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildProbeConfig: ${e.message}")
            null
        }

    private fun probeThroughProxy(port: Int, user: String, pass: String): Boolean {
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
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "closeService error: ${e.message}")
        }
        commandServer = null
        connectedServerName = ""
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
        override fun serviceStop() { disconnect() }
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

        override fun findConnectionOwner(
            ipProtocol: Int, sourceAddress: String, sourcePort: Int,
            destAddress: String, destPort: Int
        ): ConnectionOwner = ConnectionOwner()

        override fun getInterfaces(): NetworkInterfaceIterator =
            object : NetworkInterfaceIterator {
                override fun hasNext() = false
                override fun next() = throw NoSuchElementException()
            }

        override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")

        override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener) {}
        override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener) {}
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
