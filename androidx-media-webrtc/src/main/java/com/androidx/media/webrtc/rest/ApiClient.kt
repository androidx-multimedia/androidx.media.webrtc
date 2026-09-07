package com.androidx.media.webrtc.rest

import com.androidx.media.webrtc.models.IceConfig
import com.androidx.media.webrtc.models.LookupResponse
import com.androidx.media.webrtc.models.RegisterResponse
import com.androidx.media.webrtc.models.User
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

interface ApiService {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterBody): RegisterResponse

    @GET("api/v1/users/by-client/{clientId}")
    suspend fun lookupUser(@Path("clientId") clientId: String): LookupResponse

    @GET("rtc/ice-config")
    suspend fun iceConfig(): IceConfig
}

data class RegisterBody(val clientId: String, val displayName: String, val avatar: String? = null)

class ApiClient private constructor() {
    lateinit var service: ApiService
        private set
    var token: String? = null

    companion object {
        @Volatile private var instance: ApiClient? = null

        fun init(baseUrl: String): ApiClient {
            return instance ?: synchronized(this) {
                instance ?: ApiClient().also {
                    val http = OkHttpClient.Builder().build()
                    val moshi = Moshi.Builder()
                        .add(KotlinJsonAdapterFactory())
                        .build()
                    it.service = Retrofit.Builder()
                        .baseUrl(baseUrl.trimEnd('/') + "/")
                        .client(http)
                        .addConverterFactory(MoshiConverterFactory.create(moshi))
                        .build()
                        .create(ApiService::class.java)
                }
            }
        }

        fun get(): ApiClient = checkNotNull(instance) {
            "ApiClient not initialized — call ApiClient.init(baseUrl) first"
        }
    }
}