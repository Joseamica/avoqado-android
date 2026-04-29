package com.avoqado.pos.reservations.presentation.waitlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPhoneInput
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.reservations.data.model.AddToWaitlistRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWaitlistSheet(
    customers: List<Customer>,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSubmit: (AddToWaitlistRequest) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(Countries.byIso("MX")) }
    var partySize by remember { mutableStateOf(2) }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var time by remember { mutableStateOf(LocalTime.now(zone).withSecond(0).withNano(0)) }
    var notes by remember { mutableStateOf("") }
    var isGuest by remember { mutableStateOf(true) }
    var customerId: String? by remember { mutableStateOf(null) }
    var customerName: String? by remember { mutableStateOf(null) }
    var query by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val canSubmit = (isGuest && name.isNotBlank()) || (!isGuest && customerId != null)

    AvoqadoDialog(
        title = "Agregar a la lista de espera",
        onDismiss = onDismiss,
        actionButton = {
            PrimaryButton(
                text = "Agregar",
                onClick = {
                    val instant = ZonedDateTime.of(date, time, zone).toInstant()
                    val iso = DateTimeFormatter.ISO_INSTANT.format(instant)
                    val req = AddToWaitlistRequest(
                        customerId = if (!isGuest) customerId else null,
                        guestName = if (isGuest) name else null,
                        guestPhone = if (isGuest && phone.isNotBlank()) "+${country.dialCode}$phone" else null,
                        partySize = partySize,
                        desiredStartAt = iso,
                        notes = notes.ifBlank { null },
                    )
                    onSubmit(req)
                },
                enabled = canSubmit,
                fullWidth = true,
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = isGuest,
                    onCheckedChange = { on ->
                        isGuest = on
                        if (on) {
                            customerId = null
                            customerName = null
                        } else {
                            name = ""
                            phone = ""
                        }
                    },
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Invitado",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (isGuest) {
                AvoqadoPillTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Nombre",
                )
                AvoqadoPhoneInput(
                    country = country,
                    onCountryChange = { country = it },
                    digits = phone,
                    onDigitsChange = { phone = it },
                )
            } else {
                SearchPillField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Buscar cliente",
                )
                val filtered = customers.filter {
                    query.isBlank() ||
                        it.fullName.contains(query, ignoreCase = true) ||
                        (it.phone?.contains(query) == true)
                }.take(20)
                Column(
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    customerId = c.id
                                    customerName = c.fullName
                                }
                                .padding(AvoqadoTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = c.fullName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                c.displayContact?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (customerId == c.id) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Seleccionado",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            // Party size stepper
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Personas",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = { partySize = (partySize - 1).coerceAtLeast(1) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Quitar")
                }
                Text(
                    text = "$partySize",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { partySize = (partySize + 1).coerceAtMost(50) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Agregar")
                }
            }

            // Date + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fecha y hora",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale("es")))} · ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = { showDatePicker = true }) { Text("Fecha") }
                TextButton(onClick = { showTimePicker = true }) { Text("Hora") }
            }

            if (showDatePicker) {
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let { ms ->
                                date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                            }
                            showDatePicker = false
                        }) { Text("Aceptar") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
                    },
                ) {
                    DatePicker(state = pickerState)
                }
            }

            if (showTimePicker) {
                val timeState = rememberTimePickerState(
                    initialHour = time.hour,
                    initialMinute = time.minute,
                    is24Hour = true,
                )
                AlertDialog(
                    onDismissRequest = { showTimePicker = false },
                    title = { Text("Hora") },
                    text = { TimePicker(state = timeState) },
                    confirmButton = {
                        TextButton(onClick = {
                            time = LocalTime.of(timeState.hour, timeState.minute)
                            showTimePicker = false
                        }) { Text("Aceptar") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTimePicker = false }) { Text("Cancelar") }
                    },
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = { Text("Notas (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}
