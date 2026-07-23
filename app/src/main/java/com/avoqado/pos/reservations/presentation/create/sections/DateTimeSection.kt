package com.avoqado.pos.reservations.presentation.create.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.reservations.data.ReservationTimeSlot
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

    // Slots del SERVER (horario real, aviso mínimo, pacing) — el picker no debe
    // ofrecer horas que la configuración no permite. Sólo el contrato legacy
    // conserva la grilla estática; staff-aware bloquea ante una falla y ofrece
    // reintento para no mostrar horarios sin confirmar.
    val serverSlots by viewModel.availableSlots.collectAsStateWithLifecycle()
    val slotLoadError by viewModel.slotLoadError.collectAsStateWithLifecycle()
    val staffAware by viewModel.staffAware.collectAsStateWithLifecycle()
    val usesStaffAwareContract = staffAware && draft.productType == "APPOINTMENTS_SERVICE"
    LaunchedEffect(draft.date, draft.durationMinutes, draft.productId, draft.assignedStaffId, draft.partySize) {
        viewModel.loadSlots()
    }
    val slots = remember(serverSlots, draft.date, usesStaffAwareContract) {
        serverSlots ?: if (usesStaffAwareContract) emptyList() else buildList {
            var t = LocalTime.of(9, 0)
            val end = LocalTime.of(22, 0)
            while (t.isBefore(end)) {
                add(ReservationTimeSlot(time = t, available = true))
                t = t.plusMinutes(15)
            }
        }.filter { slot ->
            draft.date != java.time.LocalDate.now(zone) || slot.time.isAfter(java.time.LocalTime.now(zone))
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
        if (usesStaffAwareContract && slotLoadError != null) {
            item("slots-error") {
                Column(modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md)) {
                    Text(
                        text = slotLoadError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::loadSlots) {
                        Text("Reintentar")
                    }
                }
            }
        } else if (usesStaffAwareContract && serverSlots == null) {
            item("loading-slots") {
                Text(
                    text = "Cargando horarios…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.lg),
                )
            }
        } else if (serverSlots?.isEmpty() == true) {
            item("no-slots") {
                Text(
                    text = "No hay horarios disponibles este día — prueba otra fecha.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.lg),
                )
            }
        }

        items(slotRows.size) { index ->
            val rowSlots = slotRows[index]
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowSlots.forEach { slot ->
                    TimeSlotPill(
                        slot = slot,
                        selected = slot.time == draft.time,
                        onClick = { viewModel.selectTime(slot.time) },
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
        // 🔴 Material3 DatePicker habla en MEDIANOCHE UTC (entrada y salida).
        // Interpretarlo en la zona del venue (UTC-6) corría el día: elegir el
        // 20 devolvía el 19 a las 18:00 → toLocalDate() = 19 — la fecha "no
        // cambiaba" y una cita podía caer en el día equivocado.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.date
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val newDate = Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                        viewModel.selectDate(newDate)
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
    slot: ReservationTimeSlot,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (slot.isFull) {
        Warning.copy(alpha = if (selected) 0.2f else 0.08f)
    } else if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (slot.isFull) {
        Warning
    } else if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (slot.isFull) {
        Warning
    } else if (selected) {
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
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = slot.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            if (slot.isFull) {
                Text(
                    text = "Lleno",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
            }
        }
    }
}
