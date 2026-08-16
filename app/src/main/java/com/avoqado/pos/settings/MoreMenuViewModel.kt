package com.avoqado.pos.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.addons.domain.AddonsManager
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.local.roleDisplayName
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.settings.domain.PosModeManager
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MoreMenuViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
    val timeEntryRepository: TimeEntryRepository,
    val printerService: PrinterService,
    private val roleManager: RoleManager,
    private val planManager: PlanManager,
    val posModeManager: PosModeManager,
    val addonsManager: AddonsManager,
    val activeCartState: ActiveCartState,
    val kioskManager: com.avoqado.pos.settings.domain.KioskManager,
    val customerDisplayPrefs: com.avoqado.pos.customerdisplay.CustomerDisplayPrefs,
    val customerDisplayState: com.avoqado.pos.customerdisplay.CustomerDisplayState,
    val displayModePrefs: com.avoqado.pos.customerdisplay.DisplayModePrefs,
    val cashierDisplayGuard: com.avoqado.pos.customerdisplay.CashierDisplayGuard,
    val venueSwitchState: com.avoqado.pos.settings.domain.VenueSwitchState,
    private val paymentSyncService: PaymentSyncService,
    private val syncOutbox: SyncOutbox,
) : ViewModel() {

    private val _venueName = MutableStateFlow(secureStorage.venueName ?: "Sin establecimiento")
    val venueName: StateFlow<String> = _venueName.asStateFlow()

    private val _venueRole = MutableStateFlow(secureStorage.userRole)
    val venueRole: StateFlow<String?> = _venueRole.asStateFlow()

    // MARK: - Identidad de la sesión
    //
    // Quién inició sesión y con qué rol. El rol va como flujo y el resto como
    // getters a propósito: el rol es POR SUCURSAL — la misma persona puede ser
    // Gerente aquí y Cajero allá, así que tiene que repintarse al cambiar de
    // establecimiento. El nombre y el correo no cambian dentro de una sesión.

    /** Nombre completo del usuario, o null si la sesión no lo guardó. */
    val userDisplayName: String?
        get() = listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName)
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotBlank() }

    /** Correo con el que se inició sesión, o null si no consta. */
    val userEmail: String?
        get() = secureStorage.userEmail?.takeIf { it.isNotBlank() }

    /** Rol legible en español del establecimiento ACTIVO. Null = no consta. */
    val roleLabel: StateFlow<String?> = _venueRole
        .map { roleDisplayName(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            roleDisplayName(secureStorage.userRole),
        )

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private val _sessionGuardMessage = MutableStateFlow<String?>(null)
    val sessionGuardMessage: StateFlow<String?> = _sessionGuardMessage.asStateFlow()

    val currentVenueId: String?
        get() = secureStorage.venueId

    val venuesList: List<StoredVenue>
        get() = secureStorage.venuesList

    val hasMultipleVenues: Boolean
        get() = secureStorage.venuesList.size > 1

    val canCreateProducts: Boolean
        get() = roleManager.canCreateProducts

    val canAccessReports: Boolean
        get() = roleManager.canAccessReports

    val canManageCashDrawer: Boolean
        get() = roleManager.canManageCashDrawer

    val canAccessKDS: Boolean
        get() = roleManager.canAccessKDS

    /**
     * Effective reservations availability: local toggle AND plan gate, so a
     * stale local toggle can't surface reservation entries (waitlist, mode
     * card) on a plan without RESERVATIONS. Fail-open when plan is unknown.
     */
    val reservationsEnabled: Boolean
        get() = secureStorage.reservationsEnabled && planManager.hasFeature("RESERVATIONS")

    /** True → show the Pro tier badge on the "Activar reservas" entry (visible teaser). */
    val reservationsRequireUpgrade: Boolean
        get() = planManager.requiresUpgrade("RESERVATIONS")

    /** Tier label required for reservations ("Pro") for badges/upsell copy. */
    val reservationsTierLabel: String
        get() = planManager.requiredTierLabel("RESERVATIONS") ?: "Pro"



    fun switchVenue(venue: StoredVenue, onSwitched: () -> Unit = {}) {
        if (venue.id == secureStorage.venueId) return
        viewModelScope.launch {
            val currentVenueId = secureStorage.venueId
            val blocking = paymentSyncService.blockingWorkCount() +
                (currentVenueId?.let { syncOutbox.blockingWorkCount(it) } ?: 0) +
                secureStorage.areaTicketRecoveryCount(currentVenueId)
            if (blocking > 0) {
                _sessionGuardMessage.value = if (blocking == 1) {
                    "Hay 1 operación offline pendiente o en conciliación. Resuélvela antes de cambiar de establecimiento."
                } else {
                    "Hay $blocking operaciones offline pendientes o en conciliación. Resuélvelas antes de cambiar de establecimiento."
                }
                return@launch
            }

            _isSwitching.value = true
            // Loader GLOBAL (sobrevive al rebuild del NavHost que detona el cambio).
            venueSwitchState.begin("Cambiando a ${venue.name}…")
            try {
                authRepository.switchVenue(venue)

                _venueName.value = venue.name
                _venueRole.value = venue.role

                // Reload per-venue settings for the new venue
                posModeManager.reloadForCurrentVenue()
                addonsManager.reloadForCurrentVenue()

                Log.d("🔄", "✅ Venue switch complete: ${venue.name}")
                // Notify so AppState.visibleTabs recomputes against the new role.
                onSwitched()
            } catch (e: Exception) {
                Log.e("🔄", "❌ Venue switch error: ${e.message}")
            } finally {
                _isSwitching.value = false
                venueSwitchState.end()
            }
        }
    }

    fun clearSessionGuard() {
        _sessionGuardMessage.value = null
    }
}
