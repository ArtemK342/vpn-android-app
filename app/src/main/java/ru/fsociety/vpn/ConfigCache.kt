package ru.fsociety.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Сохранённые конфиги WireGuard (по id сервера) — чтобы не выдавать новый ключ при каждом
 * подключении (на сервере это 3–6 SSH-команд).
 *
 * В конфиге приватный ключ, поэтому он шифруется AES-GCM ключом из Android Keystore (ключ не
 * покидает защищённое хранилище). Раньше лежал открытым текстом в SharedPreferences. Если Keystore
 * на устройстве сбоит — хранится как раньше (кэш, не данные: при ошибке конфиг просто запросится заново).
 */
object ConfigCache {
    private const val PREFS = "fsociety_config_cache"
    private const val KEY_ALIAS = "fsociety_config_cache"
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12

    fun get(context: Context, serverId: String): String? {
        val stored = prefs(context).getString(serverId, null) ?: return null
        if (!stored.startsWith(PREFIX)) {
            // Старая открытая запись — шифруем на месте (тот же ключ на сервере, без перевыдачи)
            save(context, serverId, stored)
            return stored
        }
        return decrypt(stored) ?: run {
            clear(context, serverId)   // ключ Keystore потерян (сброс экрана блокировки и т.п.) — запросим заново
            null
        }
    }

    fun save(context: Context, serverId: String, config: String) =
        prefs(context).edit().putString(serverId, encrypt(config) ?: config).apply()

    fun clear(context: Context, serverId: String) =
        prefs(context).edit().remove(serverId).apply()

    fun clearAll(context: Context) =
        prefs(context).edit().clear().apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    } catch (e: Exception) {
        AppErrors.log("config cache encrypt", e)
        null
    }

    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
        String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        AppErrors.log("config cache decrypt", e)
        null
    }
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

    /**
     * Тикеты поддержки: список и открытые тикеты — раздел открывается сразу (с тем, что было, или
     * «Тикетов нет»), свежее приходит следом. Раньше каждый раз ждали сервер.
     * null — ещё ни разу не загружали.
     */
    fun tickets(context: Context): List<TicketResponse>? =
        read(context, "cached_tickets", Array<TicketResponse>::class.java)?.toList()

    fun saveTickets(context: Context, tickets: List<TicketResponse>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keep = tickets.map { TICKET_PREFIX + it.id }.toSet()
        prefs.edit().apply {
            putString("cached_tickets", gson.toJson(tickets))
            // Переписка удалённых тикетов не нужна
            prefs.all.keys.filter { it.startsWith(TICKET_PREFIX) && it !in keep }.forEach { remove(it) }
        }.apply()
    }

    fun ticket(context: Context, id: String): TicketDetailResponse? =
        read(context, TICKET_PREFIX + id, TicketDetailResponse::class.java)

    fun saveTicket(context: Context, detail: TicketDetailResponse) {
        val id = detail.ticket?.id ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TICKET_PREFIX + id, gson.toJson(detail)).apply()
    }

    private const val TICKET_PREFIX = "cached_ticket_"

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove("cached_user"); remove("cached_subscription"); remove("cached_tickets")
            prefs.all.keys.filter { it.startsWith(TICKET_PREFIX) }.forEach { remove(it) }
        }.apply()
    }

    private fun <T> read(context: Context, key: String, cls: Class<T>): T? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?.let { gson.fromJson(it, cls) }
    } catch (_: Exception) { null }
}
