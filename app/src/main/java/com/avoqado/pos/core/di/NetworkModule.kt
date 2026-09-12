package com.avoqado.pos.core.di

import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.network.AuthInterceptor
import com.avoqado.pos.core.data.network.ConnectivityInterceptor
import com.avoqado.pos.core.data.network.DeviceHeadersInterceptor
import com.avoqado.pos.core.data.network.ErrorNotifier
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
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
        // 🔴 `explicitNulls` SE QUEDA EN true AQUÍ, Y ES UNA DECISIÓN, NO UN DESCUIDO.
        //
        // Este `Json` es el del converter compartido de Retrofit: arma los 36 `@Body` de
        // `ApiService`. El 2026-09-12 se probó ponerle `explicitNulls = false` —el arreglo
        // que sí corresponde al reembolso, ver `RefundRepository.jsonReembolsos`— y una
        // auditoría adversarial (Codex gpt-6-astra, xhigh) demostró que ROMPE una señal viva:
        // el servidor usa `PrintJob.error: null` como «borra el error viejo»
        // (`print.mobile.service.ts:75`, con ese comentario textual) y `ReporteDeComandas`
        // lo manda al recuperarse una comanda. Omitir la llave dejaba el fallo pegado y una
        // comanda que SÍ salió seguía figurando como fallida.
        //
        // O sea: en este carril `null` y «llave ausente» NO son lo mismo, así que el arreglo
        // va por cuerpo —el `Json` propio del reembolso— y no de golpe. Lo fija
        // `NetworkJsonNullsTest`, que exige que el nulo siga viajando.
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceHeadersInterceptor: DeviceHeadersInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator,
        errorNotifier: ErrorNotifier,
        connectivityMonitor: ConnectivityMonitor,
        // No hay ciclo: ManagerOverrideCoordinator → PermissionOverrideRepository
        // → SecureStorage, y el repositorio construye su PROPIO OkHttpClient.
        managerOverrideCoordinator: ManagerOverrideCoordinator,
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
            .addInterceptor(ForbiddenInterceptor(errorNotifier, managerOverrideCoordinator))
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
