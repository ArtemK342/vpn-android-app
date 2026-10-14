package ru.fsociety.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.ProxyGoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.proxy.Socks5Proxy
import java.io.BufferedReader
import java.io.StringReader

/**
 * AmneziaWG в двух режимах:
 *  • обычный ([connect]) — библиотека сама поднимает VPN-интерфейс;
 *  • локальный прокси ([connectProxy]) — без VPN-интерфейса, только SOCKS5 на 127.0.0.1 (TCP и UDP);
 *    интерфейс держит sing-box и решает, какие сайты пускать через этот прокси (правила сайтов).
 */
object VpnManager {
    private var backend: GoBackend? = null
    private var proxyBackend: ProxyGoBackend? = null
    /** Чей сейчас туннель — ему же уходят getState / статистика / остановка. */
    @Volatile private var active: Backend? = null
    @Volatile private var currentTunnel: WgTunnel? = null
    @Volatile var connectedServerName: String = ""

    private val noActions = object : TunnelActionHandler {
        override fun runPreUp(commands: MutableCollection<String>?) {}
        override fun runPostUp(commands: MutableCollection<String>?) {}
        override fun runPreDown(commands: MutableCollection<String>?) {}
        override fun runPostDown(commands: MutableCollection<String>?) {}
    }

    fun isConnected(): Boolean {
        val tunnel = currentTunnel ?: return false
        return try { active?.getState(tunnel) == Tunnel.State.UP } catch (_: Exception) { false }
    }

    /** Работает в режиме локального прокси (VPN-интерфейс — у sing-box). */
    val isProxyMode: Boolean get() = currentTunnel != null && active === proxyBackend

    fun init(context: Context) {
        if (backend == null) backend = GoBackend(context, noActions)
    }

    private fun newTunnel(): WgTunnel {
        lateinit var tunnel: WgTunnel
        tunnel = WgTunnel("fsociety") { state ->
            // disconnect()/новое подключение обнуляют currentTunnel ДО setState(DOWN), так что
            // сюда попадает только чужое выключение: служба WireGuard остановлена системой —
            // VPN забрало другое приложение или его выключили в настройках Android. Это не
            // обрыв связи (тогда туннель остаётся «поднят» — его ловит сторож по рукопожатию):
            // без переподключения, иначе приложение отбирало бы VPN обратно.
            if (state == Tunnel.State.DOWN && currentTunnel === tunnel) {
                currentTunnel = null
                connectedServerName = ""
                VpnEvents.vpnStopped.tryEmit(Unit)
            }
        }
        return tunnel
    }

    /** Старый туннель гасим как намеренное отключение: обнуляем ДО setState(DOWN). */
    private fun stopCurrent() {
        val old = currentTunnel
        val oldBackend = active
        currentTunnel = null
        if (old != null) runCatching { oldBackend?.setState(old, Tunnel.State.DOWN, null) }
    }

    /** Обычный режим. Правила приложений (если есть) уже вписаны в конфиг — их применит библиотека. */
    suspend fun connect(context: Context, configString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            init(context)
            stopCurrent()
            val config = Config.parse(BufferedReader(StringReader(configString)))
            val tunnel = newTunnel()
            currentTunnel = tunnel
            active = backend
            backend?.setState(tunnel, Tunnel.State.UP, config)
            backend?.getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            AppErrors.log("wireguard up", e)
            currentTunnel = null
            false
        }
    }

    /**
     * Режим локального прокси: AmneziaWG (тот же конфиг и маскировка) отдаёт SOCKS5 на
     * 127.0.0.1:[port] с логином/паролем. Приложение исключено из VPN-интерфейса sing-box, поэтому
     * UDP AmneziaWG к серверу не заворачивается обратно в туннель.
     */
    suspend fun connectProxy(context: Context, configString: String, port: Int, user: String, pass: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (proxyBackend == null) proxyBackend = ProxyGoBackend(context.applicationContext, noActions)
                stopCurrent()
                val base = Config.parse(BufferedReader(StringReader(configString)))
                val config = Config.Builder()
                    .setInterface(base.getInterface())
                    .addPeers(base.peers)
                    .setDnsSettings(base.dnsSettings)
                    .addProxy(Socks5Proxy("127.0.0.1:$port", user, pass))
                    .build()
                val tunnel = newTunnel()
                currentTunnel = tunnel
                active = proxyBackend
                proxyBackend?.setState(tunnel, Tunnel.State.UP, config)
                proxyBackend?.getState(tunnel) == Tunnel.State.UP
            } catch (e: Exception) {
                AppErrors.log("wireguard proxy up", e)
                currentTunnel = null
                false
            }
        }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
            connectedServerName = ""
            stopCurrent()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Время последнего рукопожатия с сервером (мс, 0 — не было) — для сторожа ConnectionManager. */
    fun lastHandshakeMillis(): Long {
        val tunnel = currentTunnel ?: return 0L
        return try {
            val stats = active?.getStatistics(tunnel) ?: return 0L
            stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun getTrafficStats(): Pair<Long, Long> {
        val tunnel = currentTunnel ?: return Pair(0L, 0L)
        return try {
            val stats = active?.getStatistics(tunnel)
            Pair(stats?.totalRx() ?: 0L, stats?.totalTx() ?: 0L)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }
}

class WgTunnel(
    private val name: String,
    private val onStateChanged: ((Tunnel.State) -> Unit)? = null
) : Tunnel {
    override fun getName() = name
    override fun onStateChange(state: Tunnel.State) { onStateChanged?.invoke(state) }
    override fun isIpv4ResolutionPreferred(): Boolean = false
    override fun isMetered(): Boolean = false
}
