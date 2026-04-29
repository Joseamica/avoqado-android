package com.avoqado.pos.reservations.presentation.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.domain.CreateReservationDraft
import com.avoqado.pos.reservations.domain.CreateStep
import com.avoqado.pos.tables.data.Table
import com.avoqado.pos.tables.data.TablesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class CreateReservationViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val customersRepository: CustomersRepository,
    private val productsRepository: ProductsRepository,
    private val tablesRepository: TablesRepository,
    private val staffRepository: StaffRepository,
    private val secureStorage: SecureStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _draft = MutableStateFlow(CreateReservationDraft())
    val draft: StateFlow<CreateReservationDraft> = _draft.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _result = MutableStateFlow<Result<Reservation>?>(null)
    val result: StateFlow<Result<Reservation>?> = _result.asStateFlow()

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    private val _isLoadingCustomers = MutableStateFlow(false)
    val isLoadingCustomers: StateFlow<Boolean> = _isLoadingCustomers.asStateFlow()

    private val _customerError = MutableStateFlow<String?>(null)
    val customerError: StateFlow<String?> = _customerError.asStateFlow()

    private val _isCreatingCustomer = MutableStateFlow(false)
    val isCreatingCustomer: StateFlow<Boolean> = _isCreatingCustomer.asStateFlow()

    val products: StateFlow<List<Product>> = productsRepository.products

    private val _tables = MutableStateFlow<List<Table>>(emptyList())
    val tables: StateFlow<List<Table>> = _tables.asStateFlow()

    private val _staff = MutableStateFlow<List<StaffMember>>(emptyList())
    val staff: StateFlow<List<StaffMember>> = _staff.asStateFlow()

    init {
        seedFromNavArgs(savedStateHandle)
        loadCustomers()
        viewModelScope.launch { productsRepository.fetchProducts() }
        viewModelScope.launch {
            tablesRepository.fetchTables().onSuccess { _tables.value = it }
        }
        viewModelScope.launch {
            staffRepository.getActiveStaff().onSuccess { _staff.value = it }
        }
    }

    private fun seedFromNavArgs(handle: SavedStateHandle) {
        val date = handle.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = handle.get<String>("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val isWalkIn = handle.get<String>("walkin")?.equals("true", ignoreCase = true) ?: false

        if (date == null && time == null && !isWalkIn) return

        _draft.update { d ->
            d.copy(
                date = date ?: d.date,
                time = if (isWalkIn) nextQuarterHour(LocalTime.now(zone)) else (time ?: d.time),
                isGuest = if (isWalkIn) true else d.isGuest,
                guestName = if (isWalkIn) "Walk-in" else d.guestName,
                channel = if (isWalkIn) ReservationChannel.WALK_IN else d.channel,
                step = if (isWalkIn) CreateStep.SERVICE else d.step,
            )
        }
    }

    private fun nextQuarterHour(t: LocalTime): LocalTime {
        val minute = t.minute
        val rounded = ((minute / 15) + 1) * 15
        return if (rounded >= 60) t.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        else t.withMinute(rounded).withSecond(0).withNano(0)
    }

    fun update(transform: (CreateReservationDraft) -> CreateReservationDraft) {
        _draft.update(transform)
    }

    fun next() = _draft.update { d -> d.copy(step = nextStepOf(d.step)) }
    fun back() = _draft.update { d -> d.copy(step = prevStepOf(d.step)) }
    fun goTo(step: CreateStep) = _draft.update { it.copy(step = step) }

    private fun loadCustomers() {
        viewModelScope.launch {
            _isLoadingCustomers.value = true
            customersRepository.fetchCustomers()
                .onSuccess { _customers.value = it }
            _isLoadingCustomers.value = false
        }
    }

    fun createCustomer(
        request: CreateCustomerRequest,
        onSuccess: (Customer) -> Unit = {},
    ) {
        if (_isCreatingCustomer.value) return
        _customerError.value = null
        viewModelScope.launch {
            _isCreatingCustomer.value = true
            customersRepository.createCustomer(request)
                .onSuccess { customer ->
                    _customers.value = listOf(customer) + _customers.value
                    update { d ->
                        d.copy(
                            customerId = customer.id,
                            customerName = customer.fullName,
                            isGuest = false,
                            guestName = null,
                            guestPhone = null,
                            guestEmail = null,
                        )
                    }
                    onSuccess(customer)
                }
                .onFailure {
                    _customerError.value = it.message ?: "Error al crear cliente"
                }
            _isCreatingCustomer.value = false
        }
    }

    fun clearCustomerError() {
        _customerError.value = null
    }

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
