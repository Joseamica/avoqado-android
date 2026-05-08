package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ClassSession
import com.avoqado.pos.reservations.presentation.components.CalendarDayGrid
import com.avoqado.pos.reservations.presentation.components.WeekStrip
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private const val SLIDE_DURATION_MS = 260

@Composable
fun CalendarDayView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onReservationClick: (Reservation) -> Unit,
    onClassSessionClick: (ClassSession) -> Unit = {},
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
            onSwipeWeek = { dir -> onSelectDate(state.selectedDate.plusWeeks(dir.toLong())) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // Slide the entire grid horizontally when the selected day changes —
        // forward (left swipe / next day) slides new content in from the right,
        // backward slides it in from the left. Tapping a date in WeekStrip uses
        // the same animation, picked by date comparison.
        AnimatedContent(
            targetState = state.selectedDate,
            transitionSpec = {
                val forward = targetState.isAfter(initialState)
                val enter = if (forward) {
                    slideInHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { w -> w }
                } else {
                    slideInHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { w -> -w }
                }
                val exit = if (forward) {
                    slideOutHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { w -> -w }
                } else {
                    slideOutHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { w -> w }
                }
                (enter + fadeIn(tween(SLIDE_DURATION_MS))) togetherWith
                    (exit + fadeOut(tween(SLIDE_DURATION_MS)))
            },
            label = "calendar-day-swipe",
            modifier = Modifier.fillMaxSize(),
        ) { date ->
            CalendarDayGrid(
                selectedDate = date,
                today = state.today,
                reservations = state.visibleReservations,
                classSessions = state.visibleClassSessions,
                venueZone = venueZone,
                nowTime = LocalTime.now(venueZone),
                onReservationClick = onReservationClick,
                onClassSessionClick = onClassSessionClick,
                onSlotTap = { time -> onSlotTap(date, time) },
                onReservationReschedule = onReservationReschedule,
                onSwipeDay = { dir -> onSelectDate(date.plusDays(dir.toLong())) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
