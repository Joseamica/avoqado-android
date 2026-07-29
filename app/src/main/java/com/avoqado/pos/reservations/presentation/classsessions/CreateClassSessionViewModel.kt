package com.avoqado.pos.reservations.presentation.classsessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.reservations.data.ClassSessionRepository
import com.avoqado.pos.reservations.data.model.CreateClassSessionBulkRequest
import com.avoqado.pos.reservations.data.model.CreateClassSessionRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.avoqado.pos.core.util.VenueTimeZone
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.contentOrNull

enum class RecurrenceEndMode { COUNT, DATE }

data class CreateClassSessionDraft(
    val productId: String? = null,
    val productName: String? = null,
    val productDuration: Int? = null,
    val date: LocalDate = LocalDate.now(VenueTimeZone.zoneId()),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val capacity: Int = 10,
    val assignedStaffId: String? = null,
    val assignedStaffName: String? = null,
    val internalNotes: String? = null,
    val isRecurring: Boolean = false,
    val weekdays: Set<Int> = emptySet(),
    val endMode: RecurrenceEndMode = RecurrenceEndMode.COUNT,
    val occurrences: Int = 8,
    val endDate: LocalDate? = null,
) {
    val canSubmit: Boolean
        get() = productId != null &&
            capacity >= 1 &&
            (!isRecurring || weekdays.isNotEmpty()) &&
            (!isRecurring || endMode != RecurrenceEndMode.DATE || endDate != null)
}

@HiltViewModel
class CreateClassSessionViewModel @Inject constructor(
    private val repository: ClassSessionRepository,
    val productsRepository: ProductsRepository,
    private val staffRepository: StaffRepository,
    private val secureStorage: SecureStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")
    val products: StateFlow<List<Product>> = productsRepository.products

    private val _staff = MutableStateFlow<List<StaffMember>>(emptyList())
    val staff: StateFlow<List<StaffMember>> = _staff.asStateFlow()

    private val _draft = MutableStateFlow(seedDraft(savedStateHandle))
    val draft: StateFlow<CreateClassSessionDraft> = _draft.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _result = MutableStateFlow<Result<String>?>(null)
    val result: StateFlow<Result<String>?> = _result.asStateFlow()

    init {
        viewModelScope.launch { productsRepository.fetchProducts() }
        viewModelScope.launch {
            staffRepository.getActiveStaff().onSuccess { _staff.value = it }
        }
    }

    private fun seedDraft(handle: SavedStateHandle): CreateClassSessionDraft {
        val date = handle.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = handle.get<String>("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val start = time ?: LocalTime.of(9, 0)
        return CreateClassSessionDraft(
            date = date ?: LocalDate.now(zone),
            startTime = start,
            endTime = start.plusHours(1),
        )
    }

    fun update(transform: (CreateClassSessionDraft) -> CreateClassSessionDraft) {
        _draft.update(transform)
    }

    fun selectProduct(product: Product) {
        val duration = product.duration ?: 60
        _draft.update { draft ->
            val capacity = capacityFromLayout(product) ?: product.maxParticipants ?: draft.capacity
            draft.copy(
                productId = product.id,
                productName = product.name,
                productDuration = duration,
                endTime = draft.startTime.plusMinutes(duration.toLong()),
                capacity = capacity.coerceAtLeast(1),
            )
        }
    }

    fun onProductCreated(product: Product) {
        productsRepository.clearCache()
        viewModelScope.launch {
            productsRepository.fetchProducts()
            selectProduct(product)
        }
    }

    fun setStartTime(time: LocalTime) {
        _draft.update {
            val duration = it.productDuration ?: java.time.Duration.between(it.startTime, it.endTime).toMinutes().toInt().coerceAtLeast(15)
            it.copy(startTime = time, endTime = time.plusMinutes(duration.toLong()))
        }
    }

    fun submit() {
        if (_isSubmitting.value || !_draft.value.canSubmit) return
        viewModelScope.launch {
            _isSubmitting.value = true
            val draft = _draft.value
            val result = if (draft.isRecurring) {
                repository.createBulk(draft.toBulkRequest()).map { bulk ->
                    if (bulk.skipped > 0) "${bulk.count} clases agendadas (${bulk.skipped} omitidas por conflicto)"
                    else "${bulk.count} clases agendadas"
                }
            } else {
                val starts = draft.date.atTime(draft.startTime).atZone(zone)
                if (starts.toInstant().isBefore(java.time.Instant.now().minusSeconds(60))) {
                    Result.failure(Exception("No se puede agendar una clase en el pasado"))
                } else {
                    repository.create(draft.toSingleRequest(zone)).map { "¡Clase agendada!" }
                }
            }
            _result.value = result
            _isSubmitting.value = false
        }
    }

    private fun CreateClassSessionDraft.toSingleRequest(zoneId: ZoneId): CreateClassSessionRequest {
        val starts = date.atTime(startTime).atZone(zoneId).withZoneSameInstant(ZoneOffset.UTC)
        val ends = date.atTime(endTime).atZone(zoneId).withZoneSameInstant(ZoneOffset.UTC)
        return CreateClassSessionRequest(
            productId = productId!!,
            startsAt = starts.format(DateTimeFormatter.ISO_INSTANT),
            endsAt = ends.format(DateTimeFormatter.ISO_INSTANT),
            capacity = capacity,
            assignedStaffId = assignedStaffId,
            internalNotes = internalNotes,
        )
    }

    private fun CreateClassSessionDraft.toBulkRequest(): CreateClassSessionBulkRequest =
        CreateClassSessionBulkRequest(
            productId = productId!!,
            startDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            startTime = startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
            endTime = endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
            weekdays = weekdays.toList().sorted(),
            endDate = if (endMode == RecurrenceEndMode.DATE) endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) else null,
            occurrences = if (endMode == RecurrenceEndMode.COUNT) occurrences.coerceIn(1, 104) else null,
            capacity = capacity,
            assignedStaffId = assignedStaffId,
            internalNotes = internalNotes,
        )

    private fun capacityFromLayout(product: Product): Int? {
        val spots = product.layoutConfig
            ?.jsonObject
            ?.get("spots")
            ?.jsonArray
            ?: return null
        return spots.count { spot ->
            spot.jsonObject["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != false
        }.takeIf { it > 0 }
    }
}
