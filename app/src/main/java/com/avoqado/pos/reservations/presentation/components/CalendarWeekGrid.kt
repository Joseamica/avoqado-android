package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import java.time.LocalDate
import java.time.LocalTime
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
    onSlotTap: (LocalDate, LocalTime) -> Unit,
    startHour: Int = 6,
    endHour: Int = 23,
    modifier: Modifier = Modifier,
) {
    val hours = (startHour..endHour).toList()
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }
    val gridLine = Color.LightGray.copy(alpha = 0.4f)

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
                days.forEachIndexed { index, d ->
                    DayHourCell(
                        date = d,
                        hour = hour,
                        reservations = reservations.filter {
                            val starts = ZonedDateTime.parse(it.startsAt).withZoneSameInstant(venueZone)
                            starts.toLocalDate() == d && starts.hour == hour
                        },
                        onReservationClick = onReservationClick,
                        onSlotTap = onSlotTap,
                        gridLine = gridLine,
                        drawRightBorder = index < days.size - 1,
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
    onSlotTap: (LocalDate, LocalTime) -> Unit,
    gridLine: Color,
    drawRightBorder: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clickable { onSlotTap(date, LocalTime.of(hour, 0)) }) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                color = gridLine,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1f,
            )
            if (drawRightBorder) {
                drawLine(
                    color = gridLine,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f,
                )
            }
        }
        Column(Modifier.padding(2.dp)) {
            reservations.forEach { r ->
                ReservationBlock(
                    reservation = r,
                    onClick = { onReservationClick(r) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                )
            }
        }
    }
}
