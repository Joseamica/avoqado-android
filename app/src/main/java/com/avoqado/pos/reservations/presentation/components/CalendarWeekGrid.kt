package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

private val HOUR_HEIGHT_DP = 56.dp

@Composable
fun CalendarWeekGrid(
    weekStart: LocalDate,
    today: LocalDate,
    reservations: List<Reservation>,
    venueZone: ZoneId,
    onReservationClick: (Reservation) -> Unit,
    startHour: Int = 6,
    endHour: Int = 23,
    modifier: Modifier = Modifier,
) {
    val hours = (startHour..endHour).toList()
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(48.dp))
            days.forEach { d ->
                Text(
                    "${d.dayOfMonth}\n${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("es"))}",
                    modifier = Modifier.weight(1f).padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        hours.forEach { hour ->
            Row(Modifier.height(HOUR_HEIGHT_DP).fillMaxWidth()) {
                Text(
                    "%02d".format(hour),
                    modifier = Modifier.width(48.dp).padding(start = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
                days.forEach { d ->
                    DayHourCell(
                        date = d,
                        hour = hour,
                        reservations = reservations.filter {
                            val starts = ZonedDateTime.parse(it.startsAt).withZoneSameInstant(venueZone)
                            starts.toLocalDate() == d && starts.hour == hour
                        },
                        onReservationClick = onReservationClick,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHourCell(
    date: LocalDate,
    hour: Int,
    reservations: List<Reservation>,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(2.dp)) {
        reservations.forEach { r ->
            ReservationBlock(
                reservation = r,
                onClick = { onReservationClick(r) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
    }
}
