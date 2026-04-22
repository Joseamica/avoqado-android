package com.avoqado.pos.notifications.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.notifications.data.model.AppNotification
import com.avoqado.pos.notifications.data.model.NotificationsResponse
import com.avoqado.pos.notifications.data.model.UnreadCountResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: Int
        get() = _unreadCount.value

    suspend fun fetchNotifications(page: Int = 1, limit: Int = 50) {
        val token = secureStorage.accessToken ?: return

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/notifications?page=$page&limit=$limit")
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<NotificationsResponse>(body)
                _notifications.value = result.data.notifications
                _unreadCount.value = result.data.unreadCount
                Log.d("🔔", "✅ Loaded ${result.data.notifications.size} notifications (unread: ${result.data.unreadCount})")
            }
        } catch (e: Exception) {
            Log.e("🔔", "❌ Notifications fetch error: ${e.message}")
        }
    }

    suspend fun markAsRead(notificationId: String) {
        val token = secureStorage.accessToken ?: return

        // Optimistic local update
        _notifications.value = _notifications.value.map {
            if (it.id == notificationId && it.readAt == null) {
                it.copy(readAt = java.time.Instant.now().toString())
            } else {
                it
            }
        }
        _unreadCount.value = _notifications.value.count { !it.isRead }

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/notifications/$notificationId/read")
                .header("Authorization", "Bearer $token")
                .patch("".toRequestBody("application/json".toMediaType()))
                .build()

            val responseCode = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }
            if (responseCode !in 200..299) {
                Log.e("🔔", "❌ markAsRead failed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("🔔", "❌ markAsRead error: ${e.message}")
        }
    }

    suspend fun markAllAsRead() {
        val token = secureStorage.accessToken ?: return

        // Optimistic local update
        val now = java.time.Instant.now().toString()
        _notifications.value = _notifications.value.map {
            if (it.readAt == null) it.copy(readAt = now) else it
        }
        _unreadCount.value = 0

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/notifications/mark-all-read")
                .header("Authorization", "Bearer $token")
                .patch("".toRequestBody("application/json".toMediaType()))
                .build()

            val responseCode = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }
            if (responseCode !in 200..299) {
                Log.e("🔔", "❌ markAllAsRead failed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("🔔", "❌ markAllAsRead error: ${e.message}")
        }
    }

    fun clearCache() {
        _notifications.value = emptyList()
        _unreadCount.value = 0
    }
}
