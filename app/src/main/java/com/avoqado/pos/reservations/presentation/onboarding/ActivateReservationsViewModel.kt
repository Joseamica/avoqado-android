package com.avoqado.pos.reservations.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationApi
import com.avoqado.pos.reservations.domain.VenueMode
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
class ActivateReservationsViewModel @Inject constructor(
    private val api: ReservationApi,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivateReservationsUiState())
    val state: StateFlow<ActivateReservationsUiState> = _state.asStateFlow()

    fun activate() {
        if (_state.value.isActivating || _state.value.didSucceed) return
        _state.value = _state.value.copy(isActivating = true, error = null)
        viewModelScope.launch {
            val r = api.enableForVenue()
            _state.value = if (r.isSuccess) {
                secureStorage.reservationsEnabled = true
                secureStorage.venueMode = VenueMode.RESERVATIONS.storageValue
                ActivateReservationsUiState(didSucceed = true)
            } else {
                ActivateReservationsUiState(error = r.exceptionOrNull()?.message ?: "Error activando reservas")
            }
        }
    }
}
