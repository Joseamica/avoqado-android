package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.Canvas
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
import java.time.format.DateTimeFormatter

private val HOUR_HEIGHT_DP = 64.dp

@Composable
fun CalendarDayGrid(
    selectedDate: LocalDate,
    today: LocalDate,
    reservations: List<Reservation>,
    venueZone: ZoneId,
    nowTime: LocalTime,
    startHour: Int = 6,
    endHour: Int = 23,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hours = (startHour..endHour).toList()

    Box(modifier = modifier.verticalScroll(rememberScrollState())) {
        Column {
            hours.forEach { hour ->
                Row(Modifier.height(HOUR_HEIGHT_DP).fillMaxWidth()) {
                    Text(
                        "%02d".format(hour),
                        modifier = Modifier.width(48.dp).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Canvas(Modifier.weight(1f).fillMaxHeight()) {
                        drawLine(
                            color = Color.LightGray.copy(alpha = 0.5f),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1f,
                        )
                    }
                }
            }
        }

        reservations.forEach { r ->
            val start = ZonedDateTime.parse(r.startsAt).withZoneSameInstant(venueZone)
            if (start.toLocalDate() != selectedDate) return@forEach
            val topMin = (start.hour - startHour) * 60 + start.minute
            val durMin = r.duration.coerceAtLeast(15)
            if (topMin < 0) return@forEach
            val topDp = (topMin / 60f) * HOUR_HEIGHT_DP.value
            val heightDp = (durMin / 60f) * HOUR_HEIGHT_DP.value

            Box(
                Modifier
                    .padding(start = 56.dp, end = 8.dp)
                    .offset(y = topDp.dp)
                    .height(heightDp.dp)
                    .fillMaxWidth(),
            ) {
                ReservationBlock(reservation = r, onClick = { onReservationClick(r) }, modifier = Modifier.fillMaxSize())
            }
        }

        if (selectedDate == today) {
            val nowMin = (nowTime.hour - startHour) * 60 + nowTime.minute
            if (nowMin in 0..(hours.size * 60)) {
                val topDp = (nowMin / 60f) * HOUR_HEIGHT_DP.value
                CurrentTimeIndicator(yOffsetDp = topDp, label = nowTime.format(DateTimeFormatter.ofPattern("HH:mm")))
            }
        }
    }
}
