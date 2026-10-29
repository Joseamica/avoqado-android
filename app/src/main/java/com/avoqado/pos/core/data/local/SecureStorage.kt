package com.avoqado.pos.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.avoqado.pos.areatickets.data.IntentoDeVale
import com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest
import com.avoqado.pos.areatickets.data.ValeSinConfirmar
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import com.avoqado.pos.transactions.data.model.PendingCustomerAssignment
import com.avoqado.pos.core.domain.PlanSnapshot
import com.avoqado.pos.core.domain.retainNewestPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /// Emitted when the session is wiped (e.g. a failed token refresh in the
    /// authenticator). Before, nothing observed this, so the UI stayed in the
    /// main scaffold with every request silently 401ing until a force-restart
    /// (zombie session). AppState collects this and flips isLoggedIn=false.
    private val _sessionInvalidated = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionInvalidated: kotlinx.coroutines.flow.SharedFlow<Unit> = _sessionInvalidated

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "avoqado_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences("avoqado_prefs", Context.MODE_PRIVATE)

    fun readPendingCustomerAssignment(actorId: String, venueId: String, paymentId: String): PendingCustomerAssignment? =
        prefs.getString(customerAssignmentKey(actorId, venueId, paymentId), null)?.let {
            json.decodeFromString<PendingCustomerAssignment>(it)
        }

    fun writePendingCustomerAssignment(pending: PendingCustomerAssignment): Boolean =
        prefs.edit().putString(
            customerAssignmentKey(pending.actorId, pending.venueId, pending.paymentId), json.encodeToString(pending),
        ).commit()

    fun clearPendingCustomerAssignment(pending: PendingCustomerAssignment): Boolean =
        prefs.edit().remove(customerAssignmentKey(pending.actorId, pending.venueId, pending.paymentId)).commit()

    private fun customerAssignmentKey(actorId: String, venueId: String, paymentId: String) =
        "pendingCustomerAssignment.$actorId.$venueId.$paymentId"

    // MARK: - Session Data

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userFirstName: String?
        get() = prefs.getString(KEY_USER_FIRST_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_FIRST_NAME, value).apply()

    var userLastName: String?
        get() = prefs.getString(KEY_USER_LAST_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_LAST_NAME, value).apply()

    var userRole: String?
        get() = prefs.getString(KEY_USER_ROLE, null)
        set(value) = prefs.edit().putString(KEY_USER_ROLE, value).apply()

    var venueId: String?
        get() = prefs.getString(KEY_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_VENUE_ID, value).apply()

    var venueName: String?
        get() = prefs.getString(KEY_VENUE_NAME, null)
        set(value) = prefs.edit().putString(KEY_VENUE_NAME, value).apply()

    var venueSlug: String?
        get() = prefs.getString(KEY_VENUE_SLUG, null)
        set(value) = prefs.edit().putString(KEY_VENUE_SLUG, value).apply()

    var venueTimezone: String?
        get() = prefs.getString(KEY_VENUE_TIMEZONE, null)
        set(value) {
            prefs.edit().putString(KEY_VENUE_TIMEZONE, value).apply()
            com.avoqado.pos.core.util.VenueTimeZone.set(value)
        }

    var venueMode: String?
        get() = prefs.getString(KEY_VENUE_MODE, null)
        set(value) { prefs.edit().putString(KEY_VENUE_MODE, value).apply() }

    var reservationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_RESERVATIONS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_RESERVATIONS_ENABLED, value).apply() }

    /**
     * Vales por área (AREA_TICKETS) — opt-in POR VENUE, apagado por defecto.
     *
     * 🔴 El tier NO alcanza como gate, y por eso existe esta bandera. `AREA_TICKETS` es PRO
     * y PRO es *blanket* en el server: TODO venue PRO lo recibe. Gatear sólo con
     * `PlanManager.hasFeature()` haría aparecer el escaneo de vales y las pantallas de
     * entrega en cada tienda retail y cada restaurante del parque — negocios que no tienen
     * áreas ni vales y para los que eso es puro ruido, o peor, un flujo que rompe el suyo.
     *
     * Peor todavía: `hasFeature()` **falla ABIERTO** por diseño (código desconocido → `true`,
     * para no dejar un POS inservible por un plan que no se pudo leer). Esa ley es correcta
     * para no bloquear ventas, y es exactamente la equivocada aquí: sin esta bandera, un
     * fallo al leer el plan encendería vales en todo el parque.
     *
     * Default `false` = el parque entero se comporta idéntico a hoy. Se enciende venue por
     * venue en la instalación. Mismo patrón que [reservationsEnabled].
     */
    var areaTicketsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AREA_TICKETS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_AREA_TICKETS_ENABLED, value).apply() }

    var areaTicketCheckoutId: String?
        get() = prefs.getString(KEY_AREA_TICKET_CHECKOUT_ID, null)
        set(value) = prefs.edit().putString(KEY_AREA_TICKET_CHECKOUT_ID, value).apply()

    var areaTicketCheckoutVenueId: String?
        get() = prefs.getString(KEY_AREA_TICKET_CHECKOUT_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_AREA_TICKET_CHECKOUT_VENUE_ID, value).apply()

    var areaTicketCreateKey: String?
        get() = prefs.getString(KEY_AREA_TICKET_CREATE_KEY, null)
        set(value) = prefs.edit().putString(KEY_AREA_TICKET_CREATE_KEY, value).apply()

    var areaTicketMaterializeKey: String?
        get() = prefs.getString(KEY_AREA_TICKET_MATERIALIZE_KEY, null)
        set(value) = prefs.edit().putString(KEY_AREA_TICKET_MATERIALIZE_KEY, value).apply()

    /** 🔴 D2 (vales-ciclo): la llave de emisión de vales de [venueId]; ver [LlaveDeEmisionDeVales]. */
    fun llaveDeEmisionDeVales(venueId: String): String? = LlaveDeEmisionDeVales.leer(prefs, venueId)

    /** `true` sólo si la llave quedó en disco. */
    fun guardarLlaveDeEmisionDeVales(venueId: String, llave: String): Boolean = LlaveDeEmisionDeVales.guardar(prefs, venueId, llave)

    /** Suelta la llave de [venueId] (y lo que se mandó con ella); con [siEs], sólo si sigue siendo ésa. */
    fun soltarLlaveDeEmisionDeVales(venueId: String, siEs: String? = null) {
        LlaveDeEmisionDeVales.soltar(prefs, venueId, siEs)
    }

    /** 🔴 D5: llave + lo que se manda, juntos en disco ANTES del POST. Ver [LlaveDeEmisionDeVales.guardar]. */
    internal fun guardarIntentoDeVale(venueId: String, llave: String, intento: IntentoDeVale) =
        LlaveDeEmisionDeVales.guardar(prefs, venueId, llave, intento)

    /** 🔴 D5: primera vez de [venueId] en este proceso. Ver [LlaveDeEmisionDeVales.alArrancar]. */
    fun revisarValeAMedias(venueId: String): Boolean = LlaveDeEmisionDeVales.alArrancar(prefs, venueId)

    fun valeSinConfirmar(venueId: String) = LlaveDeEmisionDeVales.valeSinConfirmar(prefs, venueId)

    /** 🔴 D5: ver [LlaveDeEmisionDeVales.mercanciaEnDuda]. */
    fun mercanciaDeValeEnDuda(venueId: String, lineas: List<IssueAreaTicketLineRequest>): Boolean =
        LlaveDeEmisionDeVales.mercanciaEnDuda(prefs, venueId, lineas)

    fun olvidarValeSinConfirmar(venueId: String, llave: String) = LlaveDeEmisionDeVales.olvidarSinConfirmar(prefs, venueId, llave)

    /** 🔴 D5: papel pendiente + olvidar el vale sin confirmar, juntos. Ver [LlaveDeEmisionDeVales.recuperado]. */
    fun valeRecuperado(venueId: String, llave: String, codigo: String): Boolean = LlaveDeEmisionDeVales.recuperado(
        prefs, venueId, llave, codigo, KEY_PENDING_AREA_TICKET_PRINT_CODE, KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID,
    )

    var pendingAreaTicketPrintCode: String?
        get() = prefs.getString(KEY_PENDING_AREA_TICKET_PRINT_CODE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_AREA_TICKET_PRINT_CODE, value).apply()

    var pendingAreaTicketPrintVenueId: String?
        get() = prefs.getString(KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID, value).apply()

    /** D12: registros de impresión pendientes (JSON de una lista). Sólo lectura: se escribe con [commitAreaTicketPrintRecords]. */
    val pendingAreaTicketPrintRecords: String?
        get() = prefs.getString(KEY_PENDING_AREA_TICKET_PRINT_RECORDS, null)

    /**
     * D12: la salida (papel o PDF) YA ocurrió. En UN `commit()` (síncrono, como `escribirPendientes`) se guarda la
     * lista y, si [clearPendingReprint], se suelta el «pendiente de reimpresión»: si la app muere justo después, al
     * volver NO ofrece reimprimir (sería un segundo papel) y el registro sigue en la lista.
     */
    fun commitAreaTicketPrintRecords(json: String?, clearPendingReprint: Boolean): Boolean {
        val editor = prefs.edit()
        if (json == null) editor.remove(KEY_PENDING_AREA_TICKET_PRINT_RECORDS) else editor.putString(KEY_PENDING_AREA_TICKET_PRINT_RECORDS, json)
        if (clearPendingReprint) {
            editor.remove(KEY_PENDING_AREA_TICKET_PRINT_CODE)
            editor.remove(KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID)
        }
        return editor.commit()
    }

    /**
     * Contextos de vales que no deben quedar huérfanos por logout/cambio de
     * venue. `venueId == null` consulta todos (logout); con venue filtra el
     * contexto actual (cambio de establecimiento).
     */
    fun areaTicketRecoveryCount(venueId: String? = null): Int {
        val checkoutMatches = areaTicketCheckoutId != null &&
            (venueId == null || areaTicketCheckoutVenueId == venueId)
        val printVenue = pendingAreaTicketPrintVenueId
        val printMatches = pendingAreaTicketPrintCode != null &&
            (venueId == null || printVenue == null || printVenue == venueId)
        return listOf(checkoutMatches, printMatches).count { it }
    }

    /**
     * Plan tier for the active venue ("FREE"|"PRO"|"PREMIUM"|"ENTERPRISE"),
     * parsed from the venue-settings response. Null = unknown (old server or
     * not fetched yet) → PlanManager fails OPEN (no gates), matching today's
     * behavior exactly.
     */
    var planTier: String?
        get() = planSnapshot?.tier ?: prefs.getString(KEY_PLAN_TIER, null)
        set(value) = prefs.edit().putString(KEY_PLAN_TIER, value).apply()

    private val planRevision = mutableIntStateOf(0)

    val planSnapshot: PlanSnapshot?
        get() {
            // Track reads from Composables, including gates accessed through ViewModel getters.
            planRevision.intValue
            return venueId?.let(::readPlanSnapshot)
        }

    private fun readPlanSnapshot(venueId: String): PlanSnapshot? =
        prefs.getString("planSnapshot.$venueId", null)?.let {
            runCatching { json.decodeFromString<PlanSnapshot>(it) }.getOrNull()
        }

    @Synchronized
    fun storePlanSnapshot(venueId: String, observed: PlanSnapshot?) {
        val previous = readPlanSnapshot(venueId)
        val next = retainNewestPlan(previous, observed) ?: return
        if (next != previous) {
            check(prefs.edit().putString("planSnapshot.$venueId", json.encodeToString(next)).commit()) {
                "Could not persist venue access"
            }
            planRevision.intValue += 1
        }
    }

    /**
     * Sucursales donde el servidor contestó que la merma no está en el plan (403 con `featureCode`).
     * Lo escribe y lo lee `BloqueoDeMermaPorPlan`; no se borra al cerrar sesión.
     */
    var venuesConMermaBloqueada: Set<String>
        get() = prefs.getStringSet(KEY_WASTE_PLAN_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_WASTE_PLAN_BLOCKED, value).apply() }

    /** Exempt venues (grandfathered legacy / demo) bypass all plan gates. */
    var planExempt: Boolean
        get() = planSnapshot?.exempt ?: prefs.getBoolean(KEY_PLAN_EXEMPT, false)
        set(value) { prefs.edit().putBoolean(KEY_PLAN_EXEMPT, value).apply() }

    /**
     * Último valor conocido del switch de PIN de autorización del venue activo.
     *
     * Decide si una acción sin permiso se ve con candado o se esconde. Default
     * false: un server viejo (campo ausente) se comporta exactamente como hoy.
     */
    var managerPinOverrideEnabled: Boolean
        get() = prefs.getBoolean(KEY_MANAGER_PIN_OVERRIDE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_MANAGER_PIN_OVERRIDE_ENABLED, value).apply() }

    /** Last known switch for the active venue, cleared when leaving that venue. */
    var exchangesEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXCHANGES_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_EXCHANGES_ENABLED, value).apply() }

    /**
     * Permissions for the active venue (e.g. "reservations:create", "menu:read").
     * Server resolves these per-venue from role + custom overrides; client mirrors them
     * to gate UI affordances. See server lib/permissions.ts DEFAULT_PERMISSIONS.
     */
    /**
     * Cola DURABLE del cajón, POR VENUE (JSON): cierres, ingresos y retiros que el server todavía no
     * confirmó. Se guarda ANTES de intentar la red (Codex, 2ª auditoría): si el proceso muere entre
     * cerrar en local y encolar, el cierre no se pierde. Ver `CashDrawerRepository.reproducirPendientes`.
     */
    fun pendingDrawerOpsJson(venueId: String): String? = prefs.getString("pendingDrawerOps.$venueId", null)

    /**
     * 🔴 `commit()`, NO `apply()` (hallazgo M2). `apply()` escribe a disco de forma ASÍNCRONA: si
     * el proceso muere entre encolar y el flush, la intención —el fondo que el cajero acaba de
     * contar, o su retiro— se pierde, que es justo lo que esta cola existe para impedir. `commit()`
     * cuesta unos milisegundos en un camino que ya iba a tocar la red; la cola del cajón es lo
     * único de esta clase que se escribe en el camino del dinero.
     */
    fun setPendingDrawerOpsJson(venueId: String, value: String?) {
        prefs.edit().putString("pendingDrawerOps.$venueId", value).commit()
    }

    /**
     * Avisos de que una apertura terminó ADOPTANDO la caja de alguien más (hallazgo I1). Durable
     * por el mismo motivo que la cola: el cajero tiene que verlo aunque la app se reinicie antes.
     */
    fun drawerAdoptionNoticesJson(venueId: String): String? = prefs.getString("drawerAdoptionNotices.$venueId", null)
    fun setDrawerAdoptionNoticesJson(venueId: String, value: String?) {
        prefs.edit().putString("drawerAdoptionNotices.$venueId", value).commit()
    }

    /**
     * Alias de las cajas de ESTE aparato, POR VENUE (JSON): «id local de la caja → id que le dio el servidor» (efectivo sin
     * red en el turno correcto, 21-oct-2026). Un cobro guardado con el id local de su caja viaja con el del servidor. Durable
     * y con `commit()` por lo mismo que la cola del cajón: se escribe ANTES de reapuntar la cola, y si se perdiera, el cobro
     * subiría sin su caja. Ver `CashDrawerRepository.idDeCajaParaElServidor`.
     */
    fun drawerSessionAliasesJson(venueId: String): String? = prefs.getString("drawerSessionAliases.$venueId", null)
    fun setDrawerSessionAliasesJson(venueId: String, value: String?) {
        prefs.edit().putString("drawerSessionAliases.$venueId", value).commit()
    }

    var venuePermissions: List<String>
        get() {
            val raw = prefs.getString(KEY_VENUE_PERMISSIONS, null) ?: return emptyList()
            return try {
                json.decodeFromString(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            prefs.edit().putString(KEY_VENUE_PERMISSIONS, json.encodeToString(value)).apply()
        }

    /** Ajustes sin secretos por negocio; "null" distingue un 404 de una caché que nunca existió. */
    fun leerAjustesDeTarjetas(venueId: String): String? = plainPrefs.getString("giftCardSettings.$venueId", null)

    fun guardarAjustesDeTarjetas(venueId: String, json: String?) {
        plainPrefs.edit().putString("giftCardSettings.$venueId", json ?: "null").apply()
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /**
     * Los cobros con TARJETA cuyo desenlace NO consta (ver [PendientesDeTarjeta]): VARIOS a la vez desde el 25-sep.
     *
     * 🔴 Viven en disco a propósito: son lo único que impide un segundo cargo cuando la app
     * muere, el cajero cambia de pestaña, o se va a Transacciones a ver si el pago entró —
     * que es justo lo que hace la gente frente a la pantalla "Cobro sin confirmar". En RAM
     * esa ceremonia se evapora y el siguiente "Cobrar" arranca limpio, sin advertencia.
     *
     * Cada cobro se escribe ANTES del POST y se borra SÓLO cuando su desenlace consta (cobró o no
     * cobró), siempre que corresponda a la misma identidad.
     * **NO se limpia en `clearSession`**: un cobro sin confirmar no deja de existir porque
     * alguien cierre sesión o cambie de venue.
     *
     * La primera lectura migra la llave ÚNICA de versiones anteriores; si la migración no pudo escribirse, se lee igual
     * desde la llave vieja (nunca se pierde). `@Synchronized` como las escrituras: una lectura que migra en paralelo a
     * [persistPendingCardCharge] escribiría su lista vieja ENCIMA de la que ya trae el cobro nuevo, y ese pendiente se
     * perdería.
     */
    val pendingCardChargesJson: String?
        @Synchronized get() = prefs.getString(KEY_PENDING_CARD_CHARGES, null) ?: migrarLlaveUnica()

    /**
     * 🔴 La llave única vieja sólo se LEE aquí, una vez, para pasarla a la lista. Nadie la vuelve a escribir: con la lista
     * ya creada, un escritor que siguiera en la llave vieja escondería su pendiente y la venta siguiente cobraría sin freno.
     */
    private fun migrarLlaveUnica(): String? {
        val migrada = PendientesDeTarjeta.desdeLlaveUnica(
            prefs.getString(KEY_PENDING_CARD_CHARGE, null),
            prefs.getString(KEY_PENDING_CARD_CHARGE_CONTEXT, null),
        ) ?: return null
        // Si la escritura falla, la llave vieja no se borró en disco: se vuelve a migrar en la siguiente lectura.
        prefs.edit().putString(KEY_PENDING_CARD_CHARGES, migrada)
            .remove(KEY_PENDING_CARD_CHARGE).remove(KEY_PENDING_CARD_CHARGE_CONTEXT).commit()
        return migrada
    }

    /** Payment-only synchronous journal: no network authorization before a checked commit. */
    @Synchronized
    fun persistPendingCardCharge(requestId: String, orderId: String?, contextJson: String): Boolean =
        escribirPendientes { PendientesDeTarjeta.agregar(it, requestId, orderId, contextJson) }

    /**
     * H2 (26-sep): un cobro que SÍ pasó se MARCA en su lugar (o vuelve, marcado, si este proceso lo había soltado) y se queda
     * hasta el «Entendido» de ESE cobro: la venta siguiente, o reabrir la app, ya no lo callan.
     */
    @Synchronized
    fun persistCobroQueSiPaso(requestId: String, orderId: String?, contextJson: String, cobradoEn: Long): Boolean =
        escribirPendientes { PendientesDeTarjeta.agregar(it, requestId, orderId, contextJson, cobrado = true, cobradoEn = cobradoEn) }

    @Synchronized
    fun removePendingCardCharge(requestId: String) {
        escribirPendientes { PendientesDeTarjeta.quitar(it, requestId) }
    }

    /**
     * La lista nueva, en UN commit. M-10: si la que había no se pudo leer, en ese MISMO commit se guarda tal cual en
     * [KEY_PENDING_CARD_CHARGES_ILEGIBLE] — antes se leía como vacía y la escritura siguiente la borraba de verdad.
     */
    private fun escribirPendientes(nueva: (String?) -> String): Boolean {
        val crudo = pendingCardChargesJson
        val editor = prefs.edit().putString(KEY_PENDING_CARD_CHARGES, nueva(crudo))
        PendientesDeTarjeta.ilegible(crudo)?.let {
            android.util.Log.w("💳", "⚠️ La lista de cobros pendientes no se pudo leer (${it.length} caracteres): se respalda antes de escribir encima")
            editor.putString(KEY_PENDING_CARD_CHARGES_ILEGIBLE, it)
        }
        return editor.commit()
    }

    // MARK: - Biometric Data (NOT cleared on logout)

    private var solicitudesCargadas = false
    private var solicitudesConfirmadas: String? = null
    private var respaldoSolicitudesConfirmado: String? = null
    val giftCardRequestsJson: String?
        @Synchronized get() {
            if (!solicitudesCargadas) {
                solicitudesConfirmadas = prefs.getString(KEY_GIFT_CARD_REQUESTS, null)
                respaldoSolicitudesConfirmado = prefs.getString(KEY_GIFT_CARD_REQUESTS_ILEGIBLE, null)
                solicitudesCargadas = true
            }
            return solicitudesConfirmadas
        }
    @Synchronized
    fun escribirSolicitudesDeTarjeta(nueva: (String?) -> String?): Boolean {
        val raw = giftCardRequestsJson
        val siguiente = nueva(raw) ?: return false
        val editor = prefs.edit().putString(KEY_GIFT_CARD_REQUESTS, siguiente)
        val ilegible = com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.ilegible(raw)
        ilegible?.let { editor.putString(KEY_GIFT_CARD_REQUESTS_ILEGIBLE, it) }
        if (!editor.commit()) return false
        solicitudesConfirmadas = siguiente
        if (ilegible != null) respaldoSolicitudesConfirmado = ilegible
        return true
    }

    var isBiometricLoginEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var biometricRefreshToken: String?
        get() = prefs.getString(KEY_BIOMETRIC_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_REFRESH_TOKEN, value).apply()

    var biometricUserEmail: String?
        get() = prefs.getString(KEY_BIOMETRIC_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_USER_EMAIL, value).apply()

    var biometricUserId: String?
        get() = prefs.getString(KEY_BIOMETRIC_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_USER_ID, value).apply()

    var biometricVenueId: String?
        get() = prefs.getString(KEY_BIOMETRIC_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_VENUE_ID, value).apply()

    // MARK: - Device Data

    var serialNumber: String
        get() {
            val existing = prefs.getString(KEY_SERIAL_NUMBER, null)
            if (existing != null) return existing
            val generated = "AVQD-${UUID.randomUUID().toString().replace("-", "").take(12).uppercase()}"
            prefs.edit().putString(KEY_SERIAL_NUMBER, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_SERIAL_NUMBER, value).apply()

    var terminalId: String?
        get() = prefs.getString(KEY_TERMINAL_ID, null)
        set(value) = prefs.edit().putString(KEY_TERMINAL_ID, value).apply()

    var userPin: String?
        get() = prefs.getString(KEY_USER_PIN, null)
        set(value) = prefs.edit().putString(KEY_USER_PIN, value).apply()

    val selectedStaffIdForCurrentVenue: String?
        get() = plainPrefs.getString(selectedStaffIdKey(), null)

    val selectedStaffNameForCurrentVenue: String?
        get() = plainPrefs.getString(selectedStaffNameKey(), null)

    var calendarViewForCurrentVenue: String?
        get() = plainPrefs.getString(calendarViewKey(), null)
        set(value) {
            plainPrefs.edit().putString(calendarViewKey(), value).apply()
        }

    var showClassSessionsForCurrentVenue: Boolean
        get() = plainPrefs.getBoolean(showClassSessionsKey(), true)
        set(value) {
            plainPrefs.edit().putBoolean(showClassSessionsKey(), value).apply()
        }

    /** Plano en el POS (spec 2026-10-09 §4.2): la pestaña de área que se dejó abierta, por aparato y venue. */
    fun floorAreaTab(venueId: String): String? = plainPrefs.getString("${KEY_FLOOR_AREA_TAB_PREFIX}_$venueId", null)

    fun setFloorAreaTab(venueId: String, tabKey: String) {
        plainPrefs.edit().putString("${KEY_FLOOR_AREA_TAB_PREFIX}_$venueId", tabKey).apply()
    }

    /**
     * Sellos tardíos (spec §4.1): el último `stampsEnabled` que dijo el servidor para ESE venue (liga o consulta del
     * sello). Decide si una venta encolada sin red puede prometer «El sello se suma al sincronizar», también después de
     * reiniciar la app sin red. null = este aparato nunca lo supo ⇒ no se promete. No es dato personal: va en las
     * preferencias simples, como la pestaña del plano.
     */
    fun programaDeSellosActivo(venueId: String): Boolean? {
        val llave = "${KEY_PROGRAMA_DE_SELLOS_PREFIX}_$venueId"
        return if (plainPrefs.contains(llave)) plainPrefs.getBoolean(llave, false) else null
    }

    fun setProgramaDeSellosActivo(venueId: String, activo: Boolean) {
        plainPrefs.edit().putBoolean("${KEY_PROGRAMA_DE_SELLOS_PREFIX}_$venueId", activo).apply()
    }

    fun saveSelectedStaffForCurrentVenue(staffId: String, staffName: String) {
        val currentVenueId = venueId ?: return
        plainPrefs.edit()
            .putString(selectedStaffIdKey(currentVenueId), staffId)
            .putString(selectedStaffNameKey(currentVenueId), staffName)
            .apply()
    }

    fun clearSelectedStaffForCurrentVenue() {
        val currentVenueId = venueId ?: return
        plainPrefs.edit()
            .remove(selectedStaffIdKey(currentVenueId))
            .remove(selectedStaffNameKey(currentVenueId))
            .apply()
    }

    // MARK: - Venues List

    var venuesList: List<StoredVenue>
        get() {
            val raw = plainPrefs.getString(KEY_VENUES_LIST, null) ?: return emptyList()
            return try {
                json.decodeFromString(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_VENUES_LIST, json.encodeToString(value)).apply()
        }

    /** Venue ACTUAL resuelto desde la lista (por venueId). Fuente robusta para
     *  la pantalla del cliente: el campo suelto `venueName` a veces viene vacío. */
    private val currentVenue: StoredVenue?
        get() = venueId?.let { id -> venuesList.firstOrNull { it.id == id } }

    /** Logo del venue ACTUAL (para la pantalla del cliente en reposo). Null si no tiene. */
    val venueLogo: String?
        get() = currentVenue?.logo

    /** Giro del venue ACTUAL (`Venue.type`). Sólo SUGIERE el modo de punto de venta. */
    val venueType: String?
        get() = currentVenue?.venueType

    /** Nombre a mostrar del venue: el de la lista si el campo suelto viene vacío. */
    val venueDisplayName: String?
        get() = venueName?.takeIf { it.isNotBlank() } ?: currentVenue?.name

    var biometricVenuesList: List<StoredVenue>
        get() {
            val raw = plainPrefs.getString(KEY_BIOMETRIC_VENUES_LIST, null) ?: return emptyList()
            return try {
                json.decodeFromString(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_BIOMETRIC_VENUES_LIST, json.encodeToString(value)).apply()
        }

    // MARK: - Login helpers

    val isLoggedIn: Boolean
        get() = accessToken != null && userId != null

    @Synchronized
    fun saveLogin(
        userId: String,
        email: String,
        firstName: String?,
        lastName: String?,
        venueId: String,
        venueName: String,
        venueSlug: String?,
        role: String?,
        accessToken: String,
        refreshToken: String,
        venueTimezone: String? = null,
        venuePermissions: List<String> = emptyList(),
    ) {
        this.userId = userId
        this.userEmail = email
        this.userFirstName = firstName
        this.userLastName = lastName
        this.venueId = venueId
        this.venueName = venueName
        this.venueSlug = venueSlug
        this.venueTimezone = venueTimezone
        this.userRole = role
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.venuePermissions = venuePermissions
    }

    @Synchronized
    fun updateTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    /**
     * Guarda una sesión RENOVADA sólo si sigue siendo la que se mandó a renovar ([refreshUsado]). Mientras la renovación
     * viajaba, otra persona pudo entrar con su PIN o cerrarse la sesión: pisarla dejaba el nombre y los permisos de B con
     * las llaves de A (Codex, D9 del Cambio, 29-oct). Comparar y escribir van bajo el mismo candado que [saveLogin] y
     * [clearSession].
     */
    @Synchronized
    fun updateTokensSiSigue(refreshUsado: String, accessToken: String, refreshToken: String): Boolean {
        if (this.refreshToken != refreshUsado) return false
        updateTokens(accessToken, refreshToken)
        return true
    }

    /** Cierra la sesión sólo si sigue siendo la de [refreshUsado]: un rechazo de la renovación que llega tarde no
     *  expulsa a quien entró después con su PIN (Codex, D9 del Cambio r2, 29-oct). */
    @Synchronized
    fun clearSessionSiSigue(refreshUsado: String?): Boolean {
        if (this.refreshToken != refreshUsado) return false
        clearSession()
        return true
    }

    /** Igual, para una petición ya reintentada que vuelve 401: cierra sólo si la sesión sigue con el access con que
     *  salió [accessUsado]. Sin access no se sabe de quién es, y no se cierra. */
    @Synchronized
    fun clearSessionSiSigueElAccess(accessUsado: String?): Boolean {
        if (accessUsado == null || this.accessToken != accessUsado) return false
        clearSession()
        return true
    }

    fun switchVenue(venue: StoredVenue) {
        this.venueId = venue.id
        this.venueName = venue.name
        this.venueSlug = venue.slug
        this.venueTimezone = venue.timezone
        this.userRole = venue.role
        this.venuePermissions = venue.permissions
        // Plan is per-venue: clear stale plan from the previous venue so we
        // fail OPEN until the new venue's settings (with plan) are fetched.
        this.planTier = null
        this.planExempt = false
        // El PIN de autorización también es POR VENUE. Se apaga hasta que
        // lleguen los ajustes de la nueva sucursal: heredar un ON ajeno pintaría
        // candados que ese local no activó, y al tocarlos saldría el 403 pelón.
        this.managerPinOverrideEnabled = false
        this.exchangesEnabled = false
    }

    fun saveBiometricCredentials(
        userId: String,
        email: String,
        venueId: String,
        refreshToken: String,
        venues: List<StoredVenue>,
    ) {
        biometricUserId = userId
        biometricUserEmail = email
        biometricVenueId = venueId
        biometricRefreshToken = refreshToken
        biometricVenuesList = venues
        isBiometricLoginEnabled = true
    }

    fun restoreBiometricSession(venue: StoredVenue?): Boolean {
        val restoredUserId = biometricUserId ?: return false
        val restoredVenue = venue ?: return false

        userId = restoredUserId
        userEmail = biometricUserEmail
        switchVenue(restoredVenue)
        return true
    }

    @Synchronized
    fun clearSession() {
        _sessionInvalidated.tryEmit(Unit)
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_FIRST_NAME)
            .remove(KEY_USER_LAST_NAME)
            .remove(KEY_USER_ROLE)
            .remove(KEY_VENUE_ID)
            .remove(KEY_VENUE_NAME)
            .remove(KEY_VENUE_SLUG)
            .remove(KEY_VENUE_TIMEZONE)
            .remove(KEY_VENUE_MODE)
            .remove(KEY_RESERVATIONS_ENABLED)
            // 🔴 Sin esta línea la bandera de vales SOBREVIVE al cambio de venue: se cambia de
            // la cremería a una tienda retail y la tienda hereda el flujo de vales. Es
            // exactamente el modo de falla que la bandera existe para evitar.
            .remove(KEY_AREA_TICKETS_ENABLED)
            // El contexto de recuperación de vales NO se borra aquí. El
            // logout normal está protegido, pero una expiración de token puede
            // invalidar la sesión de forma reactiva. Conservar checkout/llaves
            // y vale pendiente permite recuperarlos tras volver a iniciar.
            .remove(KEY_PLAN_TIER)
            .remove(KEY_PLAN_EXEMPT)
            .remove(KEY_EXCHANGES_ENABLED)
            .remove(KEY_VENUE_PERMISSIONS)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TERMINAL_ID)
            .apply()
    }

    fun clearBiometricCredentials() {
        prefs.edit()
            .remove(KEY_BIOMETRIC_REFRESH_TOKEN)
            .remove(KEY_BIOMETRIC_USER_EMAIL)
            .remove(KEY_BIOMETRIC_USER_ID)
            .remove(KEY_BIOMETRIC_VENUE_ID)
            .apply()
        plainPrefs.edit()
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_BIOMETRIC_VENUES_LIST)
            .apply()
    }

    @Synchronized
    fun clearAll() {
        val solicitudes = giftCardRequestsJson
        val respaldo = respaldoSolicitudesConfirmado
        clearSession()
        clearBiometricCredentials()
        prefs.edit().clear().apply {
            solicitudes?.let { putString(KEY_GIFT_CARD_REQUESTS, it) }
            respaldo?.let { putString(KEY_GIFT_CARD_REQUESTS_ILEGIBLE, it) }
        }.commit()
        plainPrefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_GIFT_CARD_REQUESTS = "giftCardRequests"
        private const val KEY_GIFT_CARD_REQUESTS_ILEGIBLE = "giftCardRequestsIlegible"
        // Session
        private const val KEY_USER_ID = "userId"
        private const val KEY_USER_EMAIL = "userEmail"
        private const val KEY_USER_FIRST_NAME = "userFirstName"
        private const val KEY_USER_LAST_NAME = "userLastName"
        private const val KEY_USER_ROLE = "userRole"
        private const val KEY_VENUE_ID = "venueId"
        private const val KEY_VENUE_NAME = "venueName"
        private const val KEY_VENUE_SLUG = "venueSlug"
        private const val KEY_VENUE_TIMEZONE = "venueTimezone"
        private const val KEY_VENUE_MODE = "venueMode"
        /** La llave ÚNICA de antes del 25-sep y su contexto: sólo los lee [migrarLlaveUnica]. */
        private const val KEY_PENDING_CARD_CHARGE = "pendingCardChargeRequestId"
        private const val KEY_PENDING_CARD_CHARGE_CONTEXT = "pendingCardChargeContext"
        private const val KEY_PENDING_CARD_CHARGES = "pendingCardCharges"
        /** M-10: el último blob de la lista que no se pudo leer, tal cual, para conciliar. No lo borra `clearSession`. */
        private const val KEY_PENDING_CARD_CHARGES_ILEGIBLE = "pendingCardChargesIlegible"
        private const val KEY_RESERVATIONS_ENABLED = "reservationsEnabled"
        private const val KEY_AREA_TICKETS_ENABLED = "areaTicketsEnabled"
        private const val KEY_AREA_TICKET_CHECKOUT_ID = "areaTicketCheckoutId"
        private const val KEY_AREA_TICKET_CHECKOUT_VENUE_ID = "areaTicketCheckoutVenueId"
        private const val KEY_AREA_TICKET_CREATE_KEY = "areaTicketCreateKey"
        private const val KEY_AREA_TICKET_MATERIALIZE_KEY = "areaTicketMaterializeKey"
        private const val KEY_PENDING_AREA_TICKET_PRINT_CODE = "pendingAreaTicketPrintCode"
        private const val KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID = "pendingAreaTicketPrintVenueId"
        private const val KEY_PENDING_AREA_TICKET_PRINT_RECORDS = "pendingAreaTicketPrintRecords"
        private const val KEY_PLAN_TIER = "planTier"
        private const val KEY_PLAN_EXEMPT = "planExempt"
        private const val KEY_WASTE_PLAN_BLOCKED = "wastePlanBlockedVenues"
        private const val KEY_MANAGER_PIN_OVERRIDE_ENABLED = "managerPinOverrideEnabled"
        private const val KEY_EXCHANGES_ENABLED = "exchangesEnabled"
        private const val KEY_VENUE_PERMISSIONS = "venuePermissions"
        private const val KEY_ACCESS_TOKEN = "accessToken"
        private const val KEY_REFRESH_TOKEN = "refreshToken"
        // Biometric
        private const val KEY_BIOMETRIC_ENABLED = "isBiometricLoginEnabled"
        private const val KEY_BIOMETRIC_REFRESH_TOKEN = "biometricRefreshToken"
        private const val KEY_BIOMETRIC_USER_EMAIL = "biometricUserEmail"
        private const val KEY_BIOMETRIC_USER_ID = "biometricUserId"
        private const val KEY_BIOMETRIC_VENUE_ID = "biometricVenueId"
        private const val KEY_BIOMETRIC_VENUES_LIST = "biometricVenuesList"
        // Device
        private const val KEY_SERIAL_NUMBER = "serialNumber"
        private const val KEY_TERMINAL_ID = "terminalId"
        private const val KEY_USER_PIN = "userPin"
        private const val KEY_VENUES_LIST = "venuesList"
        private const val KEY_SELECTED_STAFF_ID_PREFIX = "selectedStaffId"
        private const val KEY_SELECTED_STAFF_NAME_PREFIX = "selectedStaffName"
        private const val KEY_CALENDAR_VIEW_PREFIX = "calendarView"
        private const val KEY_SHOW_CLASS_SESSIONS_PREFIX = "showClassSessions"
        private const val KEY_FLOOR_AREA_TAB_PREFIX = "floorAreaTab"
        private const val KEY_PROGRAMA_DE_SELLOS_PREFIX = "programaDeSellos"
    }

    private fun selectedStaffIdKey(currentVenueId: String? = venueId): String {
        return "${KEY_SELECTED_STAFF_ID_PREFIX}_${currentVenueId ?: "global"}"
    }

    private fun selectedStaffNameKey(currentVenueId: String? = venueId): String {
        return "${KEY_SELECTED_STAFF_NAME_PREFIX}_${currentVenueId ?: "global"}"
    }

    private fun calendarViewKey(currentVenueId: String? = venueId): String {
        return "${KEY_CALENDAR_VIEW_PREFIX}_${currentVenueId ?: "global"}"
    }

    private fun showClassSessionsKey(currentVenueId: String? = venueId): String {
        return "${KEY_SHOW_CLASS_SESSIONS_PREFIX}_${currentVenueId ?: "global"}"
    }
}

@kotlinx.serialization.Serializable
data class StoredVenue(
    val id: String,
    val name: String,
    val slug: String? = null,
    val logo: String? = null,
    val role: String? = null,
    val timezone: String? = null,
    val permissions: List<String> = emptyList(),
    // Organización dueña del venue (para agrupar en el picker de traslados CEDIS).
    // Default null: el JSON ya persistido de sesiones viejas decodifica sin él.
    val organizationId: String? = null,
    // Giro del negocio. Espejo EXACTO del campo `venueType` de iOS.
    // Default null: el JSON ya persistido de sesiones viejas decodifica sin él.
    val venueType: String? = null,
) {
    val displayRole: String
        get() = roleDisplayName(role) ?: "Staff"
}

/**
 * Rol del server → etiqueta en español. Única fuente: la usan el switcher de
 * sucursal y el bloque de identidad de "Más", para que el mismo rol no se llame
 * distinto según la pantalla.
 *
 * 🔴 Devuelve **null** cuando no hay rol guardado, y quien llama decide qué
 * decir. "No sé qué rol tienes" no es lo mismo que "eres Staff": esta pantalla
 * existe justo para contestar esa pregunta, así que inventar un rol ahí sería
 * peor que admitir el hueco. [StoredVenue.displayRole] conserva su "Staff"
 * histórico para no cambiar el switcher de sucursal.
 */
fun roleDisplayName(role: String?): String? {
    val normalized = role?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    return when (normalized.uppercase()) {
        "SUPERADMIN" -> "Super Admin"
        "OWNER" -> "Propietario"
        "ADMIN" -> "Administrador"
        "MANAGER" -> "Gerente"
        "CASHIER" -> "Cajero"
        "WAITER" -> "Mesero"
        "KITCHEN" -> "Cocina"
        "HOST" -> "Anfitrión"
        "VIEWER" -> "Observador"
        "STAFF" -> "Staff"
        else -> normalized
    }
}

/**
 * 🔴 D2 (vales-ciclo): la llave de emisión de vales, POR NEGOCIO (`pendingAreaTicketIssueKey_<venueId>`), como la deduplica el
 * SERVIDOR (negocio + llave). Todo va con `commit()`: `apply()` cambia la memoria y escribe después sin avisar si el disco
 * falla (ANDROID), y en WINDOWS un disco que falla deja lo de antes. Aparte de [SecureStorage] para probarla sin el cifrado.
 */
internal object LlaveDeEmisionDeVales {
    /** La de versiones anteriores: una sola para todo el aparato. Nunca se escribe ni se borra. */
    private const val GLOBAL = "pendingAreaTicketIssueKey"
    private fun clave(venueId: String) = "${GLOBAL}_$venueId"
    private fun yaNoHereda(venueId: String) = "${GLOBAL}_sinHeredar_$venueId"

    /**
     * La llave de [venueId]. Si nunca tuvo, la global de una versión anterior (Codex r7 #1): puede ser la de un vale cuya
     * respuesta se perdió antes de actualizar y no dice de qué negocio era, así que la ve CADA negocio hasta que ése la suelta
     * ([soltar]). Dársela a uno solo (al primero que emitía o al activo al arrancar) fallaba cuando era otro (r8 #1, r9 #1-#3).
     */
    fun leer(prefs: SharedPreferences, venueId: String): String? = prefs.getString(clave(venueId), null)
        ?: prefs.getString(GLOBAL, null)?.takeUnless { prefs.getBoolean(yaNoHereda(venueId), false) }

    /** `true` sólo si [llave] quedó en disco. */
    fun guardar(prefs: SharedPreferences, venueId: String, llave: String): Boolean =
        prefs.edit().putString(clave(venueId), llave).commit() && prefs.getString(clave(venueId), null) == llave

    /**
     * Con [siEs] (el SERVIDOR contestó por esa llave), sólo si la de [venueId] sigue siendo ésa: una confirmación tardía no suelta
     * la de otra emisión (r7 #4), y lo que se mandó con ella se borra. Sin [siEs] («Vaciar»), D5: lo pedido sin respuesta NO se
     * tira en silencio; pasa a «sin confirmar» y sólo «Descartar» lo olvida (si no, el vale que sí salió quedaba ISSUED).
     */
    fun soltar(prefs: SharedPreferences, venueId: String, siEs: String? = null) {
        if (siEs != null && leer(prefs, venueId) != siEs) return
        if (siEs == null) apartarSinConfirmar(prefs, venueId)
        prefs.edit().remove(clave(venueId)).remove(intentos(venueId)).putBoolean(yaNoHereda(venueId), true).commit()
    }

    // ── 🔴 D5 (vales-ciclo): el vale a medias. Lo que se mandó vive y muere con su llave. ──────────────────────────────────

    /** Tope de contenidos distintos con una misma llave: un sexto no se podría recuperar, así que no se manda. */
    const val TOPE_DE_INTENTOS = 5
    private fun intentos(venueId: String) = "pendingAreaTicketIssueAttempts_$venueId"
    private fun sinConfirmar(venueId: String) = "valeSinConfirmar_$venueId"
    private val json = Json { ignoreUnknownKeys = true }
    private val listaDeIntentos = ListSerializer(IntentoDeVale.serializer())

    enum class Guardado { SI, NO, TOPE }

    private fun leerIntentos(prefs: SharedPreferences, venueId: String): List<IntentoDeVale> =
        prefs.getString(intentos(venueId), null)?.let { runCatching { json.decodeFromString(listaDeIntentos, it) }.getOrNull() }
            ?: emptyList()

    /**
     * Llave + [intento] en UN `commit()` y relectura, ANTES del POST. Un contenido distinto con la misma llave se AGREGA: nunca se
     * pisa el que pudo crear el vale (Codex r1 #3). Si la llave guardada es otra (o es la global de una versión anterior), los
     * intentos empiezan de cero.
     */
    fun guardar(prefs: SharedPreferences, venueId: String, llave: String, intento: IntentoDeVale): Guardado {
        val previos = if (prefs.getString(clave(venueId), null) == llave) leerIntentos(prefs, venueId) else emptyList()
        val todos = if (previos.any { it.mismoQue(intento) }) previos else previos + intento
        if (todos.size > TOPE_DE_INTENTOS) return Guardado.TOPE
        val texto = json.encodeToString(listaDeIntentos, todos)
        val ok = prefs.edit().putString(clave(venueId), llave).putString(intentos(venueId), texto).commit() &&
            prefs.getString(clave(venueId), null) == llave && prefs.getString(intentos(venueId), null) == texto
        return if (ok) Guardado.SI else Guardado.NO
    }

    /**
     * La PRIMERA vez que este proceso usa [venueId]: lo que se pidió en la vez anterior de la app y nunca se confirmó pasa a «sin
     * confirmar», con SU llave, y el negocio queda sin llave viva (lo siguiente sale con otra). `true` si hay uno.
     */
    fun alArrancar(prefs: SharedPreferences, venueId: String): Boolean {
        if (apartarSinConfirmar(prefs, venueId)) {
            prefs.edit().remove(clave(venueId)).remove(intentos(venueId)).putBoolean(yaNoHereda(venueId), true).commit()
        }
        return valeSinConfirmar(prefs, venueId) != null
    }

    /** Lo pedido con la llave viva de [venueId] y sin respuesta pasa a «sin confirmar» con SU llave. `true` si había. */
    private fun apartarSinConfirmar(prefs: SharedPreferences, venueId: String): Boolean {
        val llave = prefs.getString(clave(venueId), null)
        val pedidos = leerIntentos(prefs, venueId)
        if (llave == null || pedidos.isEmpty()) return false
        // ponytail: ya hay uno sin confirmar ⇒ no se pisa; mientras exista, «Emitir vale» no deja pedir otro (④).
        if (prefs.getString(sinConfirmar(venueId), null) == null) {
            prefs.edit()
                .putString(sinConfirmar(venueId), json.encodeToString(ValeSinConfirmar.serializer(), ValeSinConfirmar(llave, pedidos)))
                .commit()
        }
        return true
    }

    /** D5 (Codex r6): ¿hay un vale de esta mercancía pedido sin respuesta (en curso o sin confirmar) en [venueId]? */
    fun mercanciaEnDuda(prefs: SharedPreferences, venueId: String, lineas: List<IssueAreaTicketLineRequest>): Boolean =
        (leerIntentos(prefs, venueId) + (valeSinConfirmar(prefs, venueId)?.intentos ?: emptyList())).any { it.mismaMercanciaQue(lineas) }

    fun valeSinConfirmar(prefs: SharedPreferences, venueId: String): ValeSinConfirmar? =
        prefs.getString(sinConfirmar(venueId), null)
            ?.let { runCatching { json.decodeFromString(ValeSinConfirmar.serializer(), it) }.getOrNull() }
            ?.takeIf { it.intentos.isNotEmpty() }

    /**
     * «Recuperar» confirmó el vale de [llave]: en UN `commit()` queda su papel pendiente (D12, claves [clavePapel] y
     * [claveNegocioDelPapel]) y se olvida el vale sin confirmar (Codex r3 #1: con dos escrituras sueltas, en WINDOWS una podía
     * fallar y perderse el papel, o quedar el registro y salir el original dos veces). `false` = no quedó; no se imprime.
     */
    fun recuperado(
        prefs: SharedPreferences, venueId: String, llave: String, codigo: String, clavePapel: String, claveNegocioDelPapel: String,
    ): Boolean {
        if (valeSinConfirmar(prefs, venueId)?.llave != llave) return false
        val antes = listOf(clavePapel, claveNegocioDelPapel, sinConfirmar(venueId)).associateWith { prefs.getString(it, null) }
        val ok = prefs.edit().putString(clavePapel, codigo).putString(claveNegocioDelPapel, venueId).remove(sinConfirmar(venueId))
            .commit() && prefs.getString(clavePapel, null) == codigo && prefs.getString(sinConfirmar(venueId), null) == null
        if (!ok) {
            // Codex r4 #3: las preferencias de ANDROID cambian la memoria ANTES de escribir; si el disco falló, la memoria
            // decía que ya no había vale sin confirmar (el aviso se iba y «Emitir» salía con otra llave). Se regresa.
            prefs.edit().apply { antes.forEach { (k, v) -> if (v == null) remove(k) else putString(k, v) } }.commit()
        }
        return ok
    }

    /** Recuperado o descartado: se olvida, sólo si sigue siendo el de [llave]. */
    fun olvidarSinConfirmar(prefs: SharedPreferences, venueId: String, llave: String) {
        if (valeSinConfirmar(prefs, venueId)?.llave != llave) return
        prefs.edit().remove(sinConfirmar(venueId)).commit()
    }
}
