package ru.fsociety.vpn

import android.content.Context

object ConfigCache {
    private const val PREFS = "fsociety_config_cache"

    fun get(context: Context, serverId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(serverId, null)

    fun save(context: Context, serverId: String, config: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(serverId, config).apply()

    fun clear(context: Context, serverId: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(serverId).apply()

    fun clearAll(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
}

/**
 * Последний профиль (email/роль/подписка): показывается сразу при запуске, пока обновляется с
 * сервера, — без «Загрузка…» при каждом входе. Не секрет (то же, что видно в настройках),
 * стирается при выходе из аккаунта; резервное копирование данных приложения выключено.
 */
object ProfileCache {
    private const val PREFS = "fsociety"
    private val gson = com.google.gson.Gson()

    fun user(context: Context): UserResponse? = read(context, "cached_user", UserResponse::class.java)
    fun subscription(context: Context): SubscriptionResponse? =
        read(context, "cached_subscription", SubscriptionResponse::class.java)

    fun save(context: Context, user: UserResponse?, subscription: SubscriptionResponse?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (user != null) putString("cached_user", gson.toJson(user))
            if (subscription != null) putString("cached_subscription", gson.toJson(subscription))
        }.apply()
    }

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cached_user").remove("cached_subscription").apply()

    private fun <T> read(context: Context, key: String, cls: Class<T>): T? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?.let { gson.fromJson(it, cls) }
    } catch (_: Exception) { null }
}
