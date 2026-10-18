package ru.fsociety.vpn

import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Streaming
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

// ── Модели запросов/ответов ──
// У всех полей есть значения по умолчанию: тогда у класса есть конструктор без аргументов, и Gson
// подставляет эти значения, если поле не пришло. Без него Gson создаёт объект в обход конструктора,
// и для отсутствующих полей были бы null/false/0 вместо указанных умолчаний.

data class LoginResponse(
    val access_token: String = "",
    val token_type: String = "bearer",
    val refresh_token: String = ""
)

data class RefreshRequest(
    val refresh_token: String
)

data class VpnConfigResponse(
    val config: String? = null,
    val client_ip: String? = null,
    val public_key: String? = null,
    val message: String? = null
)

data class UserResponse(
    val id: String = "",
    // Для анонимов сервер кладёт сюда код «XXXX-XXXX-XXXX-XXXX», для логин-аккаунтов — логин
    val email: String? = null,
    val is_active: Boolean = true,
    val role: String = "user",
    val account_type: String = "email"
)

data class ServerResponse(
    val id: String = "",
    val name: String = "",
    val name_en: String? = null,
    val country: String = "",
    val is_active: Boolean = false,
    val endpoint: String? = null,
    val allow_auto_connect: Boolean = true,
    val server_type: String = "wireguard",  // "wireguard" | "vless" | "hysteria" | "trojan" | "openvpn_cloak" | "bypass"
    val status: String = "active",          // active | testing | degraded | attacked | maintenance | down | coming_soon | beta | hidden
    val status_message: String? = null,
    val status_message_extra: String? = null,
    val connectable: Boolean = false,       // computed server-side based on user role
    // true: сервер только по подписке, а пользователь бесплатный (allow_free=false в админке)
    val subscription_required: Boolean = false,
    // не null: подключение закрыто в «Настройки → Доступ» (техработы / протокол / тип аккаунта)
    val access_block: AccessBlock? = null,
    // На сервере временно ведутся логи (галочка в админке) — показываем заметную пометку LOGS ON
    val logs_enabled: Boolean = false
) {
    // Можно ли нажать на сервер: флаги из админки + подписка + блокировки доступа
    val canConnect: Boolean get() = connectable && !subscription_required && access_block == null
    val isUnavailable: Boolean get() = status in setOf("down", "coming_soon")
    val hasWarning: Boolean get() = status in setOf("degraded", "attacked")
    // VLESS, Hysteria2, Trojan и «Режим РФ» работают через sing-box
    val usesSingbox: Boolean get() = server_type in setOf("vless", "hysteria", "trojan", "bypass")
    val usesOpenVpn: Boolean get() = server_type == "openvpn_cloak"
    // English name when the app language is English (falls back to the default name).
    fun displayName(lang: String): String =
        if (lang == "en" && !name_en.isNullOrBlank()) name_en else name
}

data class VlessConfigResponse(
    val config: String = "",
    val server_type: String = ""
)

data class HysteriaConfigResponse(
    val config: String = "",
    val server_type: String = ""
)

data class TrojanConfigResponse(
    val config: String = "",
    val server_type: String = ""
)

data class SubscriptionResponse(
    val is_active: Boolean = false,
    val plan: String? = null,
    val expires_at: String? = null
)

data class ForgotPasswordRequest(val email: String)

data class RegisterRequest(
    val email: String,
    val password: String
)

data class NumericLoginRequest(val code: String)

data class TicketResponse(
    val id: String = "",
    val subject: String = "",
    val status: String = "open",
    val created_at: String = "",
    val updated_at: String = ""
)

data class TicketMessageResponse(
    val id: String = "",
    val ticket_id: String = "",
    val sender: String = "",
    val message: String = "",
    val created_at: String = ""
)

data class TicketDetailResponse(
    val ticket: TicketResponse = TicketResponse(),
    val messages: List<TicketMessageResponse> = emptyList()
)

