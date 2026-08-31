package com.avoqado.pos.tpvsettings.data

import android.util.Log
import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeAction
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.DisplayModeRequestStore
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
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Dónde pinta el POS el panel de promociones. Espejo EXACTO del enum
 * `PromotionPanelMode` del server (`schema.prisma`) y de iOS — un nombre que no
 * coincida falla en silencio.
 *
 * 🔴 `HIDDEN` es preferencia de LAYOUT del propio local (la eligió el dueño en
 * el dashboard y la puede revertir ahí), no un candado de plan. Por eso ésta sí
 * puede ocultar el panel sin explicar nada: el que lo apagó sabe dónde
 * prenderlo. El candado de TIER es otra cosa y siempre se ve (ver
 * `PromotionsPanel`).
 */
@Serializable
enum class PanelMode { HIDDEN, TAB, SIDE_PANEL }

/**
 * Ajuste de VENUE (no de terminal) que dice dónde va el panel de promociones en
 * cada una de las dos pantallas. Viaja en su propio objeto `promotions` del
 * payload de settings, al lado de `plan` — NO dentro de `settings`, que es por
 * terminal (ver `tpvSettings.mobile.controller.ts:147-150`).
 *
 * Defaults = los del server. Campo ausente (server viejo) ⇒ estos mismos
 * valores, nunca un crash.
 */
@Serializable
data class PromotionsPanelSettings(
    val panelCashier: PanelMode = PanelMode.TAB,
    val panelCustomer: PanelMode = PanelMode.SIDE_PANEL,
)

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
    /**
     * 🔴 El server NO lo manda aquí dentro: viaja como `data.promotions`, hermano
     * de `settings`. Se copia a este objeto en `refreshSettingsForVenue` porque
     * es lo que la pantalla de cobro ya lee (y así el cache del disco lo
     * conserva para el arranque sin red). Nunca se parsea de `data.settings`.
     */
    val promotions: PromotionsPanelSettings = PromotionsPanelSettings(),
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
class TpvSettingsRepository internal constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val preferencesDataStore: PreferencesDataStore,
    private val displayModePrefs: DisplayModePrefs,
    private val displayModeJournal: DisplayModeRequestJournal,
    private val deviceIdProvider: CanonicalDeviceIdProvider,
    private val displayModeAuthorityGate: DisplayModeAuthorityGate,
) {
    @Inject
    constructor(
        secureStorage: SecureStorage,
        client: OkHttpClient,
        preferencesDataStore: PreferencesDataStore,
        displayModePrefs: DisplayModePrefs,
        displayModeJournal: DisplayModeRequestStore,
        syncOutboxProvider: Provider<SyncOutbox>,
        displayModeAuthorityGate: DisplayModeAuthorityGate,
    ) : this(
        secureStorage = secureStorage,
        client = client,
        preferencesDataStore = preferencesDataStore,
        displayModePrefs = displayModePrefs,
        displayModeJournal = displayModeJournal,
        deviceIdProvider = CanonicalDeviceIdProvider { syncOutboxProvider.get().deviceId },
        displayModeAuthorityGate = displayModeAuthorityGate,
    )

    // `coerceInputValues`: un server NUEVO con un modo de panel que esta versión
    // de la app todavía no conoce cae al default del campo en vez de reventar el
    // parseo COMPLETO de settings. Fail-open: un valor desconocido no puede
    // dejar al local sin su configuración de terminal.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val _settings = MutableStateFlow(TpvSettings.DEFAULT)
    val settings: StateFlow<TpvSettings> = _settings.asStateFlow()

    private val _terminalNavigation = MutableStateFlow(TerminalNavigationSettings.DEFAULT)
    val terminalNavigation: StateFlow<TerminalNavigationSettings> = _terminalNavigation.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _managerPinOverrideEnabled = MutableStateFlow(secureStorage.managerPinOverrideEnabled)

    /**
     * ¿El local activó el PIN de autorización de gerente? Decide si una acción
     * sin permiso se ve con candado o se esconde como hoy.
     */
    val managerPinOverrideEnabled: StateFlow<Boolean> = _managerPinOverrideEnabled.asStateFlow()

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

            // Un journal legible O ilegible es una barrera: el settings legacy
            // no adopta ni empuja mientras la intención tipada puede estar
            // cruzando journal → preferencia → mudanza física → ACK.
            displayModeAuthorityGate.withAuthority {
                val hasRemoteDisplayBarrier = runCatching {
                    displayModeJournal.hasInFlight(venueId, deviceIdProvider.currentDeviceId())
                }.getOrDefault(true)
                if (!hasRemoteDisplayBarrier) {
                    val generation = displayModePrefs.generation.value
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
                            generation = generation,
                        )
                        DisplayModeAction.Keep -> Unit
                    }
                }
            }

            // Plan gating (Phase ①): persist the OPTIONAL plan block. Absent
            // field (old server) → null tier → PlanManager fails OPEN. Only
            // written on a successful response so a transient error never
            // wipes a previously-known plan.
            secureStorage.planTier = result.data?.plan?.tier
            secureStorage.planExempt = result.data?.plan?.exempt == true
            Log.d("📦", "Plan: tier=${result.data?.plan?.tier ?: "none"} exempt=${result.data?.plan?.exempt == true}")

            // PIN de autorización de gerente. Igual que el plan: SÓLO en el
            // camino exitoso. El `catch` de red no lo toca — un bache de WiFi no
            // puede borrar lo bueno y dejar al piso sin la puerta del candado.
            val overrideEnabled = result.data?.managerPinOverrideEnabled ?: false
            _managerPinOverrideEnabled.value = overrideEnabled
            secureStorage.managerPinOverrideEnabled = overrideEnabled

            // Panel de promociones: es de VENUE, así que aplica HAYA o NO una
            // terminal activa — por eso se resuelve fuera del `if` de abajo.
            val promotionsPanel = result.data?.promotions ?: PromotionsPanelSettings()

            if (result.data?.settings != null) {
                val resolvedSettings = applyIncludeTaxOverride(
                    base = result.data.settings.copy(promotions = promotionsPanel),
                    localOverride = localIncludeTaxOverride,
                )
                _settings.value = resolvedSettings
                persistTpvSettings(venueId, resolvedSettings)
                Log.d("📦", "✅ Settings loaded (terminal: ${result.data.activeTerminalId})")
            } else {
                Log.d("📦", "No active terminal, using defaults")
                val resolvedSettings = applyIncludeTaxOverride(
                    base = TpvSettings.DEFAULT.copy(promotions = promotionsPanel),
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
    private suspend fun pushDisplayMode(
        venueId: String,
        terminalId: String?,
        value: Boolean,
        generation: Long,
    ) {
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
                if (displayModePrefs.markSynced(generation, value)) {
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
        // El switch es por venue: al soltar el cache no puede quedarse encendido
        // el de la sucursal anterior.
        _managerPinOverrideEnabled.value = false
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
    /**
     * Panel de promociones. Es de VENUE (`VenueSettings`), no de terminal — por
     * eso viaja aquí y no dentro de `settings`. Ausente (server viejo) ⇒ null ⇒
     * el repositorio aplica los defaults.
     */
    val promotions: PromotionsPanelSettings? = null,
    /**
     * PIN de autorización de gerente. Es de VENUE, no de terminal — por eso vive
     * aquí y no dentro de `settings`. Default false: un server viejo (campo
     * ausente) se comporta exactamente como hoy.
     */
    val managerPinOverrideEnabled: Boolean = false,
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
