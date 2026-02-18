package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

class TokenRefreshAuthenticator @Inject constructor(
    private val secureStorage: SecureStorage,
) : Authenticator {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var isRefreshing = false

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite refresh loops
        if (response.request.header("X-Retry-After-Refresh") != null) {
            Log.e("🔐", "Token refresh failed - logging out")
            secureStorage.clearSession()
            return null
        }

        synchronized(this) {
            if (isRefreshing) return null
            isRefreshing = true
        }

        try {
            val refreshToken = secureStorage.refreshToken ?: run {
                secureStorage.clearSession()
                return null
            }

            val refreshResult = runBlocking { refreshTokens(refreshToken) }

            return if (refreshResult != null) {
                secureStorage.updateTokens(
                    accessToken = refreshResult.accessToken,
                    refreshToken = refreshResult.refreshToken,
                )
                Log.d("🔐", "Token refreshed successfully")
                response.request.newBuilder()
                    .header("Authorization", "Bearer ${refreshResult.accessToken}")
                    .header("X-Retry-After-Refresh", "true")
                    .build()
            } else {
                Log.e("🔐", "Token refresh returned null - logging out")
                secureStorage.clearSession()
                null
            }
        } finally {
            synchronized(this) {
                isRefreshing = false
            }
        }
    }

    private fun refreshTokens(refreshToken: String): TokenRefreshResponse? {
        return try {
            val client = OkHttpClient()
            val body = json.encodeToString(
                TokenRefreshRequest.serializer(),
                TokenRefreshRequest(refreshToken),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/auth/refresh")
                .header("ngrok-skip-browser-warning", "true")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let {
                    json.decodeFromString<TokenRefreshResponse>(it)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("🔐", "Token refresh error: ${e.message}")
            null
        }
    }
}

@Serializable
private data class TokenRefreshRequest(val refreshToken: String)

@Serializable
private data class TokenRefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)
