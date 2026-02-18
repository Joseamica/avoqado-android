package com.avoqado.pos.push

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import okhttp3.Callback
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushNotificationManager @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {

    fun registerToken(fcmToken: String) {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        val body = """{"fcmToken":"$fcmToken","deviceName":"Android"}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/devices/register")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.d("🔔", "✅ FCM token registered")
                    } else {
                        Log.e("🔔", "❌ FCM token registration failed: ${it.code}")
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                Log.e("🔔", "❌ FCM registration error: ${e.message}")
            }
        })
    }

    fun unregisterToken() {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        val body = """{"deviceName":"Android"}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/devices/unregister")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.close()
                Log.d("🔔", "FCM token unregistered")
            }
            override fun onFailure(call: Call, e: IOException) {
                Log.e("🔔", "FCM unregister error: ${e.message}")
            }
        })
    }
}
