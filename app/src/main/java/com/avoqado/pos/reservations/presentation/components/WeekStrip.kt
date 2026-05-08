package com.avoqado.pos.reservations.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val SPANISH_LETTERS = listOf("D", "L", "M", "M", "J", "V", "S")
private const val WEEK_SLIDE_MS = 260

/**
 * 7-day strip header used by both Day and Week views.
 *
 * Swipe horizontally on the strip to jump ±7 days (Square pattern). The 7 cells
 * are wrapped in an [AnimatedContent] keyed on the week start, so the entire
 * strip slides horizontally when the visible week changes — but tapping a
 * different day inside the same week only moves the highlight (no slide), since
 * the week start is unchanged.
 *
 * @param onSwipeWeek invoked with +1 (forward 1 week) or -1 (back 1 week) once
 * accumulated horizontal drag exceeds the threshold.
 */
@Composable
fun WeekStrip(
    weekOf: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onSwipeWeek: ((Int) -> Unit)? = null,
) {
    // ISO Mon=1..Sun=7. We want Sunday-first column: dayOfWeek.value % 7 → Sun=0, Mon=1..Sat=6.
    val sunday = weekOf.minusDays((weekOf.dayOfWeek.value % 7).toLong())

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 60.dp.toPx() }
    val accumulated = remember { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        accumulated.floatValue += delta
    }

    val swipeModifier = if (onSwipeWeek != null) {
        Modifier.draggable(
            state = draggableState,
            orientation = Orientation.Horizontal,
            onDragStarted = { accumulated.floatValue = 0f },
            onDragStopped = {
                val drag = accumulated.floatValue
                accumulated.floatValue = 0f
                when {
                    drag > swipeThresholdPx -> onSwipeWeek(-1)
                    drag < -swipeThresholdPx -> onSwipeWeek(1)
                }
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(swipeModifier),
    ) {
        AnimatedContent(
            targetState = sunday,
            transitionSpec = {
                val forward = targetState.isAfter(initialState)
                val enter = if (forward) {
                    slideInHorizontally(animationSpec = tween(WEEK_SLIDE_MS)) { w -> w }
                } else {
                    slideInHorizontally(animationSpec = tween(WEEK_SLIDE_MS)) { w -> -w }
                }
                val exit = if (forward) {
                    slideOutHorizontally(animationSpec = tween(WEEK_SLIDE_MS)) { w -> -w }
                } else {
                    slideOutHorizontally(animationSpec = tween(WEEK_SLIDE_MS)) { w -> w }
                }
                (enter + fadeIn(tween(WEEK_SLIDE_MS))) togetherWith
                    (exit + fadeOut(tween(WEEK_SLIDE_MS)))
            },
            label = "week-strip-swipe",
        ) { weekStart ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (i in 0..6) {
                    val date = weekStart.plusDays(i.toLong())
                    val isSelected = date == selectedDate
                    val isToday = date == today
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onDateSelected(date) }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            SPANISH_LETTERS[i],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.onSurface
                                        isToday -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> Color.Transparent
                                    },
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewWeekStrip() {
    val today = LocalDate.now()
    WeekStrip(weekOf = today, selectedDate = today, today = today, onDateSelected = {})
}
