package ru.fsociety.vpn

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

enum class SplitMode { EXCLUDE, INCLUDE }

data class SplitTunnelingSettings(
    val appsEnabled: Boolean = false,
    val appsMode: SplitMode = SplitMode.EXCLUDE,
    val selectedApps: Set<String> = emptySet(),
    val sitesEnabled: Boolean = false,
    val sitesMode: SplitMode = SplitMode.EXCLUDE,
    val selectedDomains: Set<String> = emptySet()
)

object Presets {
    val ruEssential = setOf(
        "gosuslugi.ru", "esia.gosuslugi.ru", "nalog.ru", "lkfl2.nalog.ru",
        "mos.ru", "pgu.mos.ru", "government.ru", "kremlin.ru", "duma.gov.ru",
        "minfin.gov.ru", "rosstat.gov.ru", "fssp.gov.ru",
        "sberbank.ru", "sber.ru", "online.sberbank.ru",
        "tinkoff.ru", "alfabank.ru", "vtb.ru", "gazprombank.ru",
        "psbank.ru", "rshb.ru", "open.ru", "mkb.ru", "rosbank.ru",
        "unicreditbank.ru", "otpbank.ru", "pochtabank.ru",
        "vk.com", "vk.ru", "m.vk.com", "api.vk.com", "video.vk.com", "music.vk.com",
        "ok.ru", "mail.ru", "e.mail.ru", "cloud.mail.ru",
        "dzen.ru", "rutube.ru",
        "yandex.ru", "ya.ru", "yandex.net",
        "mail.yandex.ru", "disk.yandex.ru", "maps.yandex.ru",
        "taxi.yandex.ru", "market.yandex.ru", "music.yandex.ru",
        "passport.yandex.ru", "storage.yandexcloud.net", "s3.yandexcloud.net",
        "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "youla.ru",
        "cdek.ru", "boxberry.ru", "pochta.ru",
        "dodo.ru", "vkusnoitochka.ru", "kfc.ru", "rostics.ru",
        "mts.ru", "megafon.ru", "beeline.ru", "t2.ru",
        "2gis.ru", "auto.ru", "hh.ru", "pikabu.ru", "habr.com",
        "rbc.ru", "tass.ru", "ria.ru", "lenta.ru", "gazeta.ru",
        "kp.ru", "iz.ru", "vedomosti.ru", "kommersant.ru",
        "kinopoisk.ru", "ivi.ru", "okko.tv",
        "cdn.vk.com", "cdn.mail.ru",
        "spb.ru", "msk.ru", "ekb.ru", "nn.ru", "sochi.ru"
    )
}

object SplitTunnelingManager {

    fun load(prefs: SharedPreferences): SplitTunnelingSettings = SplitTunnelingSettings(
        appsEnabled = prefs.getBoolean("split_apps_enabled", false),
        appsMode = if (prefs.getString("split_apps_mode", "EXCLUDE") == "INCLUDE") SplitMode.INCLUDE else SplitMode.EXCLUDE,
        selectedApps = prefs.getStringSet("split_apps_list", emptySet()) ?: emptySet(),
        sitesEnabled = prefs.getBoolean("split_sites_enabled", false),
        sitesMode = if (prefs.getString("split_sites_mode", "EXCLUDE") == "INCLUDE") SplitMode.INCLUDE else SplitMode.EXCLUDE,
        selectedDomains = prefs.getStringSet("split_sites_list", emptySet()) ?: emptySet()
    )

    fun save(prefs: SharedPreferences, settings: SplitTunnelingSettings) {
        prefs.edit()
            .putBoolean("split_apps_enabled", settings.appsEnabled)
            .putString("split_apps_mode", settings.appsMode.name)
            .putStringSet("split_apps_list", settings.selectedApps)
            .putBoolean("split_sites_enabled", settings.sitesEnabled)
            .putString("split_sites_mode", settings.sitesMode.name)
            .putStringSet("split_sites_list", settings.selectedDomains)
            .apply()
    }

