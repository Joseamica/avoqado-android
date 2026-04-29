package com.avoqado.pos.reservations.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _state = MutableStateFlow(CalendarUiState(today = LocalDate.now(zone), selectedDate = LocalDate.now(zone)))
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init { fetch() }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(selectedDate = date) }
        fetch()
    }

    fun setView(view: CalendarView) {
        _state.update { it.copy(view = view) }
        fetch()
    }

    fun setVisibleStatuses(statuses: Set<ReservationStatus>) {
        _state.update { it.copy(visibleStatuses = statuses) }
    }

    fun setShowCancelled(show: Boolean) {
        _state.update { it.copy(showCancelled = show) }
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
