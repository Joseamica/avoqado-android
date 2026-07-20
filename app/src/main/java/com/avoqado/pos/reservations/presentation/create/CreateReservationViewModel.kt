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
import com.avoqado.pos.reservations.data.WaitlistRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.domain.CreateReservationDraft
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
    private val waitlistRepository: WaitlistRepository,
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

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private var editingId: String? = null
    private var promoteWaitlistId: String? = null

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
        val editId = handle.get<String>("editingId")

        if (editId != null) {
            editingId = editId
            _isEditing.value = true
            viewModelScope.launch {
                repository.fetchOne(editId).onSuccess { r ->
                    _draft.update { d -> seedFromReservation(d, r) }
                }
            }
            return
        }

        val promoteId = handle.get<String>("promoteWaitlistId")
        if (promoteId != null) {
            promoteWaitlistId = promoteId
            val prefillCustomerId = handle.get<String>("prefillCustomerId")
            val prefillGuestName = handle.get<String>("prefillGuestName")
            val prefillPartySize = handle.get<String>("prefillPartySize")?.toIntOrNull()
            val prefillStart = handle.get<String>("prefillStart")?.let {
                runCatching { java.time.Instant.parse(it).atZone(zone) }.getOrNull()
            }
            _draft.update { d ->
                d.copy(
                    customerId = prefillCustomerId,
                    customerName = if (prefillCustomerId == null) prefillGuestName else null,
                    isGuest = prefillCustomerId == null,
                    guestName = if (prefillCustomerId == null) prefillGuestName else null,
                    partySize = prefillPartySize ?: d.partySize,
                    date = prefillStart?.toLocalDate() ?: d.date,
                    time = prefillStart?.toLocalTime() ?: d.time,
                    channel = ReservationChannel.WALK_IN,
                )
            }
            return
        }

        if (date == null && time == null && !isWalkIn) return

        _draft.update { d ->
            d.copy(
                date = date ?: d.date,
                time = time ?: if (isWalkIn) nextQuarterHour(LocalTime.now(zone)) else d.time,
                isGuest = if (isWalkIn) true else d.isGuest,
                guestName = if (isWalkIn) "Walk-in" else d.guestName,
                channel = if (isWalkIn) ReservationChannel.WALK_IN else d.channel,
            )
        }
    }

    private fun seedFromReservation(d: CreateReservationDraft, r: Reservation): CreateReservationDraft {
        val startInstant = java.time.Instant.parse(r.startsAt)
        val zoned = startInstant.atZone(zone)
        return d.copy(
            customerId = r.customerId,
            customerName = r.customer?.fullName,
            guestName = r.guestName,
            guestPhone = r.guestPhone,
            guestEmail = r.guestEmail,
            isGuest = r.customerId == null && !r.guestName.isNullOrBlank(),
            productId = r.productId,
            productName = r.product?.name,
            durationMinutes = r.duration,
            date = zoned.toLocalDate(),
            time = zoned.toLocalTime(),
            partySize = r.partySize,
            tableId = r.tableId,
            tableNumber = r.table?.number,
            assignedStaffId = r.assignedStaffId,
            assignedStaffName = r.assignedStaff?.displayName,
            specialRequests = r.specialRequests,
            internalNotes = r.internalNotes,
        )
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
        // 🔴 Cada intento arranca LIMPIO. El gate del final (que protege el
        // fallo de promoción de waitlist de ESTE intento) veía el failure
        // ATORADO del intento anterior y bloqueaba todos los updates: del
        // segundo Crear en adelante no salía ni error ni éxito — la pantalla
        // parecía trabada aunque el POST sí corriera (y un éxito silencioso
        // habría creado la reserva sin cerrar la pantalla).
        _result.value = null
        // Slot en el pasado (p.ej. tocaron las 15:00 del calendario a las 18:52):
        // avisar SIN pegarle al server — el 422 de anticipación mínima igual lo
        // atraparíamos, pero este caso se explica solo y ahorra el round-trip.
        val d = _draft.value
        if (!_isEditing.value &&
            java.time.LocalDateTime.of(d.date, d.time).isBefore(java.time.LocalDateTime.now(zone))
        ) {
            _result.value = Result.failure(Exception("Ese horario ya pasó — elige una fecha y hora futura."))
            return
        }
        viewModelScope.launch {
            _isSubmitting.value = true
            val r = if (_isEditing.value && editingId != null) {
                repository.updateReservation(editingId!!, _draft.value.toUpdateRequest())
            } else {
                repository.createReservation(_draft.value.toRequest(zone))
            }
            r.onSuccess { created ->
                val pid = promoteWaitlistId
                if (pid != null && created != null) {
                    // Promote failure used to be swallowed: the reservation
                    // exists but the entry stays "Esperando" — re-promoting it
                    // creates a DOUBLE booking. Surface it so staff resolve
                    // the stuck entry instead of re-promoting blindly.
                    waitlistRepository.promoteEntry(pid, created.id).onFailure {
                        _result.value = Result.failure(
                            Exception("Reserva creada, pero la entrada de lista de espera no se marcó como promovida. NO la vuelvas a promover: revisa la lista de espera."),
                        )
                    }
                }
            }
            _isSubmitting.value = false
            if (_result.value?.isFailure != true) {
                _result.value = r.map { it ?: error("Empty reservation") }
            }
        }
    }
}
