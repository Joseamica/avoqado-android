package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.CalendarDayGrid
import com.avoqado.pos.reservations.presentation.components.WeekStrip
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun CalendarDayView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().widthIn(max = 880.dp)) {
        WeekStrip(
            weekOf = state.selectedDate,
            selectedDate = state.selectedDate,
            today = state.today,
            onDateSelected = onSelectDate,
        )
        if (state.reservations.isEmpty() && !state.isLoading) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("Sin reservas hoy", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CalendarDayGrid(
            selectedDate = state.selectedDate,
            today = state.today,
            reservations = state.reservations,
            venueZone = venueZone,
            nowTime = LocalTime.now(venueZone),
            onReservationClick = onReservationClick,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
