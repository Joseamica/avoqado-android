package com.avoqado.pos.reservations.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
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
 * "Activar reservas" is a purely local UI-surfacing toggle in v2.2.0.
 *
 * Why no server call?
 * - The server's reservation endpoints already gate access via `checkPermission('reservations:read|create|...')`.
 *   Whether the staff can use them is decided by their JWT permissions, not by a Venue-level flag set here.
 * - VenueFeature/Feature is for billing (Stripe-driven, superadmin-managed). When paywall arrives
 *   in a future release, the source of truth shifts to a remote `featureFlags.reservations` field
 *   that this client simply reads instead of writes.
 *
 * Effect today: the device persists `reservationsEnabled = true` so the bottom-nav can surface
 * the Calendar tab. The fake delay gives the user the perception of work being done.
 */
@HiltViewModel
class ActivateReservationsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivateReservationsUiState())
    val state: StateFlow<ActivateReservationsUiState> = _state.asStateFlow()

    fun activate() {
        if (_state.value.isActivating || _state.value.didSucceed) return
        _state.value = _state.value.copy(isActivating = true, error = null)
        viewModelScope.launch {
            delay(400)
            secureStorage.reservationsEnabled = true
            secureStorage.venueMode = VenueMode.RESERVATIONS.storageValue
            _state.value = ActivateReservationsUiState(didSucceed = true)
        }
    }
}
