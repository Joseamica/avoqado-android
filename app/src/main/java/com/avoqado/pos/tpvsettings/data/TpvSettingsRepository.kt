package com.avoqado.pos.tpvsettings.data

import android.util.Log
import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.customerdisplay.DisplayModeAction
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.reconcileDisplayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
@Serializable
data class TerminalNavigationSettings(
    val terminalId: String? = null,
    val defaultWorkspace: String = STANDARD_POS,
    val canIssueAreaTickets: Boolean = false,
    val canCheckoutAreaTickets: Boolean = false,
    val canDeliverAreaTickets: Boolean = false,
    val fulfillmentAreaId: String? = null,
    val customerDisplayInverted: Boolean = false,
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
    private val displayModePrefs: DisplayModePrefs,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _settings = MutableStateFlow(TpvSettings.DEFAULT)
    val settings: StateFlow<TpvSettings> = _settings.asStateFlow()

    private val _terminalNavigation = MutableStateFlow(TerminalNavigationSettings.DEFAULT)
    val terminalNavigation: StateFlow<TerminalNavigationSettings> = _terminalNavigation.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loadedVenueId: String? = null

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

        if (loadedVenueId != venueId) {
            loadedVenueId = venueId
            _settings.value = applyIncludeTaxOverride(TpvSettings.DEFAULT, localIncludeTaxOverride)
            _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
            hydrateLastKnownSettings(venueId, localIncludeTaxOverride)
        }

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
                if (response.code in 400..499) {
                    _settings.value = applyIncludeTaxOverride(TpvSettings.DEFAULT, localIncludeTaxOverride)
                    _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
                    clearPersistedSettings(venueId)
                }
                return
            }

            val body = response.body?.string() ?: return
            val result = json.decodeFromString<VenueSettingsResponse>(body)
            val terminalNavigation = result.data.toTerminalNavigationSettings()
            _terminalNavigation.value = terminalNavigation
            persistTerminalNavigation(venueId, terminalNavigation)

            // Modo de pantallas: el local manda mientras haya un cambio sin
            // confirmar; si no, se adopta el del server. Solo en el camino
            // exitoso — un refresh fallido no puede mover la caja de pantalla.
            when (
                val action = reconcileDisplayMode(
                    local = displayModePrefs.inverted.value,
                    dirty = displayModePrefs.dirty.value,
                    server = result.data?.deviceTerminal?.customerDisplayInverted,
                )
            ) {
                is DisplayModeAction.Adopt -> displayModePrefs.adoptFromServer(action.value)
                is DisplayModeAction.Push -> pushDisplayMode(
                    venueId = venueId,
                    terminalId = terminalNavigation.terminalId,
                    value = action.value,
                )
                DisplayModeAction.Keep -> Unit
            }

            // Plan gating (Phase ①): persist the OPTIONAL plan block. Absent
            // field (old server) → null tier → PlanManager fails OPEN. Only
            // written on a successful response so a transient error never
            // wipes a previously-known plan.
            secureStorage.planTier = result.data?.plan?.tier
            secureStorage.planExempt = result.data?.plan?.exempt == true
            Log.d("📦", "Plan: tier=${result.data?.plan?.tier ?: "none"} exempt=${result.data?.plan?.exempt == true}")

            if (result.data?.settings != null) {
                val resolvedSettings = applyIncludeTaxOverride(
                    base = result.data.settings,
                    localOverride = localIncludeTaxOverride,
                )
                _settings.value = resolvedSettings
                persistTpvSettings(venueId, resolvedSettings)
                Log.d("📦", "✅ Settings loaded (terminal: ${result.data.activeTerminalId})")
            } else {
                Log.d("📦", "No active terminal, using defaults")
                val resolvedSettings = applyIncludeTaxOverride(
                    base = TpvSettings.DEFAULT,
                    localOverride = localIncludeTaxOverride,
                )
                _settings.value = resolvedSettings
                persistTpvSettings(venueId, resolvedSettings)
            }
        } catch (e: Exception) {
            Log.w(
                "📦",
                "⚠️ Settings sin servidor (${e.message}) — conservo la última configuración del local",
            )
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Empuja el modo de pantallas de ESTE equipo. Si falla no pasa nada malo:
     * la bandera `dirty` sigue puesta y se reintenta en el próximo refresh.
     */
    private suspend fun pushDisplayMode(venueId: String, terminalId: String?, value: Boolean) {
        val id = terminalId ?: return
        val token = secureStorage.accessToken ?: return
        runCatching {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminals/$id/display-mode")
                .header("Authorization", "Bearer $token")
                .patch(
                    """{"customerDisplayInverted":$value}"""
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                // Solo se marca sincronizado si el valor que acabamos de
                // empujar SIGUE siendo el vigente. Si el cajero tocó el
                // interruptor dos veces rápido antes de que esta respuesta
                // llegara, `markSynced()` aquí bajaría `dirty` sin que el
                // valor NUEVO haya llegado al server — y el siguiente refresh
                // lo revertiría.
                if (displayModePrefs.inverted.value == value) {
                    displayModePrefs.markSynced()
                    Log.d("📦", "✅ Modo de pantallas sincronizado ($value)")
                } else {
                    Log.d(
                        "📦",
                        "↪️ El modo de pantallas cambió mientras se empujaba ($value) — no se marca sincronizado",
                    )
                }
            } else {
                Log.w("📦", "⚠️ El server rechazó el modo de pantallas: ${response.code}")
            }
        }.onFailure {
            Log.w("📦", "⚠️ Sin red para sincronizar el modo de pantallas — se reintenta después")
        }
    }

    suspend fun setIncludeTaxInTipBase(value: Boolean) {
        val venueId = secureStorage.venueId ?: GLOBAL_VENUE_KEY
        preferencesDataStore.setBoolean(includeTaxInTipBaseKey(venueId), value)
        _settings.update { it.copy(includeTaxInTipBase = value) }
    }

    fun clearCache() {
        loadedVenueId = null
        _settings.value = TpvSettings.DEFAULT
        _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
    }

    private suspend fun hydrateLastKnownSettings(
        venueId: String,
        localIncludeTaxOverride: Boolean?,
    ) {
        preferencesDataStore.getString(tpvSettingsKey(venueId)).first()?.let { cached ->
            runCatching { json.decodeFromString<TpvSettings>(cached) }
                .onSuccess {
                    _settings.value = applyIncludeTaxOverride(it, localIncludeTaxOverride)
                    Log.d("📦", "🗂️ TPV settings hidratados del cache para $venueId")
                }
                .onFailure { Log.w("📦", "Cache de TPV settings inválido: ${it.message}") }
        }

        preferencesDataStore.getString(terminalNavigationKey(venueId)).first()?.let { cached ->
            runCatching { json.decodeFromString<TerminalNavigationSettings>(cached) }
                .onSuccess {
                    _terminalNavigation.value = it
                    Log.d("📦", "🗂️ Perfil de terminal hidratado del cache para $venueId")
                }
                .onFailure { Log.w("📦", "Cache de terminal inválido: ${it.message}") }
        }
    }

    private suspend fun persistTpvSettings(venueId: String, settings: TpvSettings) {
        runCatching {
            preferencesDataStore.setString(
                tpvSettingsKey(venueId),
                json.encodeToString(TpvSettings.serializer(), settings),
            )
        }.onFailure { Log.w("📦", "No se pudo guardar cache de TPV settings: ${it.message}") }
    }

    private suspend fun persistTerminalNavigation(
        venueId: String,
        terminalNavigation: TerminalNavigationSettings,
    ) {
        runCatching {
            preferencesDataStore.setString(
                terminalNavigationKey(venueId),
                json.encodeToString(TerminalNavigationSettings.serializer(), terminalNavigation),
            )
        }.onFailure { Log.w("📦", "No se pudo guardar cache de terminal: ${it.message}") }
    }

    private suspend fun clearPersistedSettings(venueId: String) {
        runCatching {
            preferencesDataStore.removeString(tpvSettingsKey(venueId))
            preferencesDataStore.removeString(terminalNavigationKey(venueId))
        }.onFailure { Log.w("📦", "No se pudo invalidar el cache de terminal: ${it.message}") }
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

    private fun tpvSettingsKey(venueId: String): String = "${KEY_TPV_SETTINGS_PREFIX}_$venueId"

    private fun terminalNavigationKey(venueId: String): String =
        "${KEY_TERMINAL_NAVIGATION_PREFIX}_$venueId"

    companion object {
        private const val KEY_INCLUDE_TAX_IN_TIP_BASE_PREFIX = "include_tax_in_tip_base"
        private const val KEY_TPV_SETTINGS_PREFIX = "tpv_settings"
        private const val KEY_TERMINAL_NAVIGATION_PREFIX = "terminal_navigation"
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
    val customerDisplayInverted: Boolean = false,
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
        customerDisplayInverted = terminal.customerDisplayInverted,
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
