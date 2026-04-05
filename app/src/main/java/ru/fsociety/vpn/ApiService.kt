package ru.fsociety.vpn

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
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
    val token_type: String
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
    val role: String = "user"
)

data class ServerResponse(
    val id: String,
    val name: String,
    val country: String,
    val is_active: Boolean
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

}




// ── Retrofit клиент (singleton) ──

object ApiClient {
    private const val BASE_URL = "https://fsociety-vpn.org/api/"

    val service: ApiService by lazy {
        val okHttp = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
            .build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}