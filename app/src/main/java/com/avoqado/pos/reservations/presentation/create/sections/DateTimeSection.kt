package com.avoqado.pos.reservations.presentation.create.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.avoqado.pos.core.util.VenueTimeZone
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DateTimeSection — embedded date + 15-min slot picker for the create-reservation
 * flow. Lives inside a [ModalBottomSheet] (PickerSheet) — owns its own scroll
 * via LazyColumn. Time slots are emitted as `items` of 4-pill rows so only the
 * currently visible rows are composed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeSection(viewModel: CreateReservationViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }

    // Venue timezone, not the device's — the appointment's wall-clock date/time must be
    // interpreted in the venue's local zone (backend stores UTC). Same rule as iOS.
    val zone = VenueTimeZone.zoneId()
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
    val slotRows = remember(slots) { slots.chunked(4) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AvoqadoTheme.spacing.lg),
    ) {
        item("date-header") {
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            SectionHeader("FECHA")
        }
        item("date-card") {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
        }

        item("time-header") {
            SectionHeader("HORA")
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        }

        items(slotRows.size) { index ->
            val rowSlots = slotRows[index]
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowSlots.forEach { slot ->
                    TimeSlotPill(
                        time = slot,
                        selected = slot == draft.time,
                        onClick = { viewModel.update { it.copy(time = slot) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - rowSlots.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }

        item("bottom-spacer") {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
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
    modifier: Modifier = Modifier,
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
        modifier = modifier
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

