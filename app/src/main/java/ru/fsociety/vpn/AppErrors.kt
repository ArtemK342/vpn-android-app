package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import android.widget.Toast
import retrofit2.HttpException

/**
 * Фиксированный набор коротких текстов ошибок (RU/EN из strings.xml) вместо сырых
 * `e.message` / «HTTP 500: …». Подробности пишутся только в logcat на устройстве.
 *
 * Единственное исключение — сообщения, которые сервер сам формирует для показа
 * (`{"detail": {"message", "message_en"}}`: техработы, закрытая регистрация и т.п.).
 */
object AppErrors {
    private const val TAG = "AppErrors"

    /**
     * Текст для пользователя. [body] — тело ошибки, если вызывающий уже прочитал его
     * (errorBody() читается только один раз).
     */
    fun text(context: Context, e: Throwable, body: String? = null): String = when (e) {
        is HttpException -> httpText(context, e.code(), body ?: readBody(e))
        is java.net.SocketTimeoutException -> context.getString(R.string.err_timeout)
        is java.io.IOException -> context.getString(R.string.err_network)
        is com.google.gson.JsonParseException, is org.json.JSONException ->
            context.getString(R.string.err_bad_response)
        else -> context.getString(R.string.err_unknown)
    }

    /** Текст по HTTP-коду; к фиксированному тексту добавляется код — пригодится в поддержке. */
    fun httpText(context: Context, code: Int, body: String?): String {
        serverMessage(context, body)?.let { return it }
        val res = when {
            code == 401 -> R.string.err_session
            code == 403 -> R.string.err_forbidden
            code == 404 -> R.string.err_not_found
            code == 426 -> R.string.err_update
            code == 429 -> R.string.err_rate_limit
            code == 503 -> R.string.err_unavailable
            code in 500..599 -> R.string.err_server
            else -> R.string.err_unknown
        }
        return "${context.getString(res)} ($code)"
    }

    /** Сообщение для показа из `{"detail": {"message", "message_en"}}`, если сервер его прислал. */
    fun serverMessage(context: Context, body: String?): String? = try {
        val d = org.json.JSONObject(body ?: "").getJSONObject("detail")
        val en = context.resources.configuration.locales[0].language == "en"
        d.optString(if (en) "message_en" else "message").ifBlank { null }
    } catch (_: Exception) { null }

    fun readBody(e: HttpException): String? =
        try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }

    /** В logcat: что делали и тип/текст ошибки (только на устройстве, никуда не отправляется). */
    fun log(what: String, e: Throwable) {
        Log.w(TAG, "$what: ${e.javaClass.simpleName}: ${e.message}")
        AppLog.w("$what: ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Для действий без своего поля ошибки (тикеты и т.п.): лог + короткий тост. */
    fun toast(context: Context, what: String, e: Throwable) {
        log(what, e)
        Toast.makeText(context, text(context, e), Toast.LENGTH_SHORT).show()
    }
}

