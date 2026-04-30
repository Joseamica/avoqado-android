package com.avoqado.pos.reservations.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.ReservationRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    init {
        fetch()
        // Refetch whenever a reservation mutation happens elsewhere (cancel, reschedule, edit,
        // create, state transition) so the calendar stays in sync without depending on
        // ON_RESUME, which doesn't fire when nested sheets close on top of this screen.
        viewModelScope.launch {
            repository.changes.collect { fetch() }
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

    fun refresh() = fetch()

    private val _rescheduleSubmitting = MutableStateFlow(false)
    val rescheduleSubmitting: StateFlow<Boolean> = _rescheduleSubmitting.asStateFlow()

    fun reschedule(
        reservationId: String,
        newStarts: ZonedDateTime,
        newEnds: ZonedDateTime,
        channel: RescheduleNotificationChannel?,
        customMessage: String?,
        onResult: (Boolean, String?) -> Unit,
    ) {
        _rescheduleSubmitting.value = true
        viewModelScope.launch {
            val result = repository.runAction(
                reservationId = reservationId,
                action = ReservationAction.RESCHEDULE,
                payload = ReservationRepository.ActionPayload.Reschedule(
                    startsAt = newStarts.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                    endsAt = newEnds.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                    notificationChannel = channel,
                    customMessage = customMessage,
                ),
            )
            _rescheduleSubmitting.value = false
            onResult(result.isSuccess, result.exceptionOrNull()?.message)
        }
    }

    private fun fetch() {
        val s = _state.value
        val (from, to) = when (s.view) {
            CalendarView.DAY -> s.selectedDate to s.selectedDate
            CalendarView.WEEK -> {
                val sunday = s.selectedDate.minusDays((s.selectedDate.dayOfWeek.value % 7).toLong())
                sunday to sunday.plusDays(6)
            }
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = repository.fetchCalendar(
                dateFrom = from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                dateTo = to.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, reservations = r.getOrNull().orEmpty(), error = null)
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando calendario")
            }
        }
    }

    val visibleReservations: List<Reservation>
        get() {
            val s = _state.value
            return s.reservations.filter {
                (it.status in s.visibleStatuses) || (s.showCancelled && it.status == ReservationStatus.CANCELLED)
            }
        }
}
