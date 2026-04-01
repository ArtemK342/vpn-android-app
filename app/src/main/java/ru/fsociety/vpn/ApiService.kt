package ru.fsociety.vpn

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

// ── Модели запросов/ответов ──

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val access_token: String,
    val token_type: String
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
    val ip: String,
    val is_active: Boolean
)

data class SubscriptionResponse(
    val is_active: Boolean,
    val plan: String?,
    val expires_at: String?
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
}

// ── Retrofit клиент (singleton) ──

object ApiClient {
    private const val BASE_URL = "https://fsociety-vpn.org/api/"

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}