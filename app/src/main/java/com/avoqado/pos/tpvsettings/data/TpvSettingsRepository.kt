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
import com.avoqado.pos.printing.receiptlayout.ReceiptFiscalEmisor
import com.avoqado.pos.printing.receiptlayout.ReceiptLegacyFiscal
import com.avoqado.pos.printing.receiptlayout.ReceiptText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
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

/**
 * Encabezado del ticket impreso (logo + identidad fiscal, estilo SoftRestaurant).
 * Espejo del bloque `receiptInfo` del payload de settings (founder, 2026-09-01).
 * Todo opcional: server viejo (campo ausente) o venue sin emisor fiscal ⇒ el
 * ticket sale como siempre.
 */
@Serializable
data class ReceiptInfo(
    val name: String? = null,
    val logoUrl: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zipCode: String? = null,
    val legalName: String? = null,
    val rfc: String? = null,
    val lugarExpedicion: String? = null,
    /** Todos los emisores del venue con sus merchants: el ticket elige el de la cuenta que cobró. */
    val fiscalEmisors: List<ReceiptFiscalEmisor>? = null,
    val principalEmisorId: String? = null,
    /** Columnas legacy de Venue: la tercera fuente del RFC (spec § 5.6). */
    val legacy: ReceiptLegacyFiscal? = null,
) {
    /**
     * "Nápoles 47, Cuauhtémoc, Ciudad de México, CP 06600" — una línea; la
     * impresora la parte sola.
     *
     * 🔴 NO repite lo que la dirección ya dice. Medido en papel el 2026-09-01:
     * el `address` de un venue real ya venía completo —"Monte Himalaya 408,
     * Lomas de Chapultepec, Miguel Hidalgo, 11000 Ciudad de México, CDMX,
     * México"— y pegarle ciudad, estado y CP encima producía
     * "…México, Ciudad de México, Ciudad de México, CP 11000": tres renglones
     * de rollo desperdiciados diciendo lo mismo. La regla vive UNA vez, en el
     * intérprete (`ReceiptText.addressLine`), para que papel y pantalla no puedan divergir.
     */
    val addressLine: String?
        get() = ReceiptText.addressLine(address, city, state, zipCode)
}

/**
 * La receta del ticket tal como la manda el servidor (spec § 7.3). `blocks` se guarda CRUDO: el
 * parser tolerante decide al imprimir qué entiende esta versión de la app.
 */
@Serializable
data class ReceiptLayoutPayload(
    val schemaVersion: Int = 1,
    val revision: Int = 0,
    val blocks: JsonElement? = null,
)

/** Encabezado y receta del ticket de UNA sucursal, guardados JUNTOS: se escriben y se leen en pareja. */
@Serializable
data class ReceiptTicketCache(
    val info: ReceiptInfo? = null,
    val layout: ReceiptLayoutPayload? = null,
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
    /** Ver `DeviceTerminalSettingsDto.configurableSettings`. */
    val configurableSettings: List<String> = emptyList(),
) {
    companion object {
        const val STANDARD_POS = "STANDARD_POS"
        const val AREA_OPERATIONS = "AREA_OPERATIONS"
        val DEFAULT = TerminalNavigationSettings()
    }
}

/**
 * Qué pasó al intentar guardar un ajuste en la ficha de este aparato.
 *
 * 🔴 Existe en vez de un `Boolean` porque la UI tiene que poder DECIR qué pasó: «sin conexión» y
 * «no tienes permiso» exigen mensajes distintos, y un interruptor que se mueve solo porque el
 * guardado falló es una mentira en pantalla (`.claude/rules/todo-funciona-sin-red.md`).
 */
sealed interface AjusteGuardado {
    /** Guardado en el servidor y reflejado en la app. */
    data object Ok : AjusteGuardado

    /** No hubo red. El ajuste NO cambió: este carril es online-only a propósito. */
    data object SinConexion : AjusteGuardado

    /** El servidor rechazó por permisos (403). Hoy, por defecto, sólo el dueño puede. */
    data object SinPermiso : AjusteGuardado

    /** Nunca hemos sincronizado: no sabemos qué ficha es este aparato, así que no se escribe nada. */
    data object SinFicha : AjusteGuardado

