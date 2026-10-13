package ru.fsociety.vpn

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Токены входа — одно место на весь процесс (UI, службы, ресиверы уведомлений).
 *
 * Access-токен живёт сутки. Раньше он обновлялся только при открытии главного экрана, и если
 * приложение было свёрнуто дольше суток (с VPN процесс живёт долго), все запросы получали 401
 * «Сессия истекла» до перезапуска. Теперь OkHttp на 401 сам берёт новый токен по refresh
 * ([refreshBlocking]) и повторяет запрос, а UI подхватывает новый токен из [access].
 */
object TokenStore {
    private const val PREFS = "fsociety"
    private lateinit var prefs: SharedPreferences

    /** Текущий access-токен ("" — не вошли). */
    val access = MutableStateFlow("")

    /** Сервер отклонил refresh-токен (сессия кончилась/аккаунт заблокирован) — выйти на экран входа. */
    val sessionExpired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        access.value = prefs.getString("token", "") ?: ""
    }

    val refresh: String get() = prefs.getString("refresh_token", "") ?: ""

    @Synchronized
    fun save(accessToken: String, refreshToken: String?) {
        prefs.edit().apply {
            putString("token", accessToken)
            if (!refreshToken.isNullOrEmpty()) putString("refresh_token", refreshToken)
        }.apply()
        access.value = accessToken
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove("token").remove("refresh_token").apply()
        access.value = ""
    }

    /**
     * Новый access-токен по refresh (вызывается из OkHttp Authenticator на 401, синхронно).
     * Если другой запрос уже обновил токен — возвращает его без нового запроса.
     * null — обновить не удалось (сеть: оставляем как есть; отказ сервера: сессия кончилась).
     */
    @Synchronized
    fun refreshBlocking(failedToken: String): String? {
        val current = access.value
        if (current.isNotEmpty() && current != failedToken) return current
        val rt = refresh
        if (rt.isEmpty()) return null
        return try {
            val resp = ApiClient.authCalls.refresh(RefreshRequest(rt)).execute()
            val body = resp.body()
            when {
                resp.isSuccessful && body != null && body.access_token.isNotEmpty() -> {
                    save(body.access_token, body.refresh_token)
                    body.access_token
                }
                resp.code() == 401 || resp.code() == 403 -> {
                    clear()
                    sessionExpired.tryEmit(Unit)
                    null
                }
                else -> null   // 5xx / 429 — не выходим из аккаунта, попробуем позже
            }
        } catch (e: Exception) {
            AppErrors.log("token refresh", e)
            null
        }
    }
}
