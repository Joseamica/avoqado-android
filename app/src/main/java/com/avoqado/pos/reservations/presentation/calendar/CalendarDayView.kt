package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.CalendarDayGrid
import com.avoqado.pos.reservations.presentation.components.WeekStrip
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun CalendarDayView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onReservationClick: (Reservation) -> Unit,
    onSlotTap: (LocalDate, LocalTime) -> Unit,
    onReservationReschedule: ((Reservation, ZonedDateTime, ZonedDateTime) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().widthIn(max = 880.dp)) {
        WeekStrip(
            weekOf = state.selectedDate,
            selectedDate = state.selectedDate,
            today = state.today,
            onDateSelected = onSelectDate,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        CalendarDayGrid(
            selectedDate = state.selectedDate,
            today = state.today,
            reservations = state.reservations,
            venueZone = venueZone,
            nowTime = LocalTime.now(venueZone),
            onReservationClick = onReservationClick,
            onSlotTap = { time -> onSlotTap(state.selectedDate, time) },
            onReservationReschedule = onReservationReschedule,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
