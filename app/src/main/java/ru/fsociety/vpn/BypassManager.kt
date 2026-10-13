package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Режим РФ» (server_type = "bypass"): обход блокировок YouTube/Discord БЕЗ нашего сервера.
 *
 * Трафик идёт через локальный VPN (sing-box TUN, XrayVpnService): домены YouTube/Discord —
 * в byedpi (libciadpi.so, локальный SOCKS5 на 127.0.0.1), который портит пакеты так, чтобы
 * ТСПУ не распознал сайт, и отправляет их напрямую; всё остальное — напрямую как есть.
 * Приложение исключено из TUN (addDisallowedApplication), поэтому сокеты byedpi не зацикливаются.
 */
object BypassManager {
    private const val TAG = "BypassManager"
    const val SOCKS_PORT = 10808

    // Встроенная стратегия — без TCP-фейков: фейк с малым TTL долетает до кэш-серверов Google внутри
    // сети провайдера (2–4 хопа) и ломает соединение (видео YouTube, DoH). Подбор — через админку.
    private val STRATEGY = listOf(
        "--split", "1", "--disorder", "3",
        "--udp-fake", "6",  // без позиций «+s»: SNI часто дальше первых 1500 байт (Kyber ClientHello)
    )

    // Списки из zapret-discord-youtube (lists/list-general.txt, list-google.txt)
    private val YOUTUBE = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com", "googlevideo.com",
        "ytimg.com", "ggpht.com", "youtubei.googleapis.com", "youtube.googleapis.com",
        "youtubeembeddedplayer.googleapis.com", "yt3.googleusercontent.com", "jnn-pa.googleapis.com",
        "wide-youtube.l.google.com", "youtube-ui.l.google.com", "yt-video-upload.l.google.com",
        "ytimg.l.google.com",
    )
    private val DISCORD = listOf(
        "discord.com", "discord.gg", "discord.app", "discord.co", "discord.media", "discord.dev",
        "discord.gift", "discord.gifts", "discord.new", "discord.store", "discord.status",
        "discord.design", "dis.gd", "discordapp.com", "discordapp.net", "discordcdn.com",
        "discord-activities.com", "discordactivities.com", "discordsays.com", "discordstatus.com",
        "discordmerch.com", "discordpartygames.com", "discordsez.com",
        "discord-attachments-uploads-prd.storage.googleapis.com",
    )

    private var process: Process? = null

    /** Стратегия из админки (/app-config → access.bypass_strategy), если она безопасна, иначе встроенная. */
    private fun strategyArgs(remote: String?): List<String> {
        val s = remote?.trim().orEmpty()
        if (s.isEmpty() || !Regex("[A-Za-z0-9 +\\-,.:=_]+").matches(s)) return STRATEGY
        return s.split(Regex("\\s+"))
    }

    /** Запустить byedpi; false — бинарника нет или он сразу упал. [remoteStrategy] — из админки. */
    @Synchronized
    fun start(context: Context, remoteStrategy: String? = null): Boolean {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!bin.exists()) {
            Log.w(TAG, "libciadpi.so not found in ${bin.parent}")
            return false
        }
        return try {
            val args = strategyArgs(remoteStrategy)
            Log.i(TAG, "strategy: ${args.joinToString(" ")}")
            val cmd = listOf(bin.absolutePath, "-i", "127.0.0.1", "-p", SOCKS_PORT.toString()) + args
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // Вывод byedpi — в logcat (иначе буфер заполнится и процесс встанет)
            Thread {
                try { p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) } } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            Thread.sleep(300)
            if (!p.isAlive) {
                Log.w(TAG, "byedpi exited with ${p.exitValue()}")
                false
            } else {
                process = p
                true
            }
        } catch (e: Exception) {
            AppErrors.log("byedpi start", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        process?.let { p ->
            p.destroy()
            try { if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
        }
        process = null
    }

    /** Конфиг sing-box для режима обхода (тот же формат, что у серверных конфигов). */
    fun buildConfig(): String {
        val all = JSONArray(YOUTUBE + DISCORD)
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(
                // DoH напрямую, НЕ через byedpi: обход ломал DNS (фейк долетал до ближнего Cloudflare)
                JSONObject().put("tag", "doh").put("address", "https://1.1.1.1/dns-query")
                    .put("strategy", "ipv4_only").put("detour", "direct")
            )))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 9000).put("auto_route", true).put("strict_route", false)
                .put("stack", "system")))
            .put("outbounds", JSONArray()
                .put(JSONObject().put("type", "socks").put("tag", "bypass")
                    .put("server", "127.0.0.1").put("server_port", SOCKS_PORT).put("version", "5"))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "block").put("tag", "block")))
            .put("route", JSONObject()
                .put("rules", JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                    // QUIC YouTube режем — плеер перейдёт на TCP, где обход работает надёжнее
                    .put(JSONObject().put("domain_suffix", JSONArray(YOUTUBE))
                        .put("network", "udp").put("port", 443).put("action", "reject"))
                    .put(JSONObject().put("domain_suffix", all).put("outbound", "bypass")))
                .put("final", "direct"))
        return config.toString()
    }
}
