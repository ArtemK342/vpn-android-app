package ru.fsociety.vpn

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * OpenVPN + Cloak — в отдельном процессе «:ovpn» (см. манифест), как AmneziaWG в «:awg».
 *
 * Cloak (libck-ovpn-plugin) и sing-box (libbox) написаны на Go: два Go-рантайма в одном процессе
 * роняли приложение (SIGSEGV в runtime.cgocallback) при переключении Hysteria2/VLESS/Trojan ↔ OpenVPN
 * без перезапуска приложения. После падения в шторке оставалось «зависшее» уведомление OpenVPN рядом
 * с уведомлением sing-box.
 *
 * Основной процесс запускает и останавливает службу намерениями (как раньше), а итоги получает
 * широковещательными сообщениями ([OpenVpnEventReceiver]). Жива ли служба — по списку служб
 * приложения: так видно и падение процесса «:ovpn».
 */
object OvpnBridge {
    private const val KIND = "kind"
    private const val OK = "ok"
    private const val RESULT = "result"
    private const val DROPPED = "dropped"
    private const val STOPPED = "stopped"

    /** Уведомление службы OpenVPN (VpnNotificationHelper.NOTIFICATION_ID + 2). */
    const val NOTIFICATION_ID = VpnNotificationHelper.NOTIFICATION_ID + 2

    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    /** Служба OpenVPN существует (поднимает или держит туннель) — в любом процессе приложения. */
    fun serviceRunning(): Boolean {
        val ctx = app ?: return false
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
            @Suppress("DEPRECATION")   // своё приложение Android по-прежнему показывает
            am.getRunningServices(Int.MAX_VALUE).any {
                // pid 0 — процесс «:ovpn» ещё запускается (служба уже запрошена) или упал и ждёт перезапуска
                it.service.className == OpenVpnService::class.java.name &&
                    (it.pid != 0 || (it.started && it.restarting == 0L))
            }
        } catch (_: Exception) { false }
    }

    /** Уведомление OpenVPN без службы (процесс «:ovpn» упал во время запуска) — убрать из шторки. */
    fun cancelStaleNotification() {
        val ctx = app ?: return
        if (serviceRunning()) return
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
    }

    // ── Из процесса «:ovpn» в основной ──

    fun sendResult(context: Context, ok: Boolean) = send(context, RESULT) { putExtra(OK, ok) }
    /** Туннель упал сам — Kill Switch и переподключение (VpnEvents.vpnDropped). */
    fun sendDropped(context: Context) = send(context, DROPPED)
    /** VPN забрали снаружи (VpnEvents.vpnStopped). */
    fun sendStopped(context: Context) = send(context, STOPPED)

    private fun send(context: Context, kind: String, extra: Intent.() -> Unit = {}) {
        try {
            context.sendBroadcast(Intent(context, OpenVpnEventReceiver::class.java).putExtra(KIND, kind).apply(extra))
        } catch (e: Exception) { AppErrors.log("openvpn event $kind", e) }
    }

    internal fun handle(intent: Intent) {
        when (intent.getStringExtra(KIND)) {
            RESULT -> OpenVpnService.startResult.value = intent.getBooleanExtra(OK, false)
            DROPPED -> VpnEvents.vpnDropped.tryEmit(Unit)
            STOPPED -> VpnEvents.vpnStopped.tryEmit(Unit)
        }
    }
}

/** События службы OpenVPN (процесс «:ovpn») — в основном процессе. */
class OpenVpnEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = OvpnBridge.handle(intent)
}
