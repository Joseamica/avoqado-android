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
import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.ReservationTimeSlot
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

    // Slots REALMENTE reservables del día seleccionado — el server aplica
    // horario de operación, intervalo, pacing y aviso mínimo, así que el
    // picker no ofrece horas imposibles. null = cargando. En modo legacy la
    // sección conserva su fallback estático; en modo staff-aware una falla se
    // expone por separado para no ofrecer horas que el server no confirmó.
    private val _availableSlots = MutableStateFlow<List<ReservationTimeSlot>?>(null)
    val availableSlots: StateFlow<List<ReservationTimeSlot>?> = _availableSlots.asStateFlow()

    private val _slotLoadError = MutableStateFlow<String?>(null)
    val slotLoadError: StateFlow<String?> = _slotLoadError.asStateFlow()
    private var slotsJob: kotlinx.coroutines.Job? = null

    private val _staffAware = MutableStateFlow(false)
    val staffAware: StateFlow<Boolean> = _staffAware.asStateFlow()

    private val _eligibleStaffAvailable = MutableStateFlow(true)
    val eligibleStaffAvailable: StateFlow<Boolean> = _eligibleStaffAvailable.asStateFlow()

    private val _requiresSlotReselection = MutableStateFlow(false)
    val requiresSlotReselection: StateFlow<Boolean> = _requiresSlotReselection.asStateFlow()

    private val _overCapacityConfirmation = MutableStateFlow<String?>(null)
    val overCapacityConfirmation: StateFlow<String?> = _overCapacityConfirmation.asStateFlow()

    private var allStaff: List<StaffMember> = emptyList()
    private var staffMappingJob: kotlinx.coroutines.Job? = null

    private fun usesStaffAwareAppointment(draft: CreateReservationDraft = _draft.value): Boolean =
        _staffAware.value && draft.productType == "APPOINTMENTS_SERVICE"

    fun loadSlots() {
        val d = _draft.value
        slotsJob?.cancel()
        slotsJob = viewModelScope.launch {
            _availableSlots.value = null
            _slotLoadError.value = null
            if (usesStaffAwareAppointment(d) && !_eligibleStaffAvailable.value) {
                _availableSlots.value = emptyList()
                return@launch
            }
            repository.availableSlots(
                date = d.date,
                durationMin = d.durationMinutes,
                zone = zone,
                productId = d.productId,
                staffId = d.assignedStaffId,
                includeFull = usesStaffAwareAppointment(d),
                windowSemantics = "base".takeIf { usesStaffAwareAppointment(d) },
            )
                .onSuccess { _availableSlots.value = it }
                .onFailure {
                    if (usesStaffAwareAppointment(d)) {
                        _slotLoadError.value = "No se pudieron cargar los horarios. Reintenta."
                    }
                }
            // Legacy: null conserva el fallback estático de la UI. Staff-aware:
            // null + slotLoadError bloquea selección y muestra un reintento.
        }
    }

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
        viewModelScope.launch {
            productsRepository.fetchProducts()
            reconcileSelectedProduct()
        }
        viewModelScope.launch {
            repository.reservationSettings().onSuccess { settings ->
                _staffAware.value = settings.isStaffAware
                refreshStaffForCurrentProduct()
                loadSlots()
            }
        }
        viewModelScope.launch {
            tablesRepository.fetchTables().onSuccess { _tables.value = it }
        }
        viewModelScope.launch {
            staffRepository.getActiveStaff().onSuccess {
                allStaff = it
                refreshStaffForCurrentProduct()
            }
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

    fun selectProduct(product: Product) {
        _draft.update {
            it.copy(
                productId = product.id,
                productName = product.name,
                productType = product.type,
                durationMinutes = product.duration ?: 60,
                assignedStaffId = null,
                assignedStaffName = null,
            )
        }
        _requiresSlotReselection.value = usesStaffAwareAppointment()
        refreshStaffForCurrentProduct()
        loadSlots()
    }

    fun selectStaff(member: StaffMember?) {
        _draft.update {
            it.copy(
                assignedStaffId = member?.id,
                assignedStaffName = member?.fullName,
            )
        }
        if (usesStaffAwareAppointment()) _requiresSlotReselection.value = true
        loadSlots()
    }

    fun selectDate(date: LocalDate) {
        _draft.update { it.copy(date = date) }
        if (usesStaffAwareAppointment()) _requiresSlotReselection.value = true
        loadSlots()
    }

    fun selectTime(time: LocalTime) {
        _draft.update { it.copy(time = time) }
        _requiresSlotReselection.value = false
    }

    fun updatePartySize(partySize: Int) {
        _draft.update { it.copy(partySize = partySize.coerceIn(1, 50)) }
        if (usesStaffAwareAppointment()) _requiresSlotReselection.value = true
        loadSlots()
    }

    private fun reconcileSelectedProduct() {
        val productId = _draft.value.productId ?: return
        val fresh = productsRepository.getProduct(productId) ?: return
        _draft.update {
            it.copy(
                productName = fresh.name,
                productType = fresh.type,
                durationMinutes = fresh.duration ?: it.durationMinutes,
            )
        }
        refreshStaffForCurrentProduct()
    }

    private fun refreshStaffForCurrentProduct() {
        staffMappingJob?.cancel()
        val d = _draft.value
        if (!usesStaffAwareAppointment(d) || d.productId == null) {
            _staff.value = allStaff
            _eligibleStaffAvailable.value = true
            return
        }
        staffMappingJob = viewModelScope.launch {
            repository.productStaff(d.productId)
                .onSuccess { mapping ->
                    val eligibleIds = mapping.staff.mapTo(mutableSetOf()) { it.staffId }
                    val eligible = allStaff.filter { it.id in eligibleIds }
                    _staff.value = eligible
                    // Mapping existence is authoritative. A user without
                    // teams:read may not have names in allStaff, but can still
                    // choose "Cualquiera" and let the server auto-assign.
                    _eligibleStaffAvailable.value = eligibleIds.isNotEmpty()
                    if (_draft.value.assignedStaffId !in eligibleIds) {
                        _draft.update { it.copy(assignedStaffId = null, assignedStaffName = null) }
                    }
                }
                .onFailure {
                    // Permission/network fallback: keep the legacy roster visible;
                    // the transactional create remains authoritative.
                    _staff.value = allStaff
                    _eligibleStaffAvailable.value = true
                }
        }
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

    fun submit(allowOverCapacity: Boolean = false) {
        if (_isSubmitting.value) return
        _overCapacityConfirmation.value = null
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
        // WALK-IN exento: la persona está aquí AHORA — su hora es "ya" por
        // definición (y con gracia de 5 min para que los segundos que corren
        // entre abrir el form y picar Crear no conviertan "ahora" en "pasado").
        val d = _draft.value
        if (!_isEditing.value && usesStaffAwareAppointment(d) && _requiresSlotReselection.value) {
            _result.value = Result.failure(Exception("Selecciona nuevamente un horario disponible."))
            return
        }
        if (!_isEditing.value && usesStaffAwareAppointment(d) && !_eligibleStaffAvailable.value) {
            _result.value = Result.failure(Exception("Este servicio no tiene profesionistas configurados."))
            return
        }
        if (!_isEditing.value &&
            d.channel != ReservationChannel.WALK_IN &&
            java.time.LocalDateTime.of(d.date, d.time)
                .isBefore(java.time.LocalDateTime.now(zone).minusMinutes(5))
        ) {
            _result.value = Result.failure(Exception("Ese horario ya pasó — elige una fecha y hora futura."))
            return
        }
        viewModelScope.launch {
            _isSubmitting.value = true
            val requestDraft = d
            val useBaseWindow = usesStaffAwareAppointment(requestDraft)
            val r = if (_isEditing.value && editingId != null) {
                repository.updateReservation(editingId!!, requestDraft.toUpdateRequest())
            } else {
                repository.createReservation(
                    requestDraft.toRequest(
                        zone = zone,
                        useBaseWindow = useBaseWindow,
                        allowOverCapacity = allowOverCapacity,
                    ),
                )
            }
            val apiError = r.exceptionOrNull() as? ReservationApiException
            if (apiError?.code == "OVER_CAPACITY_CONFIRMATION_REQUIRED") {
                _isSubmitting.value = false
                _overCapacityConfirmation.value = buildString {
                    append(apiError.message)
                    apiError.preview?.let { append("\nOcupación: $it.") }
                }
                return@launch
            }
            if (apiError?.code == "APPOINTMENT_WINDOW_CHANGED") {
                productsRepository.fetchProducts()
                reconcileSelectedProduct()
                _requiresSlotReselection.value = true
                loadSlots()
                _isSubmitting.value = false
                _result.value = Result.failure(
                    Exception("La duración del servicio cambió. Elige un horario disponible nuevamente."),
                )
                return@launch
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

    fun confirmOverCapacity() {
        if (_overCapacityConfirmation.value == null) return
        submit(allowOverCapacity = true)
    }

    fun dismissOverCapacityConfirmation() {
        _overCapacityConfirmation.value = null
    }
}
