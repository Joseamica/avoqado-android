package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class ReservationDetailViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val capabilityProvider: Provider<ReservationsCapability>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val reservationId: String = checkNotNull(savedStateHandle["reservationId"])

    private val _state = MutableStateFlow(ReservationDetailUiState(capability = capabilityProvider.get()))
    val state: StateFlow<ReservationDetailUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            repository.changes.collect { silentRefresh() }
        }
    }

    fun reload() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = repository.fetchOne(reservationId)
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, reservation = r.getOrNull())
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando reserva")
            }
        }
    }

    private fun silentRefresh() {
        viewModelScope.launch {
            val r = repository.fetchOne(reservationId)
            if (r.isSuccess) {
                _state.update { it.copy(reservation = r.getOrNull()) }
            }
        }
    }

    fun runAction(action: ReservationAction, payload: ReservationRepository.ActionPayload? = null) {
        if (!_state.value.isAllowed(action)) return
        // Global in-flight lock: PENDING allows CONFIRM+CHECK_IN+NO_SHOW+CANCEL
        // simultaneously; without this a 2nd tap fired two concurrent mutations
        // on the same reservation (e.g. confirm + no-show racing).
        if (_state.value.pendingAction != null) return
        val before = _state.value.reservation
        _state.update { it.copy(pendingAction = action, error = null, justCompletedAction = null) }
        viewModelScope.launch {
            val r = repository.runAction(reservationId, action, payload)
            _state.update { current ->
                if (r.isSuccess) {
                    val updated = r.getOrNull() ?: before
                    current.copy(reservation = updated, pendingAction = null, justCompletedAction = action)
                } else {
                    current.copy(reservation = before, pendingAction = null, error = r.exceptionOrNull()?.message ?: "Error")
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeJustCompleted() = _state.update { it.copy(justCompletedAction = null) }
}
