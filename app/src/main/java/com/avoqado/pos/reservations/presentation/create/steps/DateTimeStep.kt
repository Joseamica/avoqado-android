package com.avoqado.pos.reservations.presentation.create.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DateTimeStep — date + time picker for the new reservation flow.
 *
 * Layout:
 *  - "FECHA" section: card showing the formatted date ("vie 1 de may") with a
 *    "Cambiar" TextButton that opens a Material3 [DatePickerDialog].
 *  - "HORA" section: 4-column [LazyVerticalGrid] of 15-min time slots from
 *    09:00 through 21:45 (52 slots). Selected slot uses the primary container;
 *    unselected slots are surface with an outlineVariant border.
 *
 * Note: Availability hint per slot (green/amber dot computed from existing
 * reservations) is deferred to Phase 3 — it requires extra calendar fetch
 * logic that doesn't ship value yet.
 *
 * Date display uses Spanish locale ("EEE d 'de' MMM"). The DatePicker uses
 * `ZoneId.systemDefault()` for the millis<->LocalDate conversion since the
 * user is selecting a calendar day, not an instant; the venue zone is only
 * applied at submit time via `draft.toRequest(zone)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeStep(viewModel: CreateReservationViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }

    val zone = ZoneId.systemDefault()
    val dateLabel = remember(draft.date) {
        draft.date
            .format(DateTimeFormatter.ofPattern("EEE d 'de' MMM", Locale("es")))
            .replaceFirstChar { it.uppercase() }
    }

    val slots = remember {
        buildList {
            var t = LocalTime.of(9, 0)
            val end = LocalTime.of(22, 0)
            while (t.isBefore(end)) {
                add(t)
                t = t.plusMinutes(15)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
    ) {
        SectionHeader("FECHA")
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AvoqadoTheme.spacing.lg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDatePicker = true }) {
                    Text("Cambiar")
                }
            }
        }

        SectionHeader("HORA")
        // Availability hint per slot deferred to Phase 3.
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                horizontal = 0.dp,
                vertical = AvoqadoTheme.spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(slots, key = { it.toString() }) { slot ->
                TimeSlotPill(
                    time = slot,
                    selected = slot == draft.time,
                    onClick = { viewModel.update { it.copy(time = slot) } },
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.date
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val newDate = Instant.ofEpochMilli(millis)
                            .atZone(zone)
                            .toLocalDate()
                        viewModel.update { it.copy(date = newDate) }
                    }
                    showDatePicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancelar")
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 0.8.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun TimeSlotPill(
    time: LocalTime,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = time.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
    }
}
