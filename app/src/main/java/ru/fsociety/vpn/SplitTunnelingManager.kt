package ru.fsociety.vpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Раздельное туннелирование (как в AdGuard VPN): для сайтов и для приложений — по два режима,
 * у каждого свой список (при переключении режима списки не теряются):
 *  • EXCLUDE — «через VPN всё, кроме этих»;
 *  • INCLUDE — «через VPN только эти».
 *
 * Где действует: AmneziaWG и протоколы на sing-box (VLESS, Trojan, Hysteria2). Для OpenVPN+Cloak и
 * «Режима РФ» — нет.
 *  • Приложения — штатным механизмом Android: список при поднятии VPN-интерфейса.
 *  • Сайты — по имени сайта в самом соединении (sing-box смотрит SNI / Host / DNS), с поддоменами;
 *    можно IP и подсети. AmneziaWG имён сайтов не видит, поэтому с правилами сайтов он работает как
 *    локальный прокси (wireproxy-awg из библиотеки), а VPN-интерфейс и выбор «VPN / напрямую» —
 *    у sing-box. Раньше сайты превращались в IP при подключении и вычитались из маршрутов
 *    WireGuard (тысячи маршрутов, CDN и смена IP промахивались), приложения не применялись вовсе.
 */
enum class SplitMode { EXCLUDE, INCLUDE }

data class SplitTunnelingSettings(
    val appsEnabled: Boolean = false,
    val appsMode: SplitMode = SplitMode.EXCLUDE,
    val appsExcluded: Set<String> = emptySet(),
    val appsIncluded: Set<String> = emptySet(),
    val sitesEnabled: Boolean = false,
    val sitesMode: SplitMode = SplitMode.EXCLUDE,
    val sitesExcluded: Set<String> = emptySet(),
    val sitesIncluded: Set<String> = emptySet(),
) {
    /** Список текущего режима. */
    val apps: Set<String> get() = if (appsMode == SplitMode.EXCLUDE) appsExcluded else appsIncluded
    val sites: Set<String> get() = if (sitesMode == SplitMode.EXCLUDE) sitesExcluded else sitesIncluded

    fun withApps(list: Set<String>) =
        if (appsMode == SplitMode.EXCLUDE) copy(appsExcluded = list) else copy(appsIncluded = list)
    fun withSites(list: Set<String>) =
        if (sitesMode == SplitMode.EXCLUDE) copy(sitesExcluded = list) else copy(sitesIncluded = list)

    /** Правила действуют: включены и список не пуст (пустой «только эти» ничего не меняет). */
    val appsActive: Boolean get() = appsEnabled && apps.isNotEmpty()
    val sitesActive: Boolean get() = sitesEnabled && sites.isNotEmpty()
    val anyActive: Boolean get() = appsActive || sitesActive

    /** Отпечаток действующих правил — применены ли они к текущему подключению. */
    fun fingerprint(): String = buildString {
        if (appsActive) append("A:").append(appsMode).append(':').append(apps.sorted().joinToString(","))
        append('|')
        if (sitesActive) append("S:").append(sitesMode).append(':').append(sites.sorted().joinToString(","))
    }
}

/** Готовый набор сайтов (кнопка «+ YouTube» и т.п.). */
data class SitePreset(val id: String, val titleRes: Int, val entries: Set<String>)

