package com.avoqado.pos.reservations.presentation.calendar

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import java.time.LocalDate

enum class CalendarView { DAY, WEEK }

data class CalendarUiState(
    val view: CalendarView = CalendarView.DAY,
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val reservations: List<Reservation> = emptyList(),
    val isLoading: Boolean = false,
    val visibleStatuses: Set<ReservationStatus> = setOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN),
    val showCancelled: Boolean = false,
    val error: String? = null,
)
