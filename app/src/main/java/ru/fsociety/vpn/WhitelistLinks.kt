package ru.fsociety.vpn

import android.content.Context

/**
 * Ссылки пользователя для серверов белых списков без CDN (юзер 2026-11-16) — их создаёт сам пользователь:
 *  - звонок VK («Белый список №2»): TURN-серверы звонков VK пересылают трафик AmneziaWG на наш сервер
 *    (cacggghp/vk-turn-proxy, TurnProcess);
 *  - документ Облака Mail.ru («Белый список №3»): туннель под видом совместного редактирования (OpenFlux).
 * Хранятся только на телефоне (SharedPreferences), на сервер уходит лишь ссылка на документ — для выхода.
 */
object WhitelistLinks {
    private const val PREFS = "fsociety"
    const val PREF_VK = "wl_vk_call_link"
    const val PREF_MAILRU = "wl_mailru_doc_link"
    const val PREF_ROOM = "wl_call_room_link"   // «№4»: комната Яндекс Телемоста / WB Stream (olcRTC)
    // «№3»: Mail.ru пускает в редактор публичного документа только залогиненных (401 «public is available with
    // auth only», 2026-11-16) — cookies входа в Mail.ru (окно MailruLoginDialog), только на телефоне.
    const val PREF_MAILRU_COOKIES = "wl_mailru_cookies"

    private val VK_RE = Regex("""^https://(m\.)?vk\.(com|ru)/call/join/[A-Za-z0-9_\-]{8,}([/?#].*)?$""")
    private val ROOM_RE = Regex("""^https://(telemost\.yandex\.(ru|com)/j/[0-9A-Za-z]{6,}|stream\.wb\.ru/[A-Za-z0-9/_\-]*[A-Za-z0-9_\-]{6,})/?$""")
    private val MAILRU_RE = Regex("""^https://cloud\.mail\.ru/public/[A-Za-z0-9]+/[A-Za-z0-9_\-]+/?$""")

    fun vk(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_VK, null)?.takeIf { it.isNotBlank() }

    fun mailru(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_MAILRU, null)?.takeIf { it.isNotBlank() }

    fun room(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ROOM, null)?.takeIf { it.isNotBlank() }

    /** Cookies входа в Mail.ru для cloud.mail.ru (имя → значение) или null, если вход не выполнен. */
    fun mailruCookies(context: Context): Map<String, String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_MAILRU_COOKIES, null) ?: return null
        val m = parseCookies(raw)
        return m.takeIf { isMailruSession(it) }
    }

    fun parseCookies(raw: String): Map<String, String> = raw.split(";").mapNotNull {
        val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() }.toMap()

    fun isMailruSession(m: Map<String, String>) = !m["Mpop"].isNullOrEmpty() || !m["sdcs"].isNullOrEmpty()

    fun saveMailruCookies(context: Context, raw: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_MAILRU_COOKIES, raw).apply()
    }

    fun validRoom(link: String) = ROOM_RE.matches(link.trim())
    fun validVk(link: String) = VK_RE.matches(link.trim())
    fun validMailru(link: String) = MAILRU_RE.matches(link.trim())

    fun save(context: Context, key: String, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, value?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }
}
