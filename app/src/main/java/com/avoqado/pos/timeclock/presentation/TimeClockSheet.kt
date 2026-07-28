package com.avoqado.pos.timeclock.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import com.avoqado.pos.timeclock.data.model.StaffData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// MARK: - Screen state machine (matching iOS: 3 screens)

private enum class TimeClockScreen {
    PIN_ENTRY,
    IDENTIFIED,
    BREAK_SELECTION,
}

// MARK: - Break type (matching iOS: 3 predefined break types)

private data class BreakType(
    val id: String,
    val name: String,
    val duration: String,
)

private val defaultBreakTypes = listOf(
    BreakType("meal", "Descanso para comer", "30 min"),
    BreakType("rest", "Descanso", "20 min"),
    BreakType("quick", "Descanso rápido", "10 min"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeClockSheet(
    repository: TimeEntryRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var staffData by remember { mutableStateOf<StaffData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(TimeClockScreen.PIN_ENTRY) }
    var note by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val compactSheetLayout = adaptive.isAggressiveCompact ||
        (adaptive.sizeClass == AvoqadoAdaptiveSizeClass.Compact && configuration.screenHeightDp < 860) ||
        configuration.screenHeightDp < 740
    val clockFontSize = if (compactSheetLayout) 52.sp else 64.sp

    // Live clock in venue timezone (matching iOS: ticks every second)
    val zoneId = remember { com.avoqado.pos.core.util.VenueTimeZone.zoneId() }
    var currentTime by remember { mutableStateOf(LocalTime.now(zoneId)) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now(zoneId)
            delay(1000)
        }
    }
    val timeDisplay = currentTime.format(DateTimeFormatter.ofPattern("HH:mm"))

    ModalBottomSheet(
        onDismissRequest = {
            pin = ""
            staffData = null
            error = null
            note = ""
            currentScreen = TimeClockScreen.PIN_ENTRY
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = if (compactSheetLayout) {
                        AvoqadoTheme.spacing.md
                    } else {
                        AvoqadoTheme.spacing.lg
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Live clock display (matching iOS: 72pt font)
            Text(
                text = timeDisplay,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = clockFontSize,
                    fontWeight = FontWeight.Light,
                ),
            )

            Spacer(modifier = Modifier.height(if (compactSheetLayout) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg))

            when (currentScreen) {
                TimeClockScreen.PIN_ENTRY -> {
                    // MARK: - PIN Entry Screen

                    Text(
                        text = "Reloj de entrada",
                        style = MaterialTheme.typography.headlineMedium,
                    )

                    Spacer(modifier = Modifier.height(if (compactSheetLayout) AvoqadoTheme.spacing.lg else AvoqadoTheme.spacing.xxl))

                    PinPadView(
                        pin = pin,
                        onPinChange = { pin = it },
                        maxLength = 10,
                        minLength = 4,
                        compact = compactSheetLayout,
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))

                    Text(
                        text = "${pin.length} dígitos (mínimo 4)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    error?.let {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                    PrimaryButton(
                        text = "Identificar",
                        onClick = {
                            scope.launch {
                                isLoading = true
                                error = null
                                repository.identifyStaff(pin).fold(
                                    onSuccess = {
                                        staffData = it
                                        currentScreen = TimeClockScreen.IDENTIFIED
                                    },
                                    onFailure = { error = it.message ?: "PIN incorrecto" },
                                )
                                isLoading = false
                            }
                        },
                        enabled = pin.length in 4..10,
                        isLoading = isLoading,
                        fullWidth = true,
                    )
                }

                TimeClockScreen.IDENTIFIED -> {
                    // MARK: - Identified Screen (matching iOS: name + status + actions)

                    val staff = staffData!!

                    Text(
                        text = "Hola, ${staff.name}",
                        style = MaterialTheme.typography.headlineMedium,
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                    // Status indicator (matching iOS: green/orange dot + label)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    ) {
                        // El chip tiene que mirar PRIMERO si marcó entrada: antes sólo
                        // miraba onBreak, así que a quien apenas llega —sin registro
                        // alguno— lo saludaba con un punto verde y "Trabajando", justo
                        // encima del botón "Entrada". Un empleado que confía en eso se
                        // va sin marcar y pierde su turno del día.
                        val statusColor = when {
                            !staff.clockedIn -> MaterialTheme.colorScheme.onSurfaceVariant
                            staff.onBreak -> Warning
                            else -> Success
                        }
                        // Mismo texto que iOS, que ya lo hacía bien.
                        val statusText = when {
                            !staff.clockedIn -> "No tienes entrada registrada"
                            staff.onBreak -> "En descanso"
                            else -> "Trabajando"
                        }

                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(statusColor, CircleShape),
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // Desde qué hora lleva trabajando, como en iOS. Sin esto el
                    // empleado no tiene forma de notar una entrada de ayer que quedó
                    // abierta, y descubre el problema hasta que le pagan mal.
                    staff.clockInTime?.takeIf { staff.clockedIn }?.let { iso ->
                        formatClockInTime(iso)?.let { hora ->
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                            Text(
                                text = "Entrada: $hora",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Role display
                    staff.role?.let { role ->
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                        Text(
                            text = role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    }

                    // Optional note field
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = {
                            Text(
                                "Agregar nota (opcional)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                    // Clock actions (matching iOS)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        if (!staff.clockedIn) {
                            PrimaryButton(
                                text = "Entrada",
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        error = null
                                        repository.clockIn(pin, note.ifBlank { null }).fold(
                                            onSuccess = { onDismiss() },
                                            onFailure = { error = it.message },
                                        )
                                        isLoading = false
                                    }
                                },
                                isLoading = isLoading,
                                fullWidth = true,
                            )
                        } else {
                            if (!staff.onBreak) {
                                PrimaryButton(
                                    text = "Iniciar descanso",
                                    onClick = {
                                        currentScreen = TimeClockScreen.BREAK_SELECTION
                                    },
                                    fullWidth = true,
                                )
                            } else {
                                PrimaryButton(
                                    text = "Terminar descanso",
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            error = null
                                            repository.endBreak(pin, note.ifBlank { null }).fold(
                                                onSuccess = { onDismiss() },
                                                onFailure = { error = it.message },
                                            )
                                            isLoading = false
                                        }
                                    },
                                    isLoading = isLoading,
                                    fullWidth = true,
                                )
                            }
                            PrimaryButton(
                                text = "Salida",
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        error = null
                                        repository.clockOut(pin, note.ifBlank { null }).fold(
                                            onSuccess = { onDismiss() },
                                            onFailure = { error = it.message },
                                        )
                                        isLoading = false
                                    }
                                },
                                isLoading = isLoading,
                                fullWidth = true,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

                    // "Cambiar usuario" button (matching iOS)
                    TextButton(onClick = {
                        pin = ""
                        staffData = null
                        error = null
                        note = ""
                        currentScreen = TimeClockScreen.PIN_ENTRY
                    }) {
                        Text("Cambiar usuario")
                    }
                }

                TimeClockScreen.BREAK_SELECTION -> {
                    // MARK: - Break Selection Screen (matching iOS: 3 break types)

                    Text(
                        text = "Tipo de descanso",
                        style = MaterialTheme.typography.headlineMedium,
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        defaultBreakTypes.forEach { breakType ->
                            PrimaryButton(
                                text = "${breakType.duration} ${breakType.name}",
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        error = null
                                        repository.startBreak(pin, breakType.id).fold(
                                            onSuccess = { onDismiss() },
                                            onFailure = { error = it.message },
                                        )
                                        isLoading = false
                                    }
                                },
                                isLoading = isLoading,
                                fullWidth = true,
                            )
                        }
                    }

                    error?.let {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

                    TextButton(onClick = {
                        currentScreen = TimeClockScreen.IDENTIFIED
                    }) {
                        Text("Cancelar")
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (compactSheetLayout) AvoqadoTheme.spacing.lg else AvoqadoTheme.spacing.xxxl))
        }
    }
}

/**
 * "2026-07-28T21:39:00.000Z" → "15:39" en la zona del local.
 *
 * Devuelve null si el server manda algo que no se puede leer: es preferible no
 * pintar la hora a pintar una equivocada en un dato que decide cuánto se le
 * paga a alguien.
 */
private fun formatClockInTime(iso: String): String? = try {
    java.time.Instant.parse(iso)
        .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: Exception) {
    null
}