object Presets {
    // Записи — суффиксы: «gosuslugi.ru» включает и esia.gosuslugi.ru, и все остальные поддомены
    val ruEssential = setOf(
        "gosuslugi.ru", "nalog.ru", "nalog.gov.ru", "mos.ru", "government.ru", "kremlin.ru", "duma.gov.ru",
        "minfin.gov.ru", "rosstat.gov.ru", "fssp.gov.ru", "pfr.gov.ru", "sfr.gov.ru",
        "sberbank.ru", "sber.ru", "sberbank.com", "tinkoff.ru", "tbank.ru", "alfabank.ru", "vtb.ru",
        "gazprombank.ru", "psbank.ru", "rshb.ru", "open.ru", "mkb.ru", "rosbank.ru", "raiffeisen.ru",
        "otpbank.ru", "pochtabank.ru", "sovcombank.ru", "nspk.ru", "mironline.ru",
        "vk.com", "vk.ru", "userapi.com", "vkuser.net", "ok.ru", "mail.ru", "dzen.ru", "rutube.ru",
        "yandex.ru", "ya.ru", "yandex.net", "yandex.com", "yastatic.net", "yandexcloud.net", "kinopoisk.ru",
        "ozon.ru", "wildberries.ru", "wb.ru", "wbbasket.ru", "avito.ru", "avito.st", "youla.ru",
        "cdek.ru", "boxberry.ru", "pochta.ru", "dodo.ru", "vkusnoitochka.ru", "rostics.ru",
        "mts.ru", "megafon.ru", "beeline.ru", "t2.ru", "rt.ru",
        "2gis.ru", "auto.ru", "hh.ru", "pikabu.ru", "habr.com",
        "rbc.ru", "tass.ru", "ria.ru", "lenta.ru", "gazeta.ru", "kp.ru", "iz.ru", "vedomosti.ru", "kommersant.ru",
        "ivi.ru", "okko.tv", "start.ru", "wink.ru", "premier.one",
    )

    // Вся зона: .ru, .рф (xn--p1ai), .su
    val ruZone = setOf("ru", "xn--p1ai", "su")

