package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.RescheduleNotificationChannel
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SUCCESS_GREEN = Color(0xFF1F9D55)

private data class ChannelOption(
    val value: RescheduleNotificationChannel,
    val label: String,
    val enabled: Boolean,
    val helper: String? = null,
)

private val CHANNEL_OPTIONS = listOf(
    ChannelOption(RescheduleNotificationChannel.push, "Notificación push", enabled = true),
    ChannelOption(RescheduleNotificationChannel.whatsapp, "WhatsApp", enabled = true),
    ChannelOption(RescheduleNotificationChannel.email, "Correo electrónico", enabled = true),
    ChannelOption(RescheduleNotificationChannel.none, "Sin notificación", enabled = true),
)

@Composable
fun RescheduleConfirmSheet(
    reservation: Reservation,
    newStarts: ZonedDateTime,
    newEnds: ZonedDateTime,
    venueZone: ZoneId,
    isSubmitting: Boolean,
    onConfirm: (RescheduleNotificationChannel?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(RescheduleNotificationChannel.push) }
    var message by remember { mutableStateOf("") }
    val customerName = reservation.displayName
    val phone = reservation.displayPhone

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    AvoqadoFullscreenHeader(
                        title = "Reprogramar cita",
                        onNav = onDismiss,
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 4.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AvoqadoTheme.spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).height(AvoqadoTheme.dimensions.buttonLarge),
                                shape = RoundedCornerShape(50),
                                enabled = !isSubmitting,
                            ) { Text("Cancelar", style = MaterialTheme.typography.titleMedium) }
                            PrimaryButton(
                                text = "Reprogramar cita",
                                onClick = { onConfirm(selected, message.ifBlank { null }) },
                                modifier = Modifier.weight(1f),
                                isLoading = isSubmitting,
                                fullWidth = true,
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .widthIn(max = 880.dp)
                        .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
                ) {
                    Text(
                        "¿Confirmas que deseas cambiar la cita para $customerName?",
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    if (phone.isNullOrBlank()) {
                        InfoCard(
                            title = "Sin número de teléfono",
                            body = "No hay un número telefónico guardado para $customerName. " +
                                "No se le enviará comunicación por WhatsApp ni SMS, incluyendo confirmación y recordatorios. " +
                                "Actualiza esta configuración desde el perfil del cliente en el panel web.",
                        )
                    }

                    SlotCard(
                        pillLabel = "Original",
                        pillColor = MaterialTheme.colorScheme.surfaceVariant,
                        pillTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = reservation.displayServiceName ?: "Servicio",
                        datetime = formatSlot(
                            ZonedDateTime.parse(reservation.startsAt).withZoneSameInstant(venueZone),
                        ),
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SlotCard(
                        pillLabel = "Actualizado",
                        pillColor = SUCCESS_GREEN.copy(alpha = 0.15f),
                        pillTextColor = SUCCESS_GREEN,
                        title = reservation.displayServiceName ?: "Servicio",
                        datetime = formatSlot(newStarts),
                        textColor = SUCCESS_GREEN,
                    )

                    Spacer(Modifier.height(AvoqadoTheme.spacing.md))

                    ChannelSelector(
                        selected = selected,
                        onSelect = { selected = it },
                    )

                    OutlinedTextField(
                        value = message,
                        onValueChange = { if (it.length <= 500) message = it },
                        label = { Text("Mensaje de notificación (opcional)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        maxLines = 5,
                        enabled = selected != RescheduleNotificationChannel.none,
                    )
                    Text(
                        "Este mensaje aparecerá en la parte superior de la notificación enviada al cliente.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(AvoqadoTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SlotCard(
    pillLabel: String,
    pillColor: Color,
    pillTextColor: Color,
    title: String,
    datetime: String,
    textColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .background(pillColor, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                pillLabel,
                style = MaterialTheme.typography.labelMedium.copy(color = pillTextColor),
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            datetime,
            style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        )
    }
}

@Composable
private fun ChannelSelector(
    selected: RescheduleNotificationChannel,
    onSelect: (RescheduleNotificationChannel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = CHANNEL_OPTIONS.first { it.value == selected }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Tipo de notificación",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(360.dp),
            ) {
                CHANNEL_OPTIONS.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                                opt.helper?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        enabled = opt.enabled,
                        onClick = {
                            onSelect(opt.value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun formatSlot(zdt: ZonedDateTime): String {
    val day = zdt.format(DateTimeFormatter.ofPattern("EEE, d 'de' MMM 'de' yyyy", Locale("es", "MX")))
    val time = zdt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return "$day, $time"
}
