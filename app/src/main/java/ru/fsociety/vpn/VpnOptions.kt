package ru.fsociety.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Настройки VPN (Настройки → Приложение):
 *  • DNS внутри VPN — Cloudflare (как было) / Google / Quad9 / AdGuard (заодно режет рекламу и трекеры);
 *  • доступ к локальной сети — принтеры, Chromecast, роутер работают при включённом AmneziaWG
 *    (у sing-box локальные адреса и так идут напрямую);
 *  • запуск при включении телефона — подключение к последнему серверу после перезагрузки.
 * DNS и локальная сеть применяются со следующего подключения.
 */
object VpnOptions {
    const val PREF_DNS = "vpn_dns"
    const val PREF_LAN = "allow_lan"
    const val PREF_AUTOSTART = "autostart_boot"

    /** Ключ → IP. Российские DNS внутри VPN не предлагаем: они свели бы приватность VPN на нет. */
    val DNS = linkedMapOf(
        "cloudflare" to "1.1.1.1",
        "google" to "8.8.8.8",
        "quad9" to "9.9.9.9",
        "adguard" to "94.140.14.14",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fsociety", Context.MODE_PRIVATE)

    fun dnsKey(ctx: Context): String = prefs(ctx).getString(PREF_DNS, "cloudflare")?.takeIf { it in DNS } ?: "cloudflare"
    fun dnsIp(ctx: Context): String = DNS[dnsKey(ctx)] ?: "1.1.1.1"
    fun allowLan(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_LAN, false)
    fun autostart(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_AUTOSTART, false)

    /**
     * Весь IPv4, кроме частных сетей 10/8, 172.16/12, 192.168/16 (стандартный список WireGuard
     * «исключить частные адреса») — они идут мимо туннеля.
     */
    private const val PUBLIC_V4 = "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, " +
        "64.0.0.0/2, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/6, 172.0.0.0/12, 172.32.0.0/11, 172.64.0.0/10, " +
        "172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, 176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, " +
        "192.169.0.0/16, 192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, 193.0.0.0/8, " +
        "194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4, 224.0.0.0/3"

    /** Конфиг AmneziaWG: выбранный DNS и (если включено) локальная сеть мимо туннеля. */
    fun patchWg(ctx: Context, config: String): String {
        val dns = dnsIp(ctx)
        val lan = allowLan(ctx)
        return config.lines().joinToString("\n") { line ->
            val key = line.substringBefore('=').trim()
            when {
                key.equals("DNS", ignoreCase = true) && line.contains('=') -> "DNS = $dns"
                lan && key.equals("AllowedIPs", ignoreCase = true) && line.contains("0.0.0.0/0") -> "AllowedIPs = $PUBLIC_V4"
                else -> line
            }
        }
    }

    /** Конфиг sing-box: DNS через VPN (сервер с detour «proxy») — выбранный. */
    fun patchSingbox(ctx: Context, configJson: String): String = try {
        val root = JSONObject(configJson)
        val servers = root.optJSONObject("dns")?.optJSONArray("servers")
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val srv = servers.optJSONObject(i) ?: continue
                if (srv.optString("detour") == "proxy") srv.put("address", dnsIp(ctx))
            }
        }
        root.toString()
    } catch (_: Exception) { configJson }
}

/** «Запуск при включении телефона»: подключиться к последнему серверу (после разблокировки). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!VpnOptions.autostart(context)) return
        ConnectionManager.onBootCompleted()
    }
}
