package com.avoqado.pos.core.data.network

import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val secureStorage: SecureStorage,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()
            .header("Content-Type", "application/json")

        // Add ngrok header for debug builds to avoid browser warning page
        if (BuildConfig.DEBUG) {
            requestBuilder.header("ngrok-skip-browser-warning", "true")
        }

        // La petición sale con el sello de la sesión que la mandó, leído junto con su access (D4): si mientras viaja entra
        // o sale alguien, su 401 o su 403 no renueva ni reenvía con la sesión de otra persona. Va en un tag: no viaja.
        val (sello, token) = secureStorage.selloYAccess()
        requestBuilder.tag(SelloDePeticion::class.java, SelloDePeticion(sello))

        // Skip auth for login/refresh endpoints
        val path = originalRequest.url.encodedPath
        if (!path.contains("/auth/login") && !path.contains("/auth/refresh")) {
            if (token != null) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}

/** Con qué [SecureStorage.generacion] salió una petición (D4). */
data class SelloDePeticion(val generacion: Long)

/** El sello de una petición, si pasó por [AuthInterceptor]. */
val okhttp3.Request.sello: Long? get() = tag(SelloDePeticion::class.java)?.generacion