data class TicketCreateRequest(val subject: String)
data class MessageCreateRequest(val message: String)
data class ChangePasswordRequest(val old_password: String, val new_password: String)

data class UsageResponse(
    val is_limited: Boolean = false,
    val bytes_used: Long = 0L,
    val limit_bytes: Long = 0L,
    val resets_at: String? = null
)

data class AppConfigResponse(
    val android_min_version: Int = 0,
    val update_url: String = "",
    val access: AccessStatus? = null
)

/** Публичная часть «Настройки → Доступ» из /app-config (плашка техработ, протоколы, регистрация). */
data class AccessStatus(
    val maintenance: MaintenanceStatus? = null,
    val protocols: Map<String, Boolean>? = null,
    val registration: Map<String, Boolean>? = null,
    // Аргументы byedpi для «Режима РФ» из админки (пусто — встроенная стратегия)
    val bypass_strategy: String? = null
)

data class MaintenanceStatus(
    val enabled: Boolean = false,
    val message: String? = null,
    val message_en: String? = null
) {
    fun text(lang: String): String? = (if (lang == "en") message_en else message)?.takeIf { it.isNotBlank() }
}

/** ServerResponse.access_block — почему сервер сейчас недоступен этому пользователю. */
data class AccessBlock(
    val code: String? = null,      // maintenance | protocol_disabled | segment_blocked
    val message: String? = null,
    val message_en: String? = null
) {
    fun text(lang: String): String? = (if (lang == "en") message_en else message)?.takeIf { it.isNotBlank() }
}

/** GET /vpn/access — можно ли сейчас открыть новое подключение этим протоколом. */
data class AccessCheckResponse(
    val allowed: Boolean = true,
    val code: String? = null,
    val message: String? = null,
    val message_en: String? = null
) {
    fun text(lang: String): String? = (if (lang == "en") message_en else message)?.takeIf { it.isNotBlank() }
}

/** Новость из GET /news: только опубликованные, порядок задаёт админка. EN-поля — если админ их заполнил. */
data class NewsItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val title_en: String? = null,
    val body_en: String? = null,
    val image_id: String? = null,
    val image_ext: String? = null,
    val published_at: String? = null
) {
    fun title(lang: String) = if (lang == "en" && !title_en.isNullOrBlank()) title_en else title
    fun body(lang: String) = if (lang == "en" && !body_en.isNullOrBlank()) body_en else body
}

data class NewsPage(
    val items: List<NewsItem> = emptyList(),
    val total: Int = 0,
    val has_more: Boolean = false
)

data class GenerateNumericResponse(
    val code: String = "",
    val access_token: String = "",
    val refresh_token: String = "",
    val token_type: String = "bearer"
)

// ── Интерфейс API ──

interface ApiService {

    // Вход — форма urlencoded как на сайте
    @FormUrlEncoded
    @POST("login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("grant_type") grantType: String = "password"
    ): LoginResponse

    @POST("refresh")
    suspend fun refresh(
        @Body body: RefreshRequest
    ): LoginResponse

    // Данные текущего пользователя
    @GET("me")
    suspend fun getMe(
        @Header("Authorization") token: String
    ): UserResponse

    // Список серверов
    @GET("servers")
    suspend fun getServers(
        @Header("Authorization") token: String
    ): List<ServerResponse>

    // Статус подписки
    @GET("subscription")
    suspend fun getSubscription(
        @Header("Authorization") token: String
    ): SubscriptionResponse

    @POST("login-numeric")
    suspend fun loginNumeric(
        @Body body: NumericLoginRequest
    ): LoginResponse

    // Ссылка для сброса пароля на почту (как на сайте /forgot); лимит сервера — 1 раз в сутки
    @POST("forgot-password")
    suspend fun forgotPassword(
        @Body body: ForgotPasswordRequest
    ): Map<String, Any?>

    @POST("register")
    suspend fun register(
        @Body body: RegisterRequest
    ): UserResponse

