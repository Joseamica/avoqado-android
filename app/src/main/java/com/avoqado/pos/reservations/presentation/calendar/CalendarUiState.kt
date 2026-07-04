package com.avoqado.pos.reservations.presentation.calendar

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.ClassSession
import com.avoqado.pos.core.util.VenueTimeZone
import java.time.LocalDate

enum class CalendarView { DAY, WEEK }

data class CalendarUiState(
    val view: CalendarView = CalendarView.DAY,
    val selectedDate: LocalDate = LocalDate.now(VenueTimeZone.zoneId()),
    val today: LocalDate = LocalDate.now(VenueTimeZone.zoneId()),
    val reservations: List<Reservation> = emptyList(),
    val classSessions: List<ClassSession> = emptyList(),
    val isLoading: Boolean = false,
    val visibleStatuses: Set<ReservationStatus> = setOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN),
    val showCancelled: Boolean = false,
    val showClassSessions: Boolean = true,
    val error: String? = null,
) {
    val visibleReservations: List<Reservation>
        get() = reservations.filter {
            it.classSessionId == null &&
                ((it.status in visibleStatuses) || (showCancelled && it.status == ReservationStatus.CANCELLED))
        }

    val visibleClassSessions: List<ClassSession>
        get() = if (showClassSessions) classSessions else emptyList()
}
