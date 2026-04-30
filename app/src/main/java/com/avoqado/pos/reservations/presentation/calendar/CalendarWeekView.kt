package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.CalendarWeekGrid
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun CalendarWeekView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onReservationClick: (Reservation) -> Unit,
    onSlotTap: (LocalDate, LocalTime) -> Unit,
    onReservationReschedule: ((Reservation, ZonedDateTime, ZonedDateTime) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val weekStart = state.selectedDate.minusDays((state.selectedDate.dayOfWeek.value % 7).toLong())
    CalendarWeekGrid(
        weekStart = weekStart,
        today = state.today,
        reservations = state.reservations,
        venueZone = venueZone,
        onReservationClick = onReservationClick,
        onSlotTap = onSlotTap,
        onReservationReschedule = onReservationReschedule,
        modifier = modifier.fillMaxSize(),
    )
}
