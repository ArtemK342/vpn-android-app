package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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

    // Встроенная стратегия (проверена 2026-10-14 у юзера: YouTube и Discord работают). Подбор — в админке.
    // • Discord (Cloudflare 162.159.128.0/18): фейк с малым TTL — серверы далеко, фейк до них не долетает,
    //   а ТСПУ обманывает. Группы перебираются по обрывам (-A t,s), рабочий TTL запоминается по IP.
    // • Остальное (YouTube): дробление TLS-записи + OOB-байт. TTL-фейки тут ломают видео (кэши Google
    //   внутри провайдера) и DoH; --md5sig ядро Android не поддерживает; позиции «+s» не годятся —
    //   SNI часто дальше первых 1500 байт (Kyber ClientHello).
    private val STRATEGY = (
        "-j :162.159.128.0/18 --fake -1 --ttl 4 --disorder 1 --udp-fake 6 " +
        "-A t,s -j :162.159.128.0/18 --fake -1 --ttl 6 --disorder 1 " +
        "-A t,s -j :162.159.128.0/18 --fake -1 --ttl 8 --disorder 1 " +
        "-A t,s -j :162.159.128.0/18 --fake -1 --ttl 12 --disorder 1 " +
        "-A n --tlsrec 3 --split 1 --oob 3 --disorder 5 --udp-fake 6"
    ).split(" ")

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
        // Капча входа (hCaptcha во встроенном браузере)
        "hcaptcha.com",
        // ECH: браузер шифрует имя сайта, наружу видно cloudflare-ech.com — его в РФ режут (как в zapret)
        "cloudflare-ech.com", "encryptedsni.com",
    )

    private var process: Process? = null
    private var runningCmd: List<String>? = null
    private var restarts = 0

    /** Стратегия из админки (/app-config → access.bypass_strategy), если она безопасна, иначе встроенная. */
    private fun strategyArgs(remote: String?): List<String> {
        val s = remote?.trim().orEmpty()
        // «/» — подсети в -j :1.2.3.0/24 (аргументы идут в byedpi напрямую, без shell)
        if (s.isEmpty() || !Regex("[A-Za-z0-9 +\\-,.:=_/]+").matches(s)) return STRATEGY
        return s.split(Regex("\\s+"))
    }

    /** Свободный порт на 127.0.0.1 (раньше фиксированный 10808 — его же занимают v2rayNG и др.). */
    private fun freePort(): Int? = try {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) { null }

    /**
     * Запустить byedpi; возвращает порт локального SOCKS5 или null (бинарника нет / сразу упал).
     * [remoteStrategy] — из админки; если с ней byedpi не стартует (опечатка), берём встроенную.
     */
    @Synchronized
    fun start(context: Context, remoteStrategy: String? = null): Int? {
        val port = freePort() ?: return null
        restarts = 0
        if (startWith(context, remoteStrategy, port)) return port
        if (remoteStrategy.isNullOrBlank()) return null
        Log.w(TAG, "remote strategy rejected, falling back to the built-in one")
        return if (startWith(context, null, port)) port else null
    }

    private fun startWith(context: Context, remoteStrategy: String?, port: Int): Boolean {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!bin.exists()) {
            Log.w(TAG, "libciadpi.so not found in ${bin.parent}")
            return false
        }
        val args = strategyArgs(remoteStrategy)
        Log.i(TAG, "strategy: ${args.joinToString(" ")}")
        val cmd = listOf(bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString()) + args
        return launch(cmd)
    }

    private fun launch(cmd: List<String>): Boolean = try {
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
            runningCmd = cmd
            watch(p)
            true
        }
    } catch (e: Exception) {
        AppErrors.log("byedpi start", e)
        false
    }

    /** byedpi упал во время работы — перезапускаем на том же порту (до 3 раз), иначе обход молча не работал. */
    private fun watch(p: Process) {
        Thread {
            val code = try { p.waitFor() } catch (_: InterruptedException) { return@Thread }
            synchronized(this) {
                if (process !== p) return@Thread       // остановлен намеренно / заменён
                process = null
                val cmd = runningCmd ?: return@Thread
                if (restarts++ < 3) {
                    Log.w(TAG, "byedpi exited unexpectedly ($code), restart $restarts/3")
                    launch(cmd)
                } else Log.e(TAG, "byedpi keeps exiting ($code), giving up")
            }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        runningCmd = null
        p?.let {
            it.destroy()
            try { if (!it.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() } catch (_: Exception) {}
        }
    }

    // DoH по порядку предпочтения; адрес — IP (в сертификатах этих серверов есть IP), без своего DNS
    private val DOH_SERVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /** DNS-запрос «youtube.com A» в формате DoH GET (RFC 8484, base64url без «=»). */
    private val DOH_PROBE: String by lazy {
        val q = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(7) + "youtube".toByteArray() + byteArrayOf(3) + "com".toByteArray() +
            byteArrayOf(0, 0, 1, 0, 1)
        android.util.Base64.encodeToString(q, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /**
     * DNS для конфига: первый DoH, который отвечает с этой сети напрямую; если все режутся — DNS
     * провайдера (лучше, чем ничего: YouTube в РФ режут не через DNS). Раньше был только DoH 1.1.1.1 —
     * если провайдер его резал, режим не запускался.
     */
    suspend fun pickDns(context: Context): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ok = kotlinx.coroutines.coroutineScope {
            DOH_SERVERS.map { ip -> async { ip to dohWorks(ip) } }.awaitAll()
        }
        ok.firstOrNull { it.second }?.let { return@withContext "https://${it.first}/dns-query" }
        val isp = ispDns(context)
        Log.w(TAG, "DoH unreachable, falling back to ${isp ?: "1.1.1.1"}")
        if (isp != null) "udp://$isp" else "https://1.1.1.1/dns-query"
    }

    /**
     * DNS для сайтов «напрямую» в правилах туннелирования: как без VPN — DNS провайдера (местные
     * адреса CDN, банков), если его нет — работающий DoH.
     */
    suspend fun directDns(context: Context): String =
        ispDns(context)?.let { "udp://$it" } ?: pickDns(context)

    private fun dohWorks(ip: String): Boolean = try {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = okhttp3.Request.Builder()
            .url("https://$ip/dns-query?dns=$DOH_PROBE")
            .header("accept", "application/dns-message")
            .build()
        client.newCall(request).execute().use { resp ->
            resp.isSuccessful && (resp.body?.bytes()?.size ?: 0) > 12   // заголовок DNS — 12 байт
        }
    } catch (e: Exception) {
        Log.w(TAG, "DoH $ip: ${e.message}")
        false
    }

    /** IPv4-адрес DNS провайдера из текущей сети (приложение исключено из своего VPN — сеть физическая). */
    private fun ispDns(context: Context): String? = try {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.dnsServers
            ?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
    } catch (_: Exception) { null }

    /**
     * Конфиг sing-box для режима обхода (тот же формат, что у серверных конфигов).
     * [port] — byedpi, [dns] — из [pickDns].
     */
    fun buildConfig(port: Int, dns: String = "https://1.1.1.1/dns-query"): String {
        val all = JSONArray(YOUTUBE + DISCORD)
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(
                // DNS напрямую, НЕ через byedpi: обход ломал DNS (фейк долетал до ближнего Cloudflare)
                JSONObject().put("tag", "doh").put("address", dns)
                    .put("strategy", "ipv4_only").put("detour", "direct")
            )))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 9000).put("auto_route", true).put("strict_route", false)
                .put("stack", "system")))
            .put("outbounds", JSONArray()
                .put(JSONObject().put("type", "socks").put("tag", "bypass")
                    .put("server", "127.0.0.1").put("server_port", port).put("version", "5"))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "block").put("tag", "block")))
            .put("route", JSONObject()
                .put("rules", JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                    // QUIC YouTube и Discord режем — приложения перейдут на TCP, где обход работает
                    .put(JSONObject().put("domain_suffix", all)
                        .put("network", "udp").put("port", 443).put("action", "reject"))
                    // Голос Discord: UDP на IP без домена (порты как в zapret) — тоже через byedpi (UDP-фейки)
                    .put(JSONObject().put("network", "udp")
                        .put("port_range", JSONArray().put("19294:19344").put("50000:50100"))
                        .put("outbound", "bypass"))
                    .put(JSONObject().put("domain_suffix", all).put("outbound", "bypass")))
                .put("final", "direct"))
        return config.toString()
    }
}