    val youtube = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com", "googlevideo.com",
        "ytimg.com", "ggpht.com", "youtubei.googleapis.com", "youtube.googleapis.com",
        "youtubeembeddedplayer.googleapis.com", "yt3.googleusercontent.com", "jnn-pa.googleapis.com", "yt.be",
        "app:com.google.android.youtube", "app:com.google.android.apps.youtube.music",
    )

    /**
     * Адреса Meta (AS32934: Instagram, Facebook, Threads, WhatsApp), сведённые в подсети по анонсам RIPE
     * (stat.ripe.net announced-prefixes, 18.10.2026). Приложения Meta часто идут на IP без имени сайта —
     * без этих подсетей Instagram в «только эти» работал через раз.
     */
    private val metaCidrs = setOf(
        "31.13.24.0/21", "31.13.64.0/18", "45.64.40.0/22", "57.141.0.0/24", "57.141.2.0/23", "57.141.4.0/23",
        "57.141.6.0/24", "57.141.8.0/24", "57.141.10.0/24", "57.141.12.0/23", "57.141.14.0/24", "57.141.16.0/22",
        "57.141.20.0/24", "57.141.22.0/24", "57.141.24.0/24", "57.144.0.0/14", "66.220.144.0/20", "69.63.176.0/20",
        "69.171.224.0/19", "74.119.76.0/22", "102.132.96.0/20", "103.4.96.0/22", "129.134.0.0/17", "157.240.0.0/17",
        "157.240.192.0/18", "163.70.128.0/17", "163.77.132.0/23", "163.77.136.0/23", "163.77.160.0/20",
        "173.252.64.0/18", "179.60.192.0/22", "185.60.216.0/22", "185.89.216.0/22", "204.15.20.0/22",
    )

    val meta = setOf(
        "instagram.com", "cdninstagram.com", "instagr.am", "ig.me",
        "facebook.com", "fbcdn.net", "fb.com", "fb.me", "facebook.net", "fbsbx.com", "messenger.com", "m.me",
        "threads.net", "threads.com", "meta.com", "meta.ai",
        "app:com.instagram.android", "app:com.instagram.barcelona", "app:com.facebook.katana",
        "app:com.facebook.lite", "app:com.facebook.orca",
    ) + metaCidrs

    // Звонки и сообщения WhatsApp идут на подсети Meta без имени сайта
    val whatsapp = setOf(
        "whatsapp.com", "whatsapp.net", "wa.me",
        "app:com.whatsapp", "app:com.whatsapp.w4b",
    ) + metaCidrs

    val discord = setOf(
        "discord.com", "discord.gg", "discord.app", "discord.co", "discord.media", "discord.dev",
        "discord.gift", "discord.new", "discordapp.com", "discordapp.net", "discordcdn.com",
        "discord-activities.com", "discordactivities.com", "discordsays.com", "discordstatus.com", "dis.gd",
        "app:com.discord",   // голос — UDP на IP без имени сайта: только правилом по приложению
    )

    // Приложение Telegram ходит на свои IP без имени сайта (подсети — core.telegram.org/resources/cidr.txt), а
    // если они недоступны — на запасные адреса, которые получает на лету: их не перечислить, поэтому
    // само приложение целиком тоже через VPN (иначе после подключения минуту висело «Соединение…»)
    // Подсети: core.telegram.org/resources/cidr.txt (совпадает) + 95.161.64.0/20 из анонсов AS62041
    val telegram = setOf(
        "telegram.org", "t.me", "telegram.me", "telesco.pe", "tdesktop.com", "telegra.ph", "graph.org",
        "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22", "91.108.16.0/22", "91.108.20.0/22",
        "91.108.56.0/22", "91.105.192.0/23", "149.154.160.0/20", "185.76.151.0/24", "95.161.64.0/20",
        "app:org.telegram.messenger", "app:org.telegram.messenger.web", "app:org.thunderdog.challegram",
    )

    // Подсети X Corp (AS13414, анонсы RIPE 18.10.2026); видео и картинки — twimg.com
    val twitter = setOf(
        "x.com", "twitter.com", "twimg.com", "t.co", "twttr.com",
        "64.63.0.0/18", "103.252.112.0/22", "104.244.42.0/24", "104.244.44.0/22", "188.64.224.0/21",
        "192.133.76.0/22", "199.16.156.0/22", "199.59.148.0/22", "199.96.56.0/23", "202.160.128.0/22",
        "app:com.twitter.android",
    )

    // ИИ, закрытые для РФ самими сервисами: ChatGPT, Claude, Gemini (приложения — целиком)
    val chatgpt = setOf(
        "openai.com", "chatgpt.com", "chat.com", "oaistatic.com", "oaiusercontent.com", "sora.com",
        "claude.ai", "claude.com", "anthropic.com", "claudeusercontent.com",
        "gemini.google.com", "aistudio.google.com", "generativelanguage.googleapis.com",
        "app:com.openai.chatgpt", "app:com.anthropic.claude", "app:com.google.android.apps.bard",
    )

    /**
     * Вкладка «Приложения», режим «Через VPN только…»: заблокированное или недоступное в РФ (октябрь 2026:
     * соцсети Meta и X, мессенджеры с ограниченными звонками или блокировкой, Discord, YouTube, Snapchat,
     * Roblox, LinkedIn; ИИ и стриминги, ушедшие из РФ сами). Добавляются только установленные.
     */
    val blockedApps = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "org.thoughtcrime.securesms",
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.lite",
        "com.facebook.orca", "com.twitter.android", "com.linkedin.android", "com.snapchat.android",
        "com.discord", "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.roblox.client", "com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard",
        "com.spotify.music", "com.netflix.mediaclient", "org.torproject.torbrowser",
    )

    val all = listOf(
        SitePreset("ru", R.string.preset_ru_sites, ruEssential),
        SitePreset("ru_zone", R.string.preset_ru_zone, ruZone),
        SitePreset("youtube", R.string.preset_youtube, youtube),
        SitePreset("meta", R.string.preset_meta, meta),
        SitePreset("whatsapp", R.string.preset_whatsapp, whatsapp),
        SitePreset("discord", R.string.preset_discord, discord),
        SitePreset("telegram", R.string.preset_telegram, telegram),
        SitePreset("twitter", R.string.preset_twitter, twitter),
        SitePreset("chatgpt", R.string.preset_chatgpt, chatgpt),
    )
}

object SplitTunnelingManager {

