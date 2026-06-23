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
}