    @POST("vpn/config")
    suspend fun getVpnConfig(
        @Header("Authorization") token: String,
        @Query("server_id") serverId: String
    ): VpnConfigResponse

    @GET("tickets")
    suspend fun getTickets(@Header("Authorization") token: String): List<TicketResponse>

    @GET("tickets/{id}")
    suspend fun getTicket(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("id") id: String
    ): TicketDetailResponse

    @POST("tickets")
    suspend fun createTicket(
        @Header("Authorization") token: String,
        @Body body: TicketCreateRequest
    ): TicketResponse

    @POST("tickets/{id}/messages")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("id") id: String,
        @Body body: MessageCreateRequest
    ): TicketMessageResponse

    @retrofit2.http.PATCH("tickets/{id}/close")
    suspend fun closeTicket(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("id") id: String
    ): TicketResponse

    /** Удалить аккаунт со всеми ключами, подпиской и тикетами (Google Play: удаление внутри приложения). */
    @DELETE("me")
    suspend fun deleteMe(
        @Header("Authorization") token: String
    ): retrofit2.Response<okhttp3.ResponseBody?>

    @DELETE("tickets/{id}")
    suspend fun deleteTicket(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("id") id: String
    ): retrofit2.Response<okhttp3.ResponseBody?>

    @POST("change-password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body body: ChangePasswordRequest
    ): retrofit2.Response<okhttp3.ResponseBody?>

    /** Лента новостей по страницам; без авторизации (читать может любой). */
    @GET("news")
    suspend fun getNews(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): NewsPage

    @GET("vpn/usage")
    suspend fun getUsage(
        @Header("Authorization") token: String
    ): UsageResponse

    @GET("vpn/vless-config")
    suspend fun getVlessConfig(
        @Header("Authorization") token: String,
        @Query("server_id") serverId: String
    ): VlessConfigResponse

    @GET("vpn/access")
    suspend fun checkAccess(
        @Header("Authorization") token: String,
        @Query("server_type") serverType: String
    ): AccessCheckResponse

    @GET("vpn/hysteria-config")
    suspend fun getHysteriaConfig(
        @Header("Authorization") token: String,
        @Query("server_id") serverId: String
    ): HysteriaConfigResponse

    @GET("vpn/trojan-config")
    suspend fun getTrojanConfig(
        @Header("Authorization") token: String,
        @Query("server_id") serverId: String
    ): TrojanConfigResponse

    @Streaming
    @GET("vpn/openvpn-cloak-bundle")
    suspend fun getOpenVpnCloakBundle(
        @Header("Authorization") token: String,
        @Query("server_id") serverId: String
    ): retrofit2.Response<okhttp3.ResponseBody>

    @GET("app-config")
    suspend fun getAppConfig(): AppConfigResponse

    @POST("generate-numeric-account")
    suspend fun generateNumericAccount(): GenerateNumericResponse

}

/** Синхронные вызовы для OkHttp Authenticator (без suspend: он работает в потоке запроса). */
interface AuthCalls {
    @POST("refresh")
    fun refresh(@Body body: RefreshRequest): retrofit2.Call<LoginResponse>
}


// ── Retrofit клиент (singleton) ──

object ApiClient {
    private const val BASE_URL = "https://fsociety-vpn.org/api/"
    // Тот же API напрямую на Финляндию, без Cloudflare
    private const val DIRECT_HOST = "api.fsociety-vpn.org"

    /** Ошибка до отправки запроса на сервер (соединение не установлено) — повтор безопасен. */
    private fun java.io.IOException.beforeSend(): Boolean =
        this is java.net.ConnectException || this is java.net.UnknownHostException ||
            this is java.net.NoRouteToHostException || this is javax.net.ssl.SSLHandshakeException ||
            (this is java.net.SocketTimeoutException && message?.contains("connect", ignoreCase = true) == true)

