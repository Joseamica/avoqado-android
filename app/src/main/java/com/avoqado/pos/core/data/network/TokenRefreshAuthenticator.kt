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

    private val refreshLock = Any()

    @Volatile
    private var isRefreshing = false

    override fun authenticate(route: Route?, response: Response): Request? {
        val requestPath = response.request.url.encodedPath

        // Time clock PIN endpoints can legitimately return 401 ("PIN inválido").
        // Do not refresh/retry token for those responses.
        if (requestPath.contains("/mobile/venues/") && requestPath.contains("/time-clock/")) {
            return null
        }

        // Avoid infinite refresh loops
        if (response.request.header("X-Retry-After-Refresh") != null) {
            Log.e("🔐", "Token refresh failed - logging out")
            secureStorage.clearSession()
            return null
        }

        // Concurrent 401s no longer get dropped: if a refresh is already
        // running, wait for it and retry with the token it stored (before,
        // every concurrent request but one surfaced a spurious 401).
        synchronized(refreshLock) {
            if (isRefreshing) {
                // Another thread is refreshing — block until it finishes.
                while (isRefreshing) {
                    try {
                        (refreshLock as Object).wait(10_000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                val freshToken = secureStorage.accessToken ?: return null
                // Only retry if the refresh actually produced a usable token.
                return if (secureStorage.isLoggedIn) {
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $freshToken")
                        .header("X-Retry-After-Refresh", "true")
                        .build()
                } else {
                    null
                }
            }
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
            synchronized(refreshLock) {
                isRefreshing = false
                (refreshLock as Object).notifyAll()
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