    /** El servidor dijo no por otra razón (ajuste no soportado por el aparato, validación…). */
    data class Rechazado(val codigo: Int) : AjusteGuardado
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
    private val receiptLogoCache: com.avoqado.pos.printing.data.ReceiptLogoCache? = null,
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
        receiptLogoCache: com.avoqado.pos.printing.data.ReceiptLogoCache,
    ) : this(
        secureStorage = secureStorage,
        client = client,
        preferencesDataStore = preferencesDataStore,
        displayModePrefs = displayModePrefs,
        displayModeJournal = displayModeJournal,
        deviceIdProvider = CanonicalDeviceIdProvider { syncOutboxProvider.get().deviceId },
        displayModeAuthorityGate = displayModeAuthorityGate,
        receiptLogoCache = receiptLogoCache,
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

    private val receiptRequests = AtomicLong(0)
    private val receiptTicketMutex = Mutex()

    /**
     * Por sucursal y SÓLO en memoria (tras reiniciar el proceso, la primera respuesta manda): el número de
     * la petición más nueva que escribió cada campo, y el de la respuesta BUENA más nueva, traiga o no
     * campos del ticket.
     */
    private val receiptInfoSeq = mutableMapOf<String, Long>()
    private val receiptLayoutSeq = mutableMapOf<String, Long>()
    private val receiptOkSeq = mutableMapOf<String, Long>()

    /**
     * El ticket de [venueId]: la pareja guardada en el DISCO de esa sucursal, leída bajo el mismo candado
     * que la escribe. El que imprime lo pide con la sucursal activa; no depende de que en este proceso ya
     * haya corrido un refresh. Si el almacenamiento no se puede leer, sale con la canónica y lo que ya trae
     * la venta: un disco que falla no puede impedir imprimir.
     */
    suspend fun receiptTicketFor(venueId: String): ReceiptTicketCache = receiptTicketMutex.withLock {
        readReceiptTicket(venueId) ?: ReceiptTicketCache().also {
            Log.w("📦", "Ticket de $venueId sin datos guardados legibles: sale la canónica")
        }
    }

    /** null = el almacenamiento FALLÓ al leer (distinto de «no hay nada guardado»). */
    private suspend fun readReceiptTicket(venueId: String): ReceiptTicketCache? {
        val ticket = readStoredString(receiptTicketKey(venueId)) ?: return null
        val legacy = readStoredString(receiptInfoKey(venueId)) ?: return null
        return ticketFrom(mapOf(receiptTicketKey(venueId) to ticket.value, receiptInfoKey(venueId) to legacy.value), venueId)
    }

    /** Un valor leído del disco; `readStoredString` devuelve null si la LECTURA falló. */
    private class StoredString(val value: String?)

    private suspend fun readStoredString(key: String): StoredString? = try {
        StoredString(preferencesDataStore.getString(key).first())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("📦", "No se pudo leer $key: ${e.message}")
        null
    }

    /**
     * La pareja a partir de lo leído. La llave nueva manda (aunque traiga los dos campos vacíos: así una
     * negación no resucita lo viejo); si no está o es ilegible, el encabezado de la llave de la versión
     * anterior — el aparato recién actualizado no pierde su RFC en el primer ticket sin red.
     */
    private fun ticketFrom(values: Map<String, String?>, venueId: String): ReceiptTicketCache {
        values[receiptTicketKey(venueId)]?.let { cached ->
            runCatching { json.decodeFromString<ReceiptTicketCache>(cached) }
                .onFailure { Log.w("📦", "Ticket guardado ilegible: ${it.message}") }
                .getOrNull()
                ?.let { return it }
        }
        val legacyInfo = values[receiptInfoKey(venueId)]?.let { cached ->
            runCatching { json.decodeFromString<ReceiptInfo>(cached) }.getOrNull()
        }
        return ReceiptTicketCache(info = legacyInfo)
    }

    /**
     * UNA transacción de DataStore: parte de lo que HAY en el disco —si no se puede leer, DataStore lanza y
     * no se escribe nada—, aplica [change], guarda la pareja y retira la llave anterior. Devuelve si escribió.
     */
    private suspend fun writeReceiptTicket(venueId: String, change: (ReceiptTicketCache) -> ReceiptTicketCache): Boolean = try {
        preferencesDataStore.updateStrings(listOf(receiptTicketKey(venueId), receiptInfoKey(venueId))) { current ->
            val next = change(ticketFrom(current, venueId))
            mapOf(
                receiptTicketKey(venueId) to json.encodeToString(ReceiptTicketCache.serializer(), next),
                receiptInfoKey(venueId) to null,
            )
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("📦", "No se pudo escribir el ticket de $venueId: ${e.message}")
        false
    }

    /**
     * Aplica lo que trajo UNA respuesta BUENA de [venueId], campo por campo: se escribe sólo lo que vino y
     * sólo si ninguna petición más nueva de esa sucursal ya escribió ese campo. Una respuesta sin nada no
     * escribe nada, pero sí cuenta como respuesta buena (una negación más vieja ya no aplica). Devuelve `true`
     * si el ENCABEZADO se aplicó (así el logo no se baja de una respuesta descartada).
     */
    private suspend fun applyReceiptResponse(venueId: String, seq: Long, info: ReceiptInfo?, layout: ReceiptLayoutPayload?): Boolean =
        receiptTicketMutex.withLock {
            receiptOkSeq[venueId] = maxOf(receiptOkSeq[venueId] ?: 0L, seq)
            val applyInfo = info != null && seq > (receiptInfoSeq[venueId] ?: 0L)
            val applyLayout = layout != null && seq > (receiptLayoutSeq[venueId] ?: 0L)
            if (!applyInfo && !applyLayout) return@withLock false
            val written = writeReceiptTicket(venueId) { stored ->
                ReceiptTicketCache(
                    info = if (applyInfo) info else stored.info,
                    layout = if (applyLayout) layout else stored.layout,
                )
            }
            if (written && applyInfo) receiptInfoSeq[venueId] = seq
            if (written && applyLayout) receiptLayoutSeq[venueId] = seq
            written && applyInfo
        }

    /**
     * Una negación real del servidor (403 y compañía) vacía el ticket de [venueId] — pero SÓLO si es más
     * nueva que toda respuesta buena de esa sucursal: si una petición posterior ya contestó bien, el aparato
     * sí tiene acceso y esta negación es información vencida. Cuando aplica, vacía los dos campos (no hay
     * nada que preservar: ninguna respuesta buena es más nueva) y ninguna respuesta más vieja puede volver a
     * escribirlos.
     */
    private suspend fun invalidateReceiptTicket(venueId: String, seq: Long) {
        receiptTicketMutex.withLock {
            if (seq < (receiptOkSeq[venueId] ?: 0L)) return@withLock
            receiptInfoSeq[venueId] = maxOf(receiptInfoSeq[venueId] ?: 0L, seq)
            receiptLayoutSeq[venueId] = maxOf(receiptLayoutSeq[venueId] ?: 0L, seq)
            // Si el disco falla aquí, lo guardado queda como estaba hasta la siguiente negación con red
            // (declarado en D18, igual que el resto de settings de `clearPersistedSettings`).
            writeReceiptTicket(venueId) { ReceiptTicketCache() }
        }
    }

    // Para la descarga del logo en segundo plano: no puede alargar el refresh
    // de settings (que la selección de venue espera) ni tumbar nada si falla.
    private val logoScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

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
        // El número de ESTA petición: el ticket sólo acepta de ella lo que ninguna más nueva ya escribió.
        val receiptSeq = receiptRequests.incrementAndGet()
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
                // 408 (timeout) y 429 (límite de peticiones) son de la RED, no del permiso: borrar
                // lo guardado ahí deja al siguiente arranque sin red sin receta y sin RFC.
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    _settings.value = applyIncludeTaxOverride(TpvSettings.DEFAULT, localIncludeTaxOverride)
                    _terminalNavigation.value = TerminalNavigationSettings.DEFAULT
                    clearPersistedSettings(venueId)
                    invalidateReceiptTicket(venueId, receiptSeq)
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

            // Encabezado y receta del ticket: SÓLO en el camino exitoso y sólo lo que vino. Un campo
            // ausente (el servidor no pudo resolverlo) conserva lo guardado.
            val receiptInfo = result.data?.receiptInfo
            val infoApplied = applyReceiptResponse(venueId, receiptSeq, receiptInfo, result.data?.receiptLayout)
            // El logo se baja aparte y en segundo plano, y sólo si este encabezado es el vigente.
            if (infoApplied && receiptInfo != null) {
                receiptLogoCache?.let { cache -> logoScope.launch { cache.refresh(venueId, receiptInfo.logoUrl) } }
            }

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

    /**
     * Guarda en la ficha de ESTE aparato un ajuste de las pantallas del cobro.
     *
     * Vive en el servidor, no en el aparato (decisión del founder, 2026-09-18): así sobrevive a
     * reinstalar la app y el dashboard ve lo mismo. El precio, declarado: **sin red no se puede
     * cambiar**, y el llamador tiene que decirlo — por eso esto devuelve `AjusteGuardado` y sólo
     * mueve el estado visible cuando el servidor confirmó.
     */
    suspend fun guardarPantallasDelCobro(
        showReviewScreen: Boolean? = null,
        showTipScreen: Boolean? = null,
        tipSuggestions: List<Int>? = null,
    ): AjusteGuardado {
        val venueId = secureStorage.venueId ?: return AjusteGuardado.SinFicha
        val terminalId = _terminalNavigation.value.terminalId ?: return AjusteGuardado.SinFicha
        val token = secureStorage.accessToken ?: return AjusteGuardado.SinPermiso

        val cambios = buildMap<String, String> {
            showReviewScreen?.let { put("showReviewScreen", it.toString()) }
            showTipScreen?.let { put("showTipScreen", it.toString()) }
            // El servidor valida el contenido (1 a 100, sin repetidos, máximo 6) y es la única
            // autoridad: repetir esas reglas aquí sólo crearía una segunda versión que se desfasa.
            tipSuggestions?.let { put("tipSuggestions", it.joinToString(",", "[", "]")) }
        }
        if (cambios.isEmpty()) return AjusteGuardado.Ok

        val cuerpo = cambios.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        val request = Request.Builder()
            .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminals/$terminalId/settings")
            .header("Authorization", "Bearer $token")
            .patch(cuerpo.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            withContext(Dispatchers.IO) { client.newCall(request).execute() }
        } catch (error: IOException) {
            Log.w("📦", "Sin red al guardar las pantallas del cobro: ${error.message}")
            return AjusteGuardado.SinConexion
        }

        response.use {
            if (!it.isSuccessful) {
                Log.e("📦", "❌ El servidor rechazó el ajuste: ${it.code}")
                return if (it.code == 401 || it.code == 403) AjusteGuardado.SinPermiso else AjusteGuardado.Rechazado(it.code)
            }
        }

        // Sólo ahora: lo que se ve en pantalla es lo que el servidor ya guardó.
        _settings.update { actuales ->
            actuales.copy(
                showReviewScreen = showReviewScreen ?: actuales.showReviewScreen,
                showTipScreen = showTipScreen ?: actuales.showTipScreen,
                tipSuggestions = tipSuggestions ?: actuales.tipSuggestions,
            )
        }
        persistTpvSettings(venueId, _settings.value)
        return AjusteGuardado.Ok
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

    private fun receiptInfoKey(venueId: String): String = "${KEY_RECEIPT_INFO_PREFIX}_$venueId"

    private fun receiptTicketKey(venueId: String): String = "${KEY_RECEIPT_TICKET_PREFIX}_$venueId"

    companion object {
        private const val KEY_INCLUDE_TAX_IN_TIP_BASE_PREFIX = "include_tax_in_tip_base"
        private const val KEY_TPV_SETTINGS_PREFIX = "tpv_settings"
        private const val KEY_TERMINAL_NAVIGATION_PREFIX = "terminal_navigation"
        private const val KEY_RECEIPT_INFO_PREFIX = "receipt_info"
        private const val KEY_RECEIPT_TICKET_PREFIX = "receipt_ticket"
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
    /**
     * Encabezado del ticket impreso. Ausente (server viejo) ⇒ null ⇒ el ticket
     * sale sin encabezado fiscal, como hoy.
     */
    val receiptInfo: ReceiptInfo? = null,
    /** La receta del ticket. Ausente (server viejo o fallo al resolverla) ⇒ se conserva la guardada. */
    val receiptLayout: ReceiptLayoutPayload? = null,
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
    /**
     * Qué ajustes puede cambiar ESTE aparato desde Más > Configuración. La lista la manda el
     * SERVIDOR (`device-capabilities.service.ts`) a propósito: si viviera aquí codificada, el día
     * que un tipo de aparato gane o pierda un ajuste habría que publicar un APK. Ausente (server
     * viejo) ⇒ vacía ⇒ no se ofrece nada, que es exactamente el comportamiento de hoy.
     */
    val configurableSettings: List<String> = emptyList(),
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
        configurableSettings = terminal.configurableSettings,
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