    /**
     * Cloudflare из РФ часто режется (соединение замирает после первых КБ),
     * поэтому при сетевой ошибке запрос повторяется напрямую через DIRECT_HOST.
     * Тело ответа вычитывается здесь же, чтобы зависание на чтении тоже
     * ловилось и уходило в fallback, а не падало позже в конвертере.
     *
     * Повторяем только то, что безопасно повторить: GET или запрос, который точно не дошёл до
     * сервера. POST, на который не дочитали ответ, мог уже выполниться — его повтор создавал второй
     * анонимный аккаунт (и 429 вместо кода), второе письмо сброса пароля, дубли сообщений в тикетах.
     */
    /**
     * Cloudflare недавно не ответил — 10 минут сразу идём напрямую. Раньше каждый запрос сначала
     * ждал таймаут Cloudflare (8–12 с): тикеты и списки открывались с задержкой, по 2–3 нажатия.
     */
    @Volatile private var directUntil = 0L
    private const val DIRECT_STICKY_MS = 10 * 60_000L

    /**
     * Запросы VPN (/api/vpn/…: конфиги с ключами и паролями подключения, проверка доступа, расход
     * трафика) — сначала напрямую на наш сервер, минуя Cloudflare: он расшифровывает HTTPS у себя и
     * видел бы и IP, и ключи. Cloudflare — запасной путь; прямой адрес не ответил — 10 минут такие
     * запросы идут сначала через Cloudflare, чтобы не ждать таймаут на каждом.
     */
    private fun isVpnRequest(req: okhttp3.Request) = req.url.encodedPath.startsWith("/api/vpn/")
    @Volatile private var directDownUntil = 0L

    /**
     * Анонимный аккаунт — все запросы напрямую (политика конфиденциальности, раздел «Сайт, API и почта»):
     * Cloudflare не видит ни IP, ни код, ни токены. Вход по коду и создание аккаунта — тоже, ещё до токена.
     */
    private val ANON_AUTH_PATHS = setOf("/api/login-numeric", "/api/generate-numeric-account")
    @Volatile private var anonTokenCache: Pair<String, Boolean>? = null

