package com.avoqado.pos.reservations.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.domain.CreateReservationDraft
import com.avoqado.pos.reservations.domain.CreateStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class CreateReservationViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _draft = MutableStateFlow(CreateReservationDraft())
    val draft: StateFlow<CreateReservationDraft> = _draft.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _result = MutableStateFlow<Result<Reservation>?>(null)
    val result: StateFlow<Result<Reservation>?> = _result.asStateFlow()

    fun update(transform: (CreateReservationDraft) -> CreateReservationDraft) {
        _draft.update(transform)
    }

    fun next() = _draft.update { d -> d.copy(step = nextStepOf(d.step)) }
    fun back() = _draft.update { d -> d.copy(step = prevStepOf(d.step)) }
    fun goTo(step: CreateStep) = _draft.update { it.copy(step = step) }

    fun submit() {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            val r = repository.createReservation(_draft.value.toRequest(zone))
            _isSubmitting.value = false
            _result.value = r.map { it ?: error("Empty reservation") }
        }
    }

    private fun nextStepOf(s: CreateStep): CreateStep = when (s) {
        CreateStep.CUSTOMER -> CreateStep.SERVICE
        CreateStep.SERVICE -> CreateStep.DATETIME
        CreateStep.DATETIME -> CreateStep.DETAILS
        CreateStep.DETAILS -> CreateStep.CONFIRM
        CreateStep.CONFIRM -> CreateStep.CONFIRM
    }

    private fun prevStepOf(s: CreateStep): CreateStep = when (s) {
        CreateStep.CUSTOMER -> CreateStep.CUSTOMER
        CreateStep.SERVICE -> CreateStep.CUSTOMER
        CreateStep.DATETIME -> CreateStep.SERVICE
        CreateStep.DETAILS -> CreateStep.DATETIME
        CreateStep.CONFIRM -> CreateStep.DETAILS
    }
}