    // Извлекаем домен из URL или оставляем как есть если это уже домен
    fun extractDomain(input: String): String {
        val cleaned = input.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        return cleaned.substringBefore("/").substringBefore("?").substringBefore("#")
    }

    // Резолвим все IP для списка доменов
    suspend fun resolveIps(domains: Set<String>): List<String> = withContext(Dispatchers.IO) {
        domains.flatMap { domain ->
            try {
                InetAddress.getAllByName(domain)
                    .filter { it.address.size == 4 } // только IPv4
                    .map { it.hostAddress ?: "" }
                    .filter { it.isNotEmpty() }
            } catch (_: Exception) { emptyList() }
        }.distinct()
    }

    // Модифицируем AllowedIPs в конфиге WireGuard
    suspend fun applyToConfig(configString: String, settings: SplitTunnelingSettings): String {
        if (!settings.sitesEnabled || settings.selectedDomains.isEmpty()) return configString

        val resolvedIps = resolveIps(settings.selectedDomains)
        if (resolvedIps.isEmpty()) return configString

        val newAllowedIps = when (settings.sitesMode) {
            SplitMode.INCLUDE -> {
                // Только трафик на выбранные IP идёт через VPN
                resolvedIps.joinToString(", ") { "$it/32" }
            }
            SplitMode.EXCLUDE -> {
                // Весь трафик через VPN кроме выбранных IP
                val allRoutes = mutableListOf("0.0.0.0/0")
                for (ip in resolvedIps) {
                    excludeIpFromRoutes(allRoutes, ip)
                }
                allRoutes.joinToString(", ")
            }
        }

        // Заменяем AllowedIPs в секции [Peer]
        return configString.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("AllowedIPs")) {
                "AllowedIPs = $newAllowedIps"
            } else line
        }
    }

    // CIDR math: вычитаем конкретный IP из списка маршрутов
    private fun excludeIpFromRoutes(routes: MutableList<String>, ip: String) {
        val targetInt = ipToInt(ip)
        val idx = routes.indexOfFirst { cidr ->
            val (net, prefix) = parseCidr(cidr)
            containsIp(net, prefix, targetInt)
        }
        if (idx == -1) return
        val (net, prefix) = parseCidr(routes[idx])
        routes.removeAt(idx)
        // Разбиваем маршрут на sub-routes исключая targetInt
        splitExclude(net, prefix, targetInt, routes)
    }

    private fun splitExclude(net: Int, prefix: Int, exclude: Int, result: MutableList<String>) {
        if (prefix == 32) return // /32 — это и есть исключаемый IP, просто не добавляем
        val newPrefix = prefix + 1
        val left = net  // левая половина: prefix+1, та же сеть
        val right = net or (1 shl (31 - prefix)) // правая половина
        if (containsIp(left, newPrefix, exclude)) {
            // exclude в левой — правую добавляем целиком, левую рекурсивно разбиваем
            result.add("${intToIp(right)}/$newPrefix")
            splitExclude(left, newPrefix, exclude, result)
        } else {
            // exclude в правой — левую добавляем целиком, правую разбиваем
            result.add("${intToIp(left)}/$newPrefix")
            splitExclude(right, newPrefix, exclude, result)
        }
    }

    private fun containsIp(net: Int, prefix: Int, ip: Int): Boolean {
        if (prefix == 0) return true
        val mask = (-1 shl (32 - prefix))
        return (net and mask) == (ip and mask)
    }

    private fun parseCidr(cidr: String): Pair<Int, Int> {
        val parts = cidr.trim().split("/")
        return Pair(ipToInt(parts[0]), parts.getOrElse(1) { "32" }.toInt())
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.trim().split(".")
        return (parts[0].toInt() shl 24) or (parts[1].toInt() shl 16) or
               (parts[2].toInt() shl 8) or parts[3].toInt()
    }

    private fun intToIp(i: Int): String =
        "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"
}
