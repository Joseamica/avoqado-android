package com.avoqado.pos.reservations.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.ClassSessionRepository
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.RescheduleNotificationChannel
import com.avoqado.pos.reservations.domain.ReservationAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val classSessionRepository: ClassSessionRepository,
    private val secureStorage: SecureStorage,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")
    val venueZoneId: ZoneId get() = zone

    private val initialView: CalendarView = secureStorage.calendarViewForCurrentVenue
        ?.let { runCatching { CalendarView.valueOf(it) }.getOrNull() }
        ?: CalendarView.DAY

    private val _state = MutableStateFlow(
        CalendarUiState(
            today = LocalDate.now(zone),
            selectedDate = LocalDate.now(zone),
            view = initialView,
            showClassSessions = secureStorage.showClassSessionsForCurrentVenue,
        ),
    )
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    val isOnline: StateFlow<Boolean> = combine(
        connectivityMonitor.isConnected,
        connectivityMonitor.isServerReachable,
    ) { connected, serverReachable -> connected && serverReachable }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    val pendingActionsCount: StateFlow<Int> = repository.pendingActionsCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    private var fetchJob: Job? = null

    init {
        // Nadie pidió esta carga: la pantalla se abrió sola. Su 403 se cuenta en
        // línea (`state.error`), nunca como modal encima de otra pantalla.
        fetch(background = true)
        // Refetch whenever a reservation mutation happens elsewhere (cancel, reschedule, edit,
        // create, state transition) so the calendar stays in sync without depending on
        // ON_RESUME, which doesn't fire when nested sheets close on top of this screen.
        viewModelScope.launch {
            merge(repository.changes, classSessionRepository.changes)
                .collect { fetch(showLoading = false, background = true) }
        }
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(selectedDate = date) }
        fetch()
    }

    fun setView(view: CalendarView) {
        _state.update { it.copy(view = view) }
        secureStorage.calendarViewForCurrentVenue = view.name
        fetch()
    }

    fun setVisibleStatuses(statuses: Set<ReservationStatus>) {
        _state.update { it.copy(visibleStatuses = statuses) }
    }

    fun setShowCancelled(show: Boolean) {
        _state.update { it.copy(showCancelled = show) }
    }

    fun setShowClassSessions(show: Boolean) {
        _state.update { it.copy(showClassSessions = show) }
        secureStorage.showClassSessionsForCurrentVenue = show
    }

    /**
     * @param background la recarga corre SOLA (el tick de 30 s, el ON_RESUME al
     * volver a la pestaña). El botón de recargar de la barra la deja en `false`:
     * ése sí es un toque, y su "no" tiene que verse.
     */
    fun refresh(showLoading: Boolean = true, background: Boolean = false) =
        fetch(showLoading = showLoading, background = background)

    private val _rescheduleSubmitting = MutableStateFlow(false)
    val rescheduleSubmitting: StateFlow<Boolean> = _rescheduleSubmitting.asStateFlow()

    private val _rescheduleOverCapacityConfirmation = MutableStateFlow<String?>(null)
    val rescheduleOverCapacityConfirmation: StateFlow<String?> =
        _rescheduleOverCapacityConfirmation.asStateFlow()

    private data class PendingRescheduleRetry(
        val reservationId: String,
        val newStarts: ZonedDateTime,
        val newEnds: ZonedDateTime,
        val channel: RescheduleNotificationChannel?,
        val customMessage: String?,
        val onResult: (Boolean, String?) -> Unit,
    )

    private var pendingRescheduleRetry: PendingRescheduleRetry? = null

    fun reschedule(
        reservationId: String,
        newStarts: ZonedDateTime,
        newEnds: ZonedDateTime,
        channel: RescheduleNotificationChannel?,
        customMessage: String?,
        onResult: (Boolean, String?) -> Unit,
    ) {
        performReschedule(
            PendingRescheduleRetry(
                reservationId,
                newStarts,
                newEnds,
                channel,
                customMessage,
                onResult,
            ),
            allowOverCapacity = false,
        )
    }

    private fun performReschedule(request: PendingRescheduleRetry, allowOverCapacity: Boolean) {
        _rescheduleSubmitting.value = true
        viewModelScope.launch {
            val result = repository.runAction(
                reservationId = request.reservationId,
                action = ReservationAction.RESCHEDULE,
                payload = ReservationRepository.ActionPayload.Reschedule(
                    startsAt = request.newStarts.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                    endsAt = request.newEnds.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                    notificationChannel = request.channel,
                    customMessage = request.customMessage,
                    allowOverCapacity = true.takeIf { allowOverCapacity },
                ),
            )
            _rescheduleSubmitting.value = false
            val apiError = result.exceptionOrNull() as? ReservationApiException
            if (!allowOverCapacity && apiError?.code == "OVER_CAPACITY_CONFIRMATION_REQUIRED") {
                pendingRescheduleRetry = request
                _rescheduleOverCapacityConfirmation.value = buildString {
                    append(apiError.message)
                    apiError.preview?.let { append("\nOcupación: $it.") }
                }
                return@launch
            }
            pendingRescheduleRetry = null
            _rescheduleOverCapacityConfirmation.value = null
            request.onResult(result.isSuccess, result.exceptionOrNull()?.message)
        }
    }

    fun confirmRescheduleOverCapacity() {
        val request = pendingRescheduleRetry ?: return
        _rescheduleOverCapacityConfirmation.value = null
        performReschedule(request, allowOverCapacity = true)
    }

    fun dismissRescheduleOverCapacity() {
        pendingRescheduleRetry = null
        _rescheduleOverCapacityConfirmation.value = null
    }

    private fun fetch(showLoading: Boolean = true, background: Boolean = false) {
        val s = _state.value
        val (from, to) = when (s.view) {
            CalendarView.DAY -> s.selectedDate to s.selectedDate
            CalendarView.WEEK -> {
                val sunday = s.selectedDate.minusDays((s.selectedDate.dayOfWeek.value % 7).toLong())
                sunday to sunday.plusDays(6)
            }
        }
        _state.update {
            if (showLoading) it.copy(isLoading = true, error = null) else it.copy(error = null)
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            val fromString = from.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val toString = to.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val reservationsDeferred = async {
                repository.fetchCalendar(fromString, toString, background = background)
            }
            val classSessionsDeferred = async {
                classSessionRepository.fetchList(fromString, toString, background = background)
            }
            val r = reservationsDeferred.await()
            val classResult = classSessionsDeferred.await()
            _state.update {
                when {
                    r.isSuccess && classResult.isSuccess -> it.copy(
                        isLoading = false,
                        reservations = r.getOrNull().orEmpty(),
                        classSessions = classResult.getOrNull().orEmpty(),
                        error = null,
                    )
                    r.isSuccess -> it.copy(
                        isLoading = false,
                        reservations = r.getOrNull().orEmpty(),
                        classSessions = emptyList(),
                        error = classResult.exceptionOrNull()?.message ?: "Error cargando clases",
                    )
                    else -> it.copy(
                        isLoading = false,
                        error = r.exceptionOrNull()?.message ?: "Error cargando calendario",
                    )
                }
            }
        }
    }

    val visibleReservations: List<Reservation>
        get() {
            val s = _state.value
            return s.reservations.filter {
                it.classSessionId == null &&
                    ((it.status in s.visibleStatuses) || (s.showCancelled && it.status == ReservationStatus.CANCELLED))
            }
        }

    val visibleClassSessions: List<com.avoqado.pos.reservations.data.model.ClassSession>
        get() {
            val s = _state.value
            return if (s.showClassSessions) s.classSessions else emptyList()
        }
}
