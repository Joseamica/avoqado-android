package com.avoqado.pos.core.di

import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.network.AuthInterceptor
import com.avoqado.pos.core.data.network.ConnectivityInterceptor
import com.avoqado.pos.core.data.network.DeviceHeadersInterceptor
import com.avoqado.pos.core.data.network.ErrorNotifier
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import com.avoqado.pos.core.util.ConnectivityMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceHeadersInterceptor: DeviceHeadersInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator,
        errorNotifier: ErrorNotifier,
        connectivityMonitor: ConnectivityMonitor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            // Registro pasivo de dispositivos (estilo Square Device Management): manda
            // los headers X-Device-* para que el server registre este aparato en el
            // venue. Si falla, el request sigue sin ellos — nunca bloquea un cobro.
            .addInterceptor(deviceHeadersInterceptor)
            .addInterceptor(ForbiddenInterceptor(errorNotifier))
            .addInterceptor(ConnectivityInterceptor(connectivityMonitor))
            .addInterceptor(logging)
            .authenticator(tokenRefreshAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ApiConstants.BASE_URL + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
