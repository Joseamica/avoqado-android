package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ClassSession
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HOUR_HEIGHT_DP = 56.dp
private const val HOUR_AXIS_WIDTH = 44

@Composable
fun CalendarDayGrid(
    selectedDate: LocalDate,
    today: LocalDate,
    reservations: List<Reservation>,
    classSessions: List<ClassSession> = emptyList(),
    venueZone: ZoneId,
    nowTime: LocalTime,
    startHour: Int = 6,
    endHour: Int = 23,
    onReservationClick: (Reservation) -> Unit,
    onClassSessionClick: (ClassSession) -> Unit = {},
    onSlotTap: (LocalTime) -> Unit,
    onReservationReschedule: ((Reservation, ZonedDateTime, ZonedDateTime) -> Unit)? = null,
    onSwipeDay: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hours = remember(startHour, endHour) { (startHour..endHour).toList() }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val hourHeightPx = with(density) { HOUR_HEIGHT_DP.toPx() }
    val swipeThresholdPx = with(density) { 80.dp.toPx() }

    // Horizontal-swipe-to-change-day: accumulated drag delta on horizontal axis.
    // verticalScroll handles vertical drags; draggable(Horizontal) only consumes
    // horizontal drags, so the two coexist without conflict.
    val accumulated = remember { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        accumulated.floatValue += delta
    }

    // Auto-scroll to current hour (or selected day's first reservation) on first composition.
    LaunchedEffect(selectedDate) {
        val anchorHour = if (selectedDate == today) nowTime.hour else startHour
        val anchorMin = (anchorHour - startHour).coerceAtLeast(0) * 60
        val targetPx = (anchorMin / 60f) * HOUR_HEIGHT_DP.value
        scrollState.scrollTo((targetPx - 100f).coerceAtLeast(0f).toInt())
    }

    val swipeModifier = if (onSwipeDay != null) {
        Modifier.draggable(
            state = draggableState,
            orientation = Orientation.Horizontal,
            onDragStarted = { accumulated.floatValue = 0f },
            onDragStopped = {
                val drag = accumulated.floatValue
                accumulated.floatValue = 0f
                when {
                    drag > swipeThresholdPx -> onSwipeDay(-1)  // dragged right → previous day
                    drag < -swipeThresholdPx -> onSwipeDay(1)  // dragged left → next day
                }
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(swipeModifier)
            .verticalScroll(scrollState),
    ) {
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
                    onDragRescheduleRequested = onReservationReschedule?.let { cb ->
                        { _, dyPx ->
                            val deltaMin = ((dyPx / hourHeightPx) * 60f / 15f).roundToInt() * 15
                            if (deltaMin != 0) {
                                val origStarts = ZonedDateTime.parse(r.startsAt).withZoneSameInstant(venueZone)
                                val origEnds = ZonedDateTime.parse(r.endsAt).withZoneSameInstant(venueZone)
                                cb(
                                    r,
                                    origStarts.plusMinutes(deltaMin.toLong()),
                                    origEnds.plusMinutes(deltaMin.toLong()),
                                )
                            }
                        }
                    },
                )
            }
        }

        classSessions.forEach { session ->
            val start = ZonedDateTime.parse(session.startsAt).withZoneSameInstant(venueZone)
            if (start.toLocalDate() != selectedDate) return@forEach
            val topMin = (start.hour - startHour) * 60 + start.minute
            val durMin = session.duration.coerceAtLeast(15)
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
                ClassSessionBlock(
                    session = session,
                    onClick = { onClassSessionClick(session) },
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
