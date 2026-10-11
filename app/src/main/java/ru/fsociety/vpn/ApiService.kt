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

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val refresh_token: String = ""
)

data class RefreshRequest(
    val refresh_token: String
)

data class VpnConfigResponse(
    val config: String?,
    val client_ip: String?,
    val public_key: String?,
    val message: String?
)

data class UserResponse(
    val id: String,
    val email: String,
    val is_active: Boolean,
    val role: String = "user",
    val account_type: String = "email"
)

data class ServerResponse(
    val id: String,
    val name: String,
    val name_en: String? = null,
    val country: String,
    val is_active: Boolean,
    val endpoint: String? = null,
    val allow_auto_connect: Boolean = true,
    val server_type: String = "wireguard",  // "wireguard" | "vless" | "hysteria" | "trojan" | "openvpn_cloak"
    val status: String = "active",          // active | testing | degraded | attacked | maintenance | down | coming_soon | beta | hidden
    val status_message: String? = null,
    val status_message_extra: String? = null,
    val connectable: Boolean = is_active,   // computed server-side based on user role
    // true: сервер только по подписке, а пользователь бесплатный (allow_free=false в админке)
    val subscription_required: Boolean = false
) {
    val isUnavailable: Boolean get() = status in setOf("down", "coming_soon")
    val hasWarning: Boolean get() = status in setOf("degraded", "attacked")
    // VLESS, Hysteria2 and Trojan all use sing-box under the hood
    val usesSingbox: Boolean get() = server_type in setOf("vless", "hysteria", "trojan")
    val usesOpenVpn: Boolean get() = server_type == "openvpn_cloak"
    // English name when the app language is English (falls back to the default name).
    fun displayName(lang: String): String =
        if (lang == "en" && !name_en.isNullOrBlank()) name_en else name
}

data class VlessConfigResponse(
    val config: String,
    val server_type: String
)

data class HysteriaConfigResponse(
    val config: String,
    val server_type: String
)

data class TrojanConfigResponse(
    val config: String,
    val server_type: String
)

data class OpenVpnCloakConfigResponse(
    val ovpn_config: String,
    val cloak_config: String,
    val host: String,
    val server_type: String
)

data class SubscriptionResponse(
    val is_active: Boolean,
    val plan: String?,
    val expires_at: String?
)

data class RegisterRequest(
    val email: String,
    val password: String
)

data class NumericLoginRequest(val code: String)

data class TicketResponse(
    val id: String,
    val subject: String,
    val status: String,
    val created_at: String,
    val updated_at: String
)

data class TicketMessageResponse(
    val id: String,
    val ticket_id: String,
    val sender: String,
    val message: String,
    val created_at: String
)

data class TicketDetailResponse(
    val ticket: TicketResponse,
    val messages: List<TicketMessageResponse>
)

data class TicketCreateRequest(val subject: String)
data class MessageCreateRequest(val message: String)
data class ChangePasswordRequest(val old_password: String, val new_password: String)

data class UsageResponse(
    val is_limited: Boolean,
    val bytes_used: Long,
    val limit_bytes: Long,
    val resets_at: String?
)

data class AppConfigResponse(
    val android_min_version: Int,
    val update_url: String,
    val access: AccessStatus? = null
)

/** Публичная часть «Настройки → Доступ» из /app-config (плашка техработ, протоколы, регистрация). */
data class AccessStatus(
    val maintenance: MaintenanceStatus? = null,
    val protocols: Map<String, Boolean>? = null,
    val registration: Map<String, Boolean>? = null
)

data class MaintenanceStatus(
    val enabled: Boolean = false,
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

data class GenerateNumericResponse(
    val code: String,
    val access_token: String,
    val refresh_token: String,
    val token_type: String
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

    @GET("vpn/openvpn-cloak-config")
    suspend fun getOpenVpnCloakConfig(
        @Header("Authorization") token: String,
        @Query("server_id") serverId: String
    ): OpenVpnCloakConfigResponse

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




// ── Retrofit клиент (singleton) ──

object ApiClient {
    private const val BASE_URL = "https://fsociety-vpn.org/api/"
    // Тот же API напрямую на Финляндию, без Cloudflare
    private const val DIRECT_HOST = "api.fsociety-vpn.org"

    /**
     * Cloudflare из РФ часто режется (соединение замирает после первых КБ),
     * поэтому при сетевой ошибке запрос повторяется напрямую через DIRECT_HOST.
     * Тело ответа вычитывается здесь же, чтобы зависание на чтении тоже
     * ловилось и уходило в fallback, а не падало позже в конвертере.
     */
    private val directFallback = okhttp3.Interceptor { chain ->
        val req = chain.request()
        if (req.url.host == DIRECT_HOST) return@Interceptor chain.proceed(req)
        try {
            val resp = chain
                .withConnectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .withReadTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .proceed(req)
            val body = resp.body ?: return@Interceptor resp
            val bytes = body.bytes()
            resp.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        } catch (e: java.io.IOException) {
            if (chain.call().isCanceled()) throw e
            android.util.Log.w("ApiClient", "${req.url.encodedPath} via Cloudflare failed (${e.message}), retry direct")
            chain.proceed(req.newBuilder().url(req.url.newBuilder().host(DIRECT_HOST).build()).build())
        }
    }

    private fun buildOkHttp(readTimeoutSec: Long) = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        // запас на попытку через Cloudflare (до ~20с) + повтор напрямую
        .callTimeout(readTimeoutSec + 30, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        .addInterceptor(directFallback)
        .addInterceptor { chain ->
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
        .build()

    private const val UPDATE_URL = "https://fsociety-vpn.org/download"

    /** Статус техработ из последнего /app-config; HomeScreen показывает плашку. */
    val maintenance = kotlinx.coroutines.flow.MutableStateFlow<MaintenanceStatus?>(null)

    /** Ссылка на обновление, если сервер ответил 426; MainActivity показывает блокирующий диалог. */
    val updateRequired = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildOkHttp(20))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
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
/**
 * Текст ошибки из ответа вида {"detail": {"code", "message", "message_en"}}
 * (блокировки «Настройки → Доступ»: регистрация закрыта, техработы и т.п.) на языке
 * приложения; null — если ответ другого формата.
 */
fun accessErrorText(e: retrofit2.HttpException, context: android.content.Context): String? = try {
    val body = e.response()?.errorBody()?.string() ?: ""
    val d = org.json.JSONObject(body).getJSONObject("detail")
    val en = context.resources.configuration.locales[0].language == "en"
    d.optString(if (en) "message_en" else "message").ifBlank { null }
} catch (_: Exception) { null }