    /**
     * Пресеты пополняются (18.10.2026: подсети Meta и X, новые домены) — у тех, кто уже включил набор,
     * дописываем новое один раз. Набор считается включённым, если в списке есть его «опорные» записи
     * из прежней версии.
     */
    private const val PRESETS_VERSION = 2
    private val presetMarkers = mapOf(
        "ru" to setOf("gosuslugi.ru", "sberbank.ru"), "ru_zone" to setOf("ru", "su"),
        "youtube" to setOf("youtube.com", "googlevideo.com"), "meta" to setOf("instagram.com", "fbcdn.net"),
        "discord" to setOf("discord.com", "discordapp.com"), "telegram" to setOf("telegram.org", "149.154.160.0/20"),
        "twitter" to setOf("x.com", "twimg.com"), "chatgpt" to setOf("openai.com", "chatgpt.com"),
    )

    private fun upgradePresets(prefs: SharedPreferences) {
        if (prefs.getInt("split_presets_version", 1) >= PRESETS_VERSION) return
        val edit = prefs.edit()
        for (key in listOf("split_sites_excluded", "split_sites_included")) {
            val list = prefs.getStringSet(key, null)?.toMutableSet() ?: continue
            for (preset in Presets.all) {
                val markers = presetMarkers[preset.id] ?: continue
                if (list.containsAll(markers)) list += preset.entries
            }
            edit.putStringSet(key, list)
        }
        edit.putInt("split_presets_version", PRESETS_VERSION).apply()
    }

    fun load(prefs: SharedPreferences): SplitTunnelingSettings {
        upgradePresets(prefs)
        val appsMode = if (prefs.getString("split_apps_mode", "EXCLUDE") == "INCLUDE") SplitMode.INCLUDE else SplitMode.EXCLUDE
        val sitesMode = if (prefs.getString("split_sites_mode", "EXCLUDE") == "INCLUDE") SplitMode.INCLUDE else SplitMode.EXCLUDE
        // До 0.0.3.8 был один список на оба режима — переносим его в список текущего режима
        val oldApps = prefs.getStringSet("split_apps_list", emptySet()).orEmpty()
        val oldSites = prefs.getStringSet("split_sites_list", emptySet()).orEmpty()
        fun list(key: String, old: Set<String>, forMode: SplitMode, mode: SplitMode) =
            prefs.getStringSet(key, null)?.toSet() ?: if (forMode == mode) old else emptySet()
        return SplitTunnelingSettings(
            appsEnabled = prefs.getBoolean("split_apps_enabled", false),
            appsMode = appsMode,
            appsExcluded = list("split_apps_excluded", oldApps, SplitMode.EXCLUDE, appsMode),
            appsIncluded = list("split_apps_included", oldApps, SplitMode.INCLUDE, appsMode),
            sitesEnabled = prefs.getBoolean("split_sites_enabled", false),
            sitesMode = sitesMode,
            sitesExcluded = list("split_sites_excluded", oldSites, SplitMode.EXCLUDE, sitesMode),
            sitesIncluded = list("split_sites_included", oldSites, SplitMode.INCLUDE, sitesMode),
        )
    }

    fun load(context: Context): SplitTunnelingSettings =
        load(context.getSharedPreferences("fsociety", Context.MODE_PRIVATE))

    fun save(prefs: SharedPreferences, s: SplitTunnelingSettings) {
        prefs.edit()
            .putBoolean("split_apps_enabled", s.appsEnabled)
            .putString("split_apps_mode", s.appsMode.name)
            .putStringSet("split_apps_excluded", s.appsExcluded)
            .putStringSet("split_apps_included", s.appsIncluded)
            .putBoolean("split_sites_enabled", s.sitesEnabled)
            .putString("split_sites_mode", s.sitesMode.name)
            .putStringSet("split_sites_excluded", s.sitesExcluded)
            .putStringSet("split_sites_included", s.sitesIncluded)
            .remove("split_apps_list")
            .remove("split_sites_list")
            .apply()
    }