    /** Анонимная сессия — по токену: sub «a:<id>» (с 0.0.3.9) или старый «n:<код>». */
    private fun isAnonymousSession(): Boolean {
        val token = TokenStore.access.value
        if (token.isEmpty()) return false
        anonTokenCache?.let { (t, anon) -> if (t == token) return anon }
        val anon = try {
            val payload = token.split('.')[1]
            val json = String(android.util.Base64.decode(payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            val sub = org.json.JSONObject(json).optString("sub")
            sub.startsWith("a:") || sub.startsWith("n:")
        } catch (_: Exception) { false }
        anonTokenCache = token to anon
        return anon
    }

    private fun prefersDirect(req: okhttp3.Request) =
        isVpnRequest(req) || req.url.encodedPath in ANON_AUTH_PATHS || isAnonymousSession()

    private val directFallback = okhttp3.Interceptor { chain ->
        val req = chain.request()
        if (req.url.host == DIRECT_HOST) return@Interceptor chain.proceed(req)
        val now = System.currentTimeMillis()
        val directFirst = now < directUntil || (prefersDirect(req) && now >= directDownUntil)
        val idempotent = req.method == "GET" || req.method == "HEAD"
        val direct = req.newBuilder().url(req.url.newBuilder().host(DIRECT_HOST).build()).build()
        val timed = chain
            .withConnectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .withReadTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        fun buffered(resp: okhttp3.Response): okhttp3.Response {
            val body = resp.body ?: return resp
            val bytes = body.bytes()
            return resp.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }
        if (directFirst) {
            // Сначала напрямую; не вышло — через Cloudflare (те же правила безопасного повтора)
            try {
                return@Interceptor buffered(timed.proceed(direct))
            } catch (e: java.io.IOException) {
                if (chain.call().isCanceled()) throw e
                if (!idempotent && !e.beforeSend()) throw e
                android.util.Log.w("ApiClient", "${req.url.encodedPath} direct failed (${e.message}), retry via Cloudflare")
                directUntil = 0L
                directDownUntil = System.currentTimeMillis() + DIRECT_STICKY_MS
                return@Interceptor chain.proceed(req)
            }
        }
        try {
            buffered(timed.proceed(req))
        } catch (e: java.io.IOException) {
            if (chain.call().isCanceled()) throw e
            if (!idempotent && !e.beforeSend()) throw e
            android.util.Log.w("ApiClient", "${req.url.encodedPath} via Cloudflare failed (${e.message}), retry direct")
            directUntil = System.currentTimeMillis() + DIRECT_STICKY_MS
            chain.proceed(direct)
        }
    }

    /** Экраны передают токен, полученный при своём открытии; если он уже обновлён — подставляем свежий. */
    private val currentToken = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val auth = req.header("Authorization")
        val current = TokenStore.access.value
        if (auth != null && auth.startsWith("Bearer ") && current.isNotEmpty() && auth != "Bearer $current") {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer $current").build())
        } else chain.proceed(req)
    }

    /** 401 на запросе с токеном → один раз обновляем токен по refresh и повторяем запрос. */
    private val refreshAuthenticator = okhttp3.Authenticator { _, response ->
        val req = response.request
        val auth = req.header("Authorization") ?: return@Authenticator null
        if (!auth.startsWith("Bearer ") || response.priorResponse != null) return@Authenticator null
        val fresh = TokenStore.refreshBlocking(auth.removePrefix("Bearer ")) ?: return@Authenticator null
        req.newBuilder().header("Authorization", "Bearer $fresh").build()
    }

    private val appVersion = okhttp3.Interceptor { chain ->
        val resp = chain.proceed(
            chain.request().newBuilder()
                .addHeader("X-App-Version", BuildConfig.VERSION_CODE.toString())
                .build()
        )
        // 426 = версия ниже минимальной (задаётся в админке) → блокирующий диалог
        if (resp.code == 426) {
            val url = try {
                org.json.JSONObject(resp.peekBody(4096).string()).optString("update_url")
            } catch (_: Exception) { "" }
            updateRequired.value = url.ifEmpty { UPDATE_URL }
        }
        resp
    }

    private fun buildOkHttp(readTimeoutSec: Long, withAuth: Boolean = true) = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        // запас на попытку через Cloudflare (до ~20с) + повтор напрямую
        .callTimeout(readTimeoutSec + 30, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // Без пула и HTTP/2 осознанно: соединения переживают включение/выключение VPN и «зависают»
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        .addInterceptor(directFallback)
        .apply { if (withAuth) addInterceptor(currentToken) }
        .addInterceptor(appVersion)
        .apply { if (withAuth) authenticator(refreshAuthenticator) }
        .build()

    private const val UPDATE_URL = "https://fsociety-vpn.org/download"

    /** Статус техработ из последнего /app-config; HomeScreen показывает плашку. */
    val maintenance = kotlinx.coroutines.flow.MutableStateFlow<MaintenanceStatus?>(null)

    /** Ссылка на обновление, если сервер ответил 426; MainActivity показывает блокирующий диалог. */
    val updateRequired = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Общий клиент: картинки новостей идут по тем же правилам (аноним — напрямую, мимо Cloudflare). */
    val http: okhttp3.OkHttpClient by lazy { buildOkHttp(20) }

    /** Адрес картинки новости: с расширением — её кэширует Cloudflare (хост подменяется в directFallback). */
    fun newsImageUrl(imageId: String, ext: String?) =
        BASE_URL + "news/images/" + imageId + (ext?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    /** Для обновления токена: без authenticator (иначе 401 на /refresh зациклился бы). */
    val authCalls: AuthCalls by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildOkHttp(20, withAuth = false))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthCalls::class.java)
    }

    // Direct endpoint (no Cloudflare) for the bundle — bypasses DLP entirely.
    val bundleService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.fsociety-vpn.org/api/")
            .client(buildOkHttp(15))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
