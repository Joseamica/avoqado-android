package com.avoqado.pos.reservations.presentation.classsessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.reservations.data.ClassSessionRepository
import com.avoqado.pos.reservations.data.model.AddAttendeeRequest
import com.avoqado.pos.reservations.data.model.ClassSession
import com.avoqado.pos.reservations.data.model.UpdateClassSessionRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ClassSessionDetailViewModel @Inject constructor(
    private val repository: ClassSessionRepository,
    private val customersRepository: CustomersRepository,
    private val staffRepository: StaffRepository,
    private val secureStorage: SecureStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _session = MutableStateFlow<ClassSession?>(null)
    val session: StateFlow<ClassSession?> = _session.asStateFlow()

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    private val _staff = MutableStateFlow<List<StaffMember>>(emptyList())
    val staff: StateFlow<List<StaffMember>> = _staff.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _message = MutableStateFlow<Result<String>?>(null)
    val message: StateFlow<Result<String>?> = _message.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { customersRepository.fetchCustomers().onSuccess { _customers.value = it } }
        viewModelScope.launch { staffRepository.getActiveStaff().onSuccess { _staff.value = it } }
        viewModelScope.launch { repository.changes.collect { refresh() } }
    }

    fun refresh() {
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            repository.fetchOne(sessionId).onSuccess { _session.value = it }
        }
    }

    fun addCustomer(customer: Customer) {
        addAttendee(
            AddAttendeeRequest(
                customerId = customer.id,
                guestName = customer.fullName,
                guestPhone = customer.phone,
                guestEmail = customer.email,
            ),
            "¡Asistente agregado!",
        )
    }

    fun addGuest(name: String, phone: String?, email: String?) {
        addAttendee(
            AddAttendeeRequest(
                guestName = name,
                guestPhone = phone?.takeIf { it.isNotBlank() },
                guestEmail = email?.takeIf { it.isNotBlank() },
            ),
            "¡Asistente agregado!",
        )
    }

    fun removeAttendee(reservationId: String) {
        submit("¡Asistente eliminado!") { repository.removeAttendee(sessionId, reservationId) }
    }

    fun cancel() {
        submit("Sesión cancelada") { repository.cancel(sessionId) }
    }

    fun update(capacity: Int, assignedStaffId: String?, internalNotes: String?) {
        submit("¡Clase actualizada!") {
            repository.update(
                sessionId,
                UpdateClassSessionRequest(
                    capacity = capacity,
                    assignedStaffId = assignedStaffId,
                    internalNotes = internalNotes?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    private fun addAttendee(request: AddAttendeeRequest, success: String) {
        val s = _session.value
        if (s != null && s.enrolled >= s.capacity) {
            _message.value = Result.failure(Exception("Sesión llena (${s.enrolled}/${s.capacity})"))
            return
        }
        submit(success) { repository.addAttendee(sessionId, request) }
    }

    private fun <T> submit(success: String, block: suspend () -> Result<T>) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = block().map { success }
            _message.value = result
            if (result.isSuccess) refresh()
            _isSubmitting.value = false
        }
    }
}
