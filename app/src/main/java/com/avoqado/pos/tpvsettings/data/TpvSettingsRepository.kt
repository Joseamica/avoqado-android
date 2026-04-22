package com.avoqado.pos.tpvsettings.data

import android.util.Log
import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    val includeTaxInTipBase: Boolean = false,
) {
    companion object {
        val DEFAULT = TpvSettings()
    }
}

@Singleton
class TpvSettingsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val preferencesDataStore: PreferencesDataStore,
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
            _settings.value = TpvSettings.DEFAULT
        }
    }

    suspend fun refreshSettingsForVenue(venueId: String) {
        _isLoading.value = true
        Log.d("📦", "Fetching settings for venue: $venueId")
        val localIncludeTaxOverride = loadIncludeTaxInTipBaseOverride(venueId)

        try {
            val token = secureStorage.accessToken ?: return
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/settings")
                .header("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (!response.isSuccessful) {
                Log.e("📦", "❌ Failed to fetch settings: ${response.code}")
                return
            }

            val body = response.body?.string() ?: return
            val result = json.decodeFromString<VenueSettingsResponse>(body)
            if (result.data?.settings != null) {
                _settings.value = applyIncludeTaxOverride(
                    base = result.data.settings,
                    localOverride = localIncludeTaxOverride,
                )
                Log.d("📦", "✅ Settings loaded (terminal: ${result.data.activeTerminalId})")
            } else {
                Log.d("📦", "No active terminal, using defaults")
                _settings.value = applyIncludeTaxOverride(
                    base = TpvSettings.DEFAULT,
                    localOverride = localIncludeTaxOverride,
                )
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Error fetching settings: ${e.message}")
            _settings.value = applyIncludeTaxOverride(
                base = TpvSettings.DEFAULT,
                localOverride = localIncludeTaxOverride,
            )
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun setIncludeTaxInTipBase(value: Boolean) {
        val venueId = secureStorage.venueId ?: GLOBAL_VENUE_KEY
        preferencesDataStore.setBoolean(includeTaxInTipBaseKey(venueId), value)
        _settings.update { it.copy(includeTaxInTipBase = value) }
    }

    fun clearCache() {
        _settings.value = TpvSettings.DEFAULT
    }

    private suspend fun loadIncludeTaxInTipBaseOverride(venueId: String): Boolean? {
        return preferencesDataStore.getBooleanOrNull(includeTaxInTipBaseKey(venueId)).first()
    }

    private fun applyIncludeTaxOverride(base: TpvSettings, localOverride: Boolean?): TpvSettings {
        return localOverride?.let { base.copy(includeTaxInTipBase = it) } ?: base
    }

    private fun includeTaxInTipBaseKey(venueId: String): String {
        return "${KEY_INCLUDE_TAX_IN_TIP_BASE_PREFIX}_$venueId"
    }

    companion object {
        private const val KEY_INCLUDE_TAX_IN_TIP_BASE_PREFIX = "include_tax_in_tip_base"
        private const val GLOBAL_VENUE_KEY = "global"
    }
}

@Serializable
private data class VenueSettingsResponse(
    val success: Boolean = true,
    val data: VenueSettingsData? = null,
)

@Serializable
private data class VenueSettingsData(
    val settings: TpvSettings? = null,
    val activeTerminalId: String? = null,
)
