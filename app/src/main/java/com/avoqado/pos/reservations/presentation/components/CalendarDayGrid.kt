package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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

private val HOUR_HEIGHT_DP = 56.dp
private const val HOUR_AXIS_WIDTH = 44

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
    onSlotTap: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hours = remember(startHour, endHour) { (startHour..endHour).toList() }
    val scrollState = rememberScrollState()

    // Auto-scroll to current hour (or selected day's first reservation) on first composition.
    LaunchedEffect(selectedDate) {
        val anchorHour = if (selectedDate == today) nowTime.hour else startHour
        val anchorMin = (anchorHour - startHour).coerceAtLeast(0) * 60
        val targetPx = (anchorMin / 60f) * HOUR_HEIGHT_DP.value
        scrollState.scrollTo((targetPx - 100f).coerceAtLeast(0f).toInt())
    }

    Box(modifier = modifier.fillMaxSize().verticalScroll(scrollState)) {
        // Hour rows — each tappable
        Column(Modifier.fillMaxWidth()) {
            hours.forEach { hour ->
                Row(
                    Modifier
                        .height(HOUR_HEIGHT_DP)
                        .fillMaxWidth()
                        .clickable { onSlotTap(LocalTime.of(hour, 0)) },
                ) {
                    Text(
                        "%02d".format(hour),
                        modifier = Modifier
                            .width(HOUR_AXIS_WIDTH.dp)
                            .padding(start = 4.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Canvas(Modifier.weight(1f).fillMaxHeight()) {
                        drawLine(
                            color = Color.LightGray.copy(alpha = 0.4f),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1f,
                        )
                    }
                }
            }
            // Trailing spacer so the last hour can scroll fully into view
            Spacer(Modifier.height(40.dp))
        }

        // Reservation blocks (overlay layer)
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
                    .padding(start = (HOUR_AXIS_WIDTH + 4).dp, end = 8.dp)
                    .offset(y = topDp.dp)
                    .height(heightDp.dp)
                    .fillMaxWidth(),
            ) {
                ReservationBlock(
                    reservation = r,
                    onClick = { onReservationClick(r) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Current-time indicator (overlay)
        if (selectedDate == today) {
            val nowMin = (nowTime.hour - startHour) * 60 + nowTime.minute
            if (nowMin in 0..(hours.size * 60)) {
                val topDp = (nowMin / 60f) * HOUR_HEIGHT_DP.value
                CurrentTimeIndicator(
                    yOffsetDp = topDp,
                    label = nowTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                )
            }
        }
    }
}

/** Renders a centered "Sin reservas para este día" label inline at row=startHour, used as overlay. */
@Composable
fun CalendarDayEmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Toca cualquier hora para crear",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
