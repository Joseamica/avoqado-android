package com.avoqado.pos.tpvsettings.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TpvSettings(
    val showReviewScreen: Boolean = true,
    val showTipScreen: Boolean = true,
    val showReceiptScreen: Boolean = true,
    val defaultTipPercentage: Int? = null,
    val tipSuggestions: List<Int> = listOf(10, 15, 20),
    val requirePinLogin: Boolean = true,
    val showVerificationScreen: Boolean = false,
    val requireVerificationPhoto: Boolean = false,
    val requireVerificationBarcode: Boolean = false,
    val enableShifts: Boolean = true,
    val kioskModeEnabled: Boolean = false,
    val kioskDefaultMerchantId: String? = null,
    val showQuickPayment: Boolean = true,
    val showOrderManagement: Boolean = true,
) {
    companion object {
        val DEFAULT = TpvSettings()
    }
}

@Singleton
class TpvSettingsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _settings = MutableStateFlow(TpvSettings.DEFAULT)
    val settings: StateFlow<TpvSettings> = _settings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun getCurrentSettings(): TpvSettings = _settings.value

    suspend fun refreshSettings() {
        val venueId = secureStorage.venueId
        if (venueId != null) {
            refreshSettingsForVenue(venueId)
        } else {
            Log.d("📦", "No venue ID available, using defaults")
        }
    }

    suspend fun refreshSettingsForVenue(venueId: String) {
        _isLoading.value = true
        Log.d("📦", "Fetching TPV settings for venue: $venueId")

        try {
            // Step 1: Get terminals for venue
            val token = secureStorage.accessToken ?: return
            val tpvsRequest = Request.Builder()
                .url("${ApiConstants.BASE_URL}/dashboard/venues/$venueId/tpvs")
                .header("Authorization", "Bearer $token")
                .build()

            val tpvsResponse = withContext(Dispatchers.IO) {
                client.newCall(tpvsRequest).execute()
            }
            if (!tpvsResponse.isSuccessful) {
                Log.e("📦", "❌ Failed to fetch terminals: ${tpvsResponse.code}")
                return
            }

            val tpvsBody = tpvsResponse.body?.string() ?: return
            val tpvsData = json.decodeFromString<TpvsListResponse>(tpvsBody)
            val firstTerminal = tpvsData.data.firstOrNull() ?: run {
                Log.d("📦", "No terminals found for venue")
                return
            }

            // Step 2: Get settings for terminal
            val settingsRequest = Request.Builder()
                .url("${ApiConstants.BASE_URL}/dashboard/tpv/${firstTerminal.id}/settings")
                .header("Authorization", "Bearer $token")
                .build()

            val settingsResponse = withContext(Dispatchers.IO) {
                client.newCall(settingsRequest).execute()
            }
            if (!settingsResponse.isSuccessful) {
                Log.e("📦", "❌ Failed to fetch settings: ${settingsResponse.code}")
                return
            }

            val settingsBody = settingsResponse.body?.string() ?: return
            val settingsData = json.decodeFromString<TpvSettingsResponse>(settingsBody)
            _settings.value = settingsData.data
            Log.d("📦", "✅ TPV settings loaded")
        } catch (e: Exception) {
            Log.e("📦", "❌ Error fetching TPV settings: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun clearCache() {
        _settings.value = TpvSettings.DEFAULT
    }
}

@Serializable
private data class TpvsListResponse(
    val success: Boolean = true,
    val data: List<TerminalData> = emptyList(),
)

@Serializable
private data class TerminalData(
    val id: String,
    val name: String? = null,
    val serialNumber: String? = null,
)

@Serializable
private data class TpvSettingsResponse(
    val success: Boolean = true,
    val data: TpvSettings = TpvSettings.DEFAULT,
)
