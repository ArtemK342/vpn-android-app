package ru.fsociety.vpn

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
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
 * AmneziaWG — в отдельном процессе «:awg» (как и VPN-служба библиотеки, см. манифест).
 *
 * В одном процессе с sing-box (libbox) он ронял приложение: оба написаны на Go, и на Android 10+
 * оба рантайма держат текущую горутину в одной и той же ячейке памяти потока (TLS_SLOT_APP).
 * Стоило потоку или сигналу попасть из одного рантайма в другой — «fatal error: unknown caller pc»
 * и процесс завершался без отчёта. Так падали режим прокси с правилами сайтов (оба работают
 * одновременно) и переключение AmneziaWG ↔ VLESS без перезапуска приложения.
 *
 * Основной процесс управляет этой службой через Messenger ([VpnManager]); все вызовы библиотеки —
 * по очереди в одном потоке.
 */
class AwgService : Service() {

    companion object {
        const val MSG_REGISTER = 1
        const val MSG_UP = 2
        const val MSG_DOWN = 3
        const val MSG_STATS = 4
        const val MSG_REPLY = 10
        const val MSG_STOPPED = 11

        const val KEY_CONFIG = "config"
        const val KEY_PORT = "port"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
    }

    private val noActions = object : TunnelActionHandler {
        override fun runPreUp(commands: MutableCollection<String>?) {}
        override fun runPostUp(commands: MutableCollection<String>?) {}
        override fun runPreDown(commands: MutableCollection<String>?) {}
        override fun runPostDown(commands: MutableCollection<String>?) {}
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    @Volatile private var client: Messenger? = null
    private var goBackend: GoBackend? = null
    private var proxyBackend: ProxyGoBackend? = null
    private var active: Backend? = null
    @Volatile private var tunnel: WgTunnel? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("awg").apply { start() }
        handler = Handler(thread.looper) { handle(it); true }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // Приложение отвязалось (отключились) — туннель не оставляем
        handler.post { down() }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val reply = Bundle()
        when (msg.what) {
            MSG_REGISTER -> { client = msg.replyTo; return }
            MSG_UP -> {
                val d = msg.data
                reply.putBoolean("ok", up(d.getString(KEY_CONFIG).orEmpty(), d.getInt(KEY_PORT, 0),
                    d.getString(KEY_USER).orEmpty(), d.getString(KEY_PASS).orEmpty()))
            }
            MSG_DOWN -> { down(); reply.putBoolean("ok", true) }
            MSG_STATS -> {
                val t = tunnel
                val b = active
                reply.putBoolean("up", t != null && runCatching { b?.getState(t) == Tunnel.State.UP }.getOrDefault(false))
                if (t != null && b != null) {
                    try {
                        val stats = b.getStatistics(t)
                        reply.putLong("rx", stats.totalRx())
                        reply.putLong("tx", stats.totalTx())
                        reply.putLong("hs", stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L)
                    } catch (_: Exception) {}
                }
            }
            else -> return
        }
        try {
            msg.replyTo?.send(Message.obtain(null, MSG_REPLY, msg.arg1, 0).apply { data = reply })
        } catch (_: Exception) {}
    }

    /** [port] > 0 — режим локального прокси (SOCKS5 на 127.0.0.1:[port]), иначе свой VPN-интерфейс. */
    private fun up(configString: String, port: Int, user: String, pass: String): Boolean = try {
        down()
        val base = Config.parse(BufferedReader(StringReader(configString)))
        val t = newTunnel()
        tunnel = t
        if (port > 0) {
            val b = proxyBackend ?: ProxyGoBackend(applicationContext, noActions).also { proxyBackend = it }
            val config = Config.Builder()
                .setInterface(base.getInterface())
                .addPeers(base.peers)
                .setDnsSettings(base.dnsSettings)
                .addProxy(Socks5Proxy("127.0.0.1:$port", user, pass))
                .build()
            active = b
            b.setState(t, Tunnel.State.UP, config)
            b.getState(t) == Tunnel.State.UP
        } else {
            val b = goBackend ?: GoBackend(applicationContext, noActions).also { goBackend = it }
            active = b
            b.setState(t, Tunnel.State.UP, base)
            b.getState(t) == Tunnel.State.UP
        }
    } catch (e: Exception) {
        AppErrors.log("awg up", e)
        tunnel = null
        false
    }

    /** Намеренная остановка: tunnel обнуляется ДО setState(DOWN) — колбэк не примет это за чужое выключение. */
    private fun down() {
        val t = tunnel ?: return
        tunnel = null
        runCatching { active?.setState(t, Tunnel.State.DOWN, null) }
    }

    private fun newTunnel(): WgTunnel {
        lateinit var t: WgTunnel
        t = WgTunnel("fsociety") { state ->
            // Сюда попадает только чужое выключение: VPN-службу библиотеки остановила система —
            // VPN забрало другое приложение или его выключили в настройках Android
            if (state == Tunnel.State.DOWN && tunnel === t) {
                tunnel = null
                try { client?.send(Message.obtain(null, MSG_STOPPED)) } catch (_: Exception) {}
            }
        }
        return t
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

/** «Постоянная VPN» Android запустила службу AmneziaWG (процесс «:awg») — подключение решает основной процесс. */
class AlwaysOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConnectionManager.onAlwaysOnTriggered()
    }
}
