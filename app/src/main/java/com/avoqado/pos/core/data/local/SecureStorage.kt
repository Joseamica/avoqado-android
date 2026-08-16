package com.avoqado.pos.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
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
     * `requestId` de un cobro con TARJETA cuyo desenlace NO consta.
     *
     * 🔴 Vive en disco a propósito: es lo único que impide un segundo cargo cuando la app
     * muere, el cajero cambia de pestaña, o se va a Transacciones a ver si el pago entró —
     * que es justo lo que hace la gente frente a la pantalla "Cobro sin confirmar". En RAM
     * esa ceremonia se evapora y el siguiente "Cobrar" arranca limpio, sin advertencia.
     *
     * Se escribe al enviar el cobro y se borra SÓLO cuando el desenlace consta (cobró o no
     * cobró), o cuando el cajero decide explícitamente cobrar de nuevo asumiendo el riesgo.
     * **NO se limpia en `clearSession`**: un cobro sin confirmar no deja de existir porque
     * alguien cierre sesión o cambie de venue.
     */
    var pendingCardChargeRequestId: String?
        get() = prefs.getString(KEY_PENDING_CARD_CHARGE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_PENDING_CARD_CHARGE) else putString(KEY_PENDING_CARD_CHARGE, value)
            }.apply()
        }

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

    var pendingAreaTicketIssueKey: String?
        get() = prefs.getString(KEY_PENDING_AREA_TICKET_ISSUE_KEY, null)
        set(value) = prefs.edit().putString(KEY_PENDING_AREA_TICKET_ISSUE_KEY, value).apply()

    var pendingAreaTicketPrintCode: String?
        get() = prefs.getString(KEY_PENDING_AREA_TICKET_PRINT_CODE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_AREA_TICKET_PRINT_CODE, value).apply()

    var pendingAreaTicketPrintVenueId: String?
        get() = prefs.getString(KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID, value).apply()

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
        get() = prefs.getString(KEY_PLAN_TIER, null)
        set(value) = prefs.edit().putString(KEY_PLAN_TIER, value).apply()

    /** Exempt venues (grandfathered legacy / demo) bypass all plan gates. */
    var planExempt: Boolean
        get() = prefs.getBoolean(KEY_PLAN_EXEMPT, false)
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

    /**
     * Permissions for the active venue (e.g. "reservations:create", "menu:read").
     * Server resolves these per-venue from role + custom overrides; client mirrors them
     * to gate UI affordances. See server lib/permissions.ts DEFAULT_PERMISSIONS.
     */
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

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    // MARK: - Biometric Data (NOT cleared on logout)

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

    fun updateTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
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

    fun clearAll() {
        clearSession()
        clearBiometricCredentials()
        prefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }

    companion object {
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
        private const val KEY_PENDING_CARD_CHARGE = "pendingCardChargeRequestId"
        private const val KEY_RESERVATIONS_ENABLED = "reservationsEnabled"
        private const val KEY_AREA_TICKETS_ENABLED = "areaTicketsEnabled"
        private const val KEY_AREA_TICKET_CHECKOUT_ID = "areaTicketCheckoutId"
        private const val KEY_AREA_TICKET_CHECKOUT_VENUE_ID = "areaTicketCheckoutVenueId"
        private const val KEY_AREA_TICKET_CREATE_KEY = "areaTicketCreateKey"
        private const val KEY_AREA_TICKET_MATERIALIZE_KEY = "areaTicketMaterializeKey"
        private const val KEY_PENDING_AREA_TICKET_ISSUE_KEY = "pendingAreaTicketIssueKey"
        private const val KEY_PENDING_AREA_TICKET_PRINT_CODE = "pendingAreaTicketPrintCode"
        private const val KEY_PENDING_AREA_TICKET_PRINT_VENUE_ID = "pendingAreaTicketPrintVenueId"
        private const val KEY_PLAN_TIER = "planTier"
        private const val KEY_PLAN_EXEMPT = "planExempt"
        private const val KEY_MANAGER_PIN_OVERRIDE_ENABLED = "managerPinOverrideEnabled"
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
) {
    val displayRole: String
        get() = when (role?.uppercase()) {
            "SUPERADMIN" -> "Super Admin"
            "OWNER" -> "Propietario"
            "ADMIN" -> "Administrador"
            "MANAGER" -> "Gerente"
            "CASHIER" -> "Cajero"
            "WAITER" -> "Mesero"
            "KITCHEN" -> "Cocina"
            "HOST" -> "Anfitrion"
            "VIEWER" -> "Observador"
            "STAFF" -> "Staff"
            else -> role ?: "Staff"
        }
}
