package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleSheet(
    venueTimezone: ZoneId,
    onDismiss: () -> Unit,
    viewModel: ReservationDetailViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsState()
    val reservation = state.reservation

    var date by remember { mutableStateOf(LocalDate.now(venueTimezone)) }
    var startTime by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var endTime by remember { mutableStateOf(LocalTime.of(11, 0)) }
    val isValid = endTime.isAfter(startTime)

    var showDatePicker by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<TimeSlot?>(null) }
    var pendingConfirm by remember {
        mutableStateOf<Pair<ZonedDateTime, ZonedDateTime>?>(null)
    }

    LaunchedEffect(state.justCompletedAction) {
        if (state.justCompletedAction == ReservationAction.RESCHEDULE) onDismiss()
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(venueTimezone).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        date = Instant.ofEpochMilli(ms).atZone(venueTimezone).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    pickingTime?.let { slot ->
        val initial = if (slot == TimeSlot.START) startTime else endTime
        val tps = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text(if (slot == TimeSlot.START) "Hora de inicio" else "Hora de fin") },
            text = { TimePicker(state = tps) },
            confirmButton = {
                TextButton(onClick = {
                    val new = LocalTime.of(tps.hour, tps.minute)
                    if (slot == TimeSlot.START) startTime = new else endTime = new
                    pickingTime = null
                }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = null }) { Text("Cancelar") }
            },
        )
    }

    pendingConfirm?.let { (newStarts, newEnds) ->
        if (reservation != null) {
            RescheduleConfirmSheet(
                reservation = reservation,
                newStarts = newStarts,
                newEnds = newEnds,
                venueZone = venueTimezone,
                isSubmitting = state.pendingAction == ReservationAction.RESCHEDULE,
                onConfirm = { channel, message ->
                    viewModel.runAction(
                        ReservationAction.RESCHEDULE,
                        ReservationRepository.ActionPayload.Reschedule(
                            startsAt = newStarts.withZoneSameInstant(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                            endsAt = newEnds.withZoneSameInstant(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                            notificationChannel = channel,
                            customMessage = message,
                        ),
                    )
                },
                onDismiss = { if (state.pendingAction != ReservationAction.RESCHEDULE) pendingConfirm = null },
            )
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text("Reagendar reserva", style = MaterialTheme.typography.headlineSmall)
            // Failed reschedule was invisible (state.error set, never rendered).
            state.error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.padding(8.dp))
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Fecha: ${date.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy"))}")
            }
            Spacer(Modifier.padding(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { pickingTime = TimeSlot.START },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Inicio: ${startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                }
                OutlinedButton(
                    onClick = { pickingTime = TimeSlot.END },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Fin: ${endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                }
            }
            if (!isValid) {
                Spacer(Modifier.padding(4.dp))
                Text(
                    "La hora de fin debe ser posterior a la hora de inicio",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.padding(12.dp))
            Button(
                onClick = {
                    val starts = ZonedDateTime.of(date, startTime, venueTimezone)
                    val ends = ZonedDateTime.of(date, endTime, venueTimezone)
                    pendingConfirm = starts to ends
                },
                enabled = isValid && state.pendingAction != ReservationAction.RESCHEDULE && reservation != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) {
                Text("Continuar")
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

private enum class TimeSlot { START, END }
