package com.avoqado.pos.reservations.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.reservations.domain.VenueMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivateReservationsUiState(
    val isActivating: Boolean = false,
    val didSucceed: Boolean = false,
    val error: String? = null,
)

/**
 * "Activar reservas" surfaces the Calendar tab via a local toggle, but the
 * toggle is now gated by the venue's PLAN: reservations are a Pro feature.
 *
 * - Plan allows RESERVATIONS (or plan unknown/exempt → fail-open): the screen
 *   shows the activation flow and persists `reservationsEnabled = true` locally
 *   so the bottom-nav surfaces the Calendar tab.
 * - Plan lacks RESERVATIONS: the screen shows the Pro upsell teaser instead of
 *   the toggle, and [activate] refuses to flip the local flag — so the gate
 *   can't be bypassed even by stale UI. The tab computation also ANDs the plan
 *   check so a stale local toggle can't expose the tab.
 *
 * Server-side: the reservation endpoints gate access via staff permissions;
 * plan enforcement (403 PLAN_REQUIRED) lands in a later phase. This client
 * reads `plan` from the venue-settings response (PlanManager).
 */
@HiltViewModel
class ActivateReservationsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val planManager: PlanManager,
    private val posModeManager: com.avoqado.pos.settings.domain.PosModeManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivateReservationsUiState())
    val state: StateFlow<ActivateReservationsUiState> = _state.asStateFlow()

    /** Pro gate. Fail-open: unknown plan or exempt venue behaves as before. */
    val hasReservationsFeature: Boolean
        get() = planManager.hasFeature(RESERVATIONS_FEATURE_CODE)

    /** Label of the plan required for reservations (for the upsell copy). */
    val requiredTierLabel: String
        get() = planManager.requiredTierLabel(RESERVATIONS_FEATURE_CODE) ?: "Pro"

    fun activate() {
        if (_state.value.isActivating || _state.value.didSucceed) return
        if (!hasReservationsFeature) {
            // Plan gate: never flip the local toggle on a plan without
            // RESERVATIONS (the screen shows the upsell instead of the CTA,
            // this guard covers any stale UI path).
            _state.value = _state.value.copy(
                error = "Las reservas son parte del plan $requiredTierLabel. " +
                    "Actívalo desde tu dashboard web (Configuración → Plan).",
            )
            return
        }
        _state.value = _state.value.copy(isActivating = true, error = null)
        viewModelScope.launch {
            delay(400)
            secureStorage.reservationsEnabled = true
            // 🔴 Un restaurante que activa citas SIGUE siendo restaurante.
            //
            // Antes esto movía el dispositivo a modo Reservas SIEMPRE, y como
            // `MainTabsPolicy` sólo muestra Mesas en modo RESTAURANT, el plano
            // DESAPARECÍA de la barra por tomar reservas. Medido el 2026-08-04.
            // La decisión del founder es que CONVIVAN.
            //
            // El Calendario ya no depende del modo, así que sólo se mueve al
            // dispositivo cuando venía de Retail —una estética que empieza a
            // tomar citas sí quiere la agenda al frente.
            if (posModeManager.currentMode.value != com.avoqado.pos.settings.domain.PosMode.RESTAURANT) {
                posModeManager.switchMode(com.avoqado.pos.settings.domain.PosMode.RESERVATIONS)
            }
            _state.value = ActivateReservationsUiState(didSucceed = true)
        }
    }

    companion object {
        private const val RESERVATIONS_FEATURE_CODE = "RESERVATIONS"
    }
}