    /** Протоколы, к которым применяются правила. */
    fun supports(serverType: String): Boolean =
        serverType in setOf("wireguard", "vless", "trojan", "hysteria")

    // ── Сайты: ввод ────────────────────────────────────────────────────────────

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(/(\\d{1,2}))?$")
    private val HOST = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val ZONE = Regex("^([a-z]{2,63}|xn--[a-z0-9-]{1,59})$")

    fun isIpRule(entry: String) = IPV4.matches(entry)

    /** «app:org.telegram.messenger» — всё приложение целиком (только из готовых наборов). */
    fun isAppRule(entry: String) = entry.startsWith(APP_PREFIX)
    private const val APP_PREFIX = "app:"

    /**
     * Ввод пользователя → запись правила: домен (вместе с поддоменами), зона («ru», «.рф»), IPv4 или
     * подсеть. Ссылки, «www.», порт и путь отбрасываются, кириллица — в punycode. null — не понять.
     */
    fun normalizeSite(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.replace(Regex("^[a-z][a-z0-9+.-]*://"), "")
        // Подсеть «1.2.3.0/24» — до отрезания пути ссылки (иначе «/24» терялась)
        if (!IPV4.matches(s)) {
            s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        }
        IPV4.matchEntire(s)?.let { m ->
            val octets = (1..4).map { m.groupValues[it].toInt() }
            if (octets.any { it > 255 }) return null
            val prefix = m.groupValues[6]
            if (prefix.isNotEmpty() && prefix.toInt() > 32) return null
            return if (prefix.isEmpty() || prefix == "32") octets.joinToString(".")
            else "${octets.joinToString(".")}/$prefix"
        }
        s = s.substringBefore(':').removePrefix("*.").trim('.').removePrefix("www.")
        if (s.isEmpty()) return null
        val ascii = try {
            java.net.IDN.toASCII(s, java.net.IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) { return null }
        if (!HOST.matches(ascii)) return null
        if (!ascii.contains('.') && !ZONE.matches(ascii)) return null
        return ascii
    }

    /** Для показа: punycode → кириллица, зона — «.ru». */
    fun displaySite(entry: String): String {
        if (isIpRule(entry)) return entry
        if (isAppRule(entry)) return entry.removePrefix(APP_PREFIX)
        val unicode = try { java.net.IDN.toUnicode(entry) } catch (_: Exception) { entry }
        return if (entry.contains('.')) unicode else ".$unicode"
    }

    // ── Применение ─────────────────────────────────────────────────────────────

    private fun cidr(entry: String) = if (entry.contains('/')) entry else "$entry/32"

    /**
     * Правила сайтов в конфиг sing-box. Формат наших конфигов: выход VPN — «proxy», DNS через VPN —
     * сервер с detour «proxy». [directDns] — DNS для сайтов, которые идут напрямую (провайдер / DoH):
     * так «прямые» сайты получают адреса, как без VPN (местные CDN, банки), а сайты через VPN —
     * через VPN (провайдер не подменит ответ).
     */
    fun applySiteRules(configJson: String, s: SplitTunnelingSettings, directDns: String): String {
        if (!s.sitesActive) return configJson
        val root = JSONObject(configJson)
        val domains = s.sites.filterNot { isIpRule(it) || isAppRule(it) }.sorted()
        val cidrs = s.sites.filter(::isIpRule).map(::cidr).sorted()
        val packages = s.sites.filter(::isAppRule).map { it.removePrefix(APP_PREFIX) }.sorted()
        val vpn = "proxy"
        val exclude = s.sitesMode == SplitMode.EXCLUDE
        val matched = if (exclude) "direct" else vpn
        val other = if (exclude) vpn else "direct"

        // Маршрут: sniff → DNS решает sing-box → (правила сервера: локальные сети напрямую) → наши
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        for (i in 0 until old.length()) {
            val r = old.optJSONObject(i) ?: continue
            val action = r.optString("action")
            if (action == "sniff" || action == "hijack-dns") continue
            rules.put(r)
        }
        // Приложение целиком — по владельцу соединения (SingboxManager.findConnectionOwner)
        if (packages.isNotEmpty()) rules.put(JSONObject().put("package_name", JSONArray(packages)).put("outbound", matched))
        if (domains.isNotEmpty()) rules.put(JSONObject().put("domain_suffix", JSONArray(domains)).put("outbound", matched))
        if (cidrs.isNotEmpty()) rules.put(JSONObject().put("ip_cidr", JSONArray(cidrs)).put("outbound", matched))
        route.put("rules", rules)
        route.put("final", other)

        // DNS: сайты «напрямую» — через DNS провайдера, остальные — через VPN
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var vpnDns: String? = null
        for (i in 0 until servers.length()) {
            val srv = servers.optJSONObject(i) ?: continue
            if (srv.optString("detour") == vpn) { vpnDns = srv.optString("tag"); break }
        }
        if (vpnDns.isNullOrEmpty()) {
            vpnDns = "dns-vpn"
            servers.put(JSONObject().put("tag", vpnDns).put("address", "https://1.1.1.1/dns-query")
                .put("strategy", "ipv4_only").put("detour", vpn))
        }
        servers.put(JSONObject().put("tag", "dns-direct").put("address", directDns)
            .put("strategy", "ipv4_only").put("detour", "direct"))
        val dnsRules = dns.optJSONArray("rules") ?: JSONArray()
        if (domains.isNotEmpty()) {
            dnsRules.put(JSONObject().put("domain_suffix", JSONArray(domains))
                .put("server", if (exclude) "dns-direct" else vpnDns))
        }
        dns.put("rules", dnsRules)
        dns.put("final", if (exclude) vpnDns else "dns-direct")
        return root.toString()
    }

    /**
     * Конфиг sing-box для AmneziaWG с правилами сайтов: VPN-интерфейс у sing-box, выход «proxy» —
     * локальный SOCKS5 AmneziaWG (127.0.0.1, случайный порт и пароль), остальное — напрямую.
     */
    fun awgProxyConfig(port: Int, user: String, pass: String, s: SplitTunnelingSettings, directDns: String): String {
        val base = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(
                // DoH (TCP) через туннель — не зависит от UDP через прокси
                JSONObject().put("tag", "default").put("address", "https://1.1.1.1/dns-query")
                    .put("strategy", "ipv4_only").put("detour", "proxy")
            )))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 9000).put("auto_route", true).put("strict_route", false)
                .put("stack", "system")))
            .put("outbounds", JSONArray()
                .put(JSONObject().put("type", "socks").put("tag", "proxy")
                    .put("server", "127.0.0.1").put("server_port", port).put("version", "5")
                    .put("username", user).put("password", pass))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "block").put("tag", "block")))
            .put("route", JSONObject()
                .put("rules", JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")))
                .put("final", "proxy"))
        return applySiteRules(base.toString(), s, directDns)
    }

    /**
     * Правила приложений в конфиг AmneziaWG, когда интерфейс поднимает сама библиотека (без правил
     * сайтов). Удалённые приложения отбрасываем: на них библиотека не подняла бы туннель вовсе.
     */
    fun applyAppRulesToWg(config: String, s: SplitTunnelingSettings, installed: (String) -> Boolean): String {
        if (!s.appsActive) return config
        val apps = s.apps.filter(installed)
        if (apps.isEmpty()) return config
        val key = if (s.appsMode == SplitMode.EXCLUDE) "ExcludedApplications" else "IncludedApplications"
        val lines = config.lines().filterNot {
            val t = it.trim()
            t.startsWith("ExcludedApplications") || t.startsWith("IncludedApplications")
        }.toMutableList()
        val idx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (idx < 0) return config
        lines.add(idx + 1, "$key = ${apps.joinToString(", ")}")
        return lines.joinToString("\n")
    }

    /** Установлено ли приложение (видно нам: в списке выбора только приложения с иконкой на рабочем столе). */
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: Exception) { false }
}
