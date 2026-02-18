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

        // Skip auth for login/refresh endpoints
        val path = originalRequest.url.encodedPath
        if (!path.contains("/auth/login") && !path.contains("/auth/refresh")) {
            val token = secureStorage.accessToken
            if (token != null) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}
