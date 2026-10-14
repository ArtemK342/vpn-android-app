package ru.fsociety.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * AmneziaWG — клиент службы [AwgService] в отдельном процессе «:awg» (почему — там же).
 *  • обычный режим ([connect]) — библиотека сама поднимает VPN-интерфейс;
 *  • локальный прокси ([connectProxy]) — без VPN-интерфейса, только SOCKS5 на 127.0.0.1 (TCP и UDP);
 *    интерфейс держит sing-box и решает, какие сайты пускать через этот прокси (правила сайтов).
 * Пока туннель поднят, приложение держит связь со службой: так процесс «:awg» не выгружается,
 * а его падение сразу видно как обрыв.
 */
object VpnManager {
    @Volatile var connectedServerName: String = ""
    @Volatile private var up = false
    @Volatile private var proxyMode = false

    private lateinit var app: Context
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var bound: CompletableDeferred<Messenger?>? = null
    private val nextId = AtomicInteger(1)
    private val calls = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val replies = Messenger(Handler(Looper.getMainLooper()) { onMessage(it); true })

    fun isConnected(): Boolean = up

    /** Работает в режиме локального прокси (VPN-интерфейс — у sing-box). */
    val isProxyMode: Boolean get() = up && proxyMode

    private fun onMessage(m: Message) {
        when (m.what) {
            AwgService.MSG_REPLY -> calls.remove(m.arg1)?.complete(m.data ?: Bundle())
            // VPN выключили снаружи (другое VPN-приложение / настройки Android) — не обрыв
            AwgService.MSG_STOPPED -> if (up) {
                up = false
                connectedServerName = ""
                VpnEvents.vpnStopped.tryEmit(Unit)
            }
        }
    }

    private suspend fun bind(): Messenger? = withContext(Dispatchers.Main) {
        service?.let { return@withContext it }
        val deferred = bound ?: CompletableDeferred<Messenger?>().also { d ->
            bound = d
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                    val m = Messenger(binder)
                    service = m
                    try { m.send(Message.obtain(null, AwgService.MSG_REGISTER).apply { replyTo = replies }) } catch (_: Exception) {}
                    d.complete(m)
                }

                // Процесс «:awg» упал или выгружен системой — туннеля больше нет: обрыв
                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    calls.values.forEach { it.complete(Bundle()) }
                    calls.clear()
                    if (up) {
                        up = false
                        connectedServerName = ""
                        VpnEvents.vpnDropped.tryEmit(Unit)
                    }
                }
            }
            connection = conn
            val ok = try {
                app.bindService(Intent(app, AwgService::class.java), conn,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            } catch (e: Exception) { AppErrors.log("awg bind", e); false }
            if (!ok) { connection = null; bound = null; d.complete(null) }
        }
        withTimeoutOrNull(10_000) { deferred.await() }
    }

    private suspend fun unbind() = withContext(Dispatchers.Main) {
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        service = null
        bound = null
    }

    private suspend fun call(what: Int, data: Bundle = Bundle(), timeoutMs: Long = 10_000): Bundle? {
        val m = bind() ?: return null
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<Bundle>()
        calls[id] = d
        try {
            m.send(Message.obtain(null, what, id, 0).apply { this.data = data; replyTo = replies })
        } catch (e: Exception) {
            calls.remove(id)
            AppErrors.log("awg call $what", e)
            return null
        }
        return try { withTimeoutOrNull(timeoutMs) { d.await() } } finally { calls.remove(id) }
    }

    /** Обычный режим. Правила приложений (если есть) уже вписаны в конфиг — их применит библиотека. */
    suspend fun connect(context: Context, configString: String): Boolean =
        start(context, Bundle().apply { putString(AwgService.KEY_CONFIG, configString) }, proxy = false)

    /**
     * Режим локального прокси: AmneziaWG (тот же конфиг и маскировка) отдаёт SOCKS5 на
     * 127.0.0.1:[port] с логином/паролем. Приложение исключено из VPN-интерфейса sing-box, поэтому
     * UDP AmneziaWG к серверу не заворачивается обратно в туннель.
     */
    suspend fun connectProxy(context: Context, configString: String, port: Int, user: String, pass: String): Boolean =
        start(context, Bundle().apply {
            putString(AwgService.KEY_CONFIG, configString)
            putInt(AwgService.KEY_PORT, port)
            putString(AwgService.KEY_USER, user)
            putString(AwgService.KEY_PASS, pass)
        }, proxy = true)

    private suspend fun start(context: Context, data: Bundle, proxy: Boolean): Boolean {
        app = context.applicationContext
        up = false
        val ok = call(AwgService.MSG_UP, data, timeoutMs = 45_000)?.getBoolean("ok") == true
        if (ok) {
            proxyMode = proxy
            up = true
        } else {
            call(AwgService.MSG_DOWN)
            unbind()
        }
        return ok
    }

    suspend fun disconnect(): Boolean {
        connectedServerName = ""
        up = false   // до DOWN: его событие — не чужое выключение
        if (::app.isInitialized && service != null) call(AwgService.MSG_DOWN)
        if (::app.isInitialized) unbind()
        return true
    }

    /** (принято, отправлено, последнее рукопожатие мс) — для сторожа и уведомления. */
    suspend fun stats(): Triple<Long, Long, Long> {
        if (!up) return Triple(0L, 0L, 0L)
        val b = call(AwgService.MSG_STATS, timeoutMs = 3_000) ?: return Triple(0L, 0L, 0L)
        return Triple(b.getLong("rx"), b.getLong("tx"), b.getLong("hs"))
    }
}
