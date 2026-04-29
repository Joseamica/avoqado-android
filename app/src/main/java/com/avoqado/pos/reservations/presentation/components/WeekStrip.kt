package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val SPANISH_LETTERS = listOf("D", "L", "M", "M", "J", "V", "S")

@Composable
fun WeekStrip(
    weekOf: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ISO Mon=1..Sun=7. We want Sunday-first column: dayOfWeek.value % 7 → Sun=0, Mon=1..Sat=6.
    val sunday = weekOf.minusDays((weekOf.dayOfWeek.value % 7).toLong())
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (i in 0..6) {
            val date = sunday.plusDays(i.toLong())
            val isSelected = date == selectedDate
            val isToday = date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onDateSelected(date) }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    SPANISH_LETTERS[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewWeekStrip() {
    val today = LocalDate.now()
    WeekStrip(weekOf = today, selectedDate = today, today = today, onDateSelected = {})
}
