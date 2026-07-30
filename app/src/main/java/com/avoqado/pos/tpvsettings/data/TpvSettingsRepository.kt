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

/**
 * Navigation capabilities for this physical terminal.
 *
 * Kept separate from venue-wide TPV settings because a cremería station,
 * the main checkout and a café terminal can share a venue while opening
 * different workspaces.
 */
data class TerminalNavigationSettings(
    val terminalId: String? = null,
    val defaultWorkspace: String = STANDARD_POS,
    val canIssueAreaTickets: Boolean = false,
    val canCheckoutAreaTickets: Boolean = false,
    val canDeliverAreaTickets: Boolean = false,
    val fulfillmentAreaId: String? = null,
) {
    companion object {
        const val STANDARD_POS = "STANDARD_POS"
        const val AREA_OPERATIONS = "AREA_OPERATIONS"
        val DEFAULT = TerminalNavigationSettings()
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

    private val _terminalNavigation = MutableStateFlow(TerminalNavigationSettings.DEFAULT)
    val terminalNavigation: StateFlow<TerminalNavigationSettings> = _terminalNavigation.asStateFlow()

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
            _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
        }
    }

    suspend fun refreshSettingsForVenue(venueId: String) {
        _isLoading.value = true
        Log.d("📦", "Fetching settings for venue: $venueId")
        val localIncludeTaxOverride = loadIncludeTaxInTipBaseOverride(venueId)

        try {
            val token = secureStorage.accessToken
            if (token == null) {
                _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
                return
            }
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/settings")
                .header("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (!response.isSuccessful) {
                Log.e("📦", "❌ Failed to fetch settings: ${response.code}")
                _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
                return
            }

            val body = response.body?.string() ?: return
            val result = json.decodeFromString<VenueSettingsResponse>(body)
            _terminalNavigation.value = result.data.toTerminalNavigationSettings()

            // Plan gating (Phase ①): persist the OPTIONAL plan block. Absent
            // field (old server) → null tier → PlanManager fails OPEN. Only
            // written on a successful response so a transient error never
            // wipes a previously-known plan.
            secureStorage.planTier = result.data?.plan?.tier
            secureStorage.planExempt = result.data?.plan?.exempt == true
            Log.d("📦", "Plan: tier=${result.data?.plan?.tier ?: "none"} exempt=${result.data?.plan?.exempt == true}")

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
            _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
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
        _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
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
internal data class VenueSettingsResponse(
    val success: Boolean = true,
    val data: VenueSettingsData? = null,
)

@Serializable
internal data class VenueSettingsData(
    val settings: TpvSettings? = null,
    val activeTerminalId: String? = null,
    val deviceTerminal: DeviceTerminalSettingsDto? = null,
    val plan: VenuePlanDto? = null,
)

@Serializable
internal data class DeviceTerminalSettingsDto(
    val id: String,
    val defaultWorkspace: String = TerminalNavigationSettings.STANDARD_POS,
    val canIssueAreaTickets: Boolean = false,
    val canCheckoutAreaTickets: Boolean = false,
    val canDeliverAreaTickets: Boolean = false,
    val fulfillmentAreaId: String? = null,
)

internal fun VenueSettingsData?.toTerminalNavigationSettings(): TerminalNavigationSettings {
    val terminal = this?.deviceTerminal ?: return TerminalNavigationSettings.DEFAULT
    return TerminalNavigationSettings(
        terminalId = terminal.id,
        defaultWorkspace = terminal.defaultWorkspace,
        canIssueAreaTickets = terminal.canIssueAreaTickets,
        canCheckoutAreaTickets = terminal.canCheckoutAreaTickets,
        canDeliverAreaTickets = terminal.canDeliverAreaTickets,
        fulfillmentAreaId = terminal.fulfillmentAreaId,
    )
}

/**
 * Optional plan block in the venue-settings response. Every field defaults so
 * old servers (field absent) and partial payloads parse fine → fail-open.
 */
@Serializable
internal data class VenuePlanDto(
    val tier: String? = null,
    val grandfathered: Boolean = false,
    val exempt: Boolean = false,
)
