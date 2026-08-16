package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.ActionSheetCenter
import com.avoqado.pos.reservations.presentation.components.ActionSheetItem
import com.avoqado.pos.reservations.presentation.detail.RescheduleConfirmSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarTabHost(
    onOpenReservation: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCreateReservation: (LocalDate, LocalTime, Boolean) -> Unit = { _, _, _ -> },
    onCreateClassSession: (LocalDate, LocalTime) -> Unit = { _, _ -> },
    onOpenClassSession: (String) -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val venueZone = remember(viewModel) { viewModel.venueZoneId }
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pending by viewModel.pendingActionsCount.collectAsStateWithLifecycle()

    var pendingSlot by remember { mutableStateOf<Pair<LocalDate, LocalTime>?>(null) }
    var showSheetForNow by remember { mutableStateOf(false) }
    var pendingReschedule by remember {
        mutableStateOf<Triple<Reservation, ZonedDateTime, ZonedDateTime>?>(null)
    }
    var rescheduleSuccessLabel by remember { mutableStateOf<String?>(null) }
    val rescheduleSubmitting by viewModel.rescheduleSubmitting.collectAsStateWithLifecycle()
    val rescheduleOverCapacityConfirmation by
        viewModel.rescheduleOverCapacityConfirmation.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Volver a la pestaña no es pedir nada: es la app poniéndose al día.
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(background = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isOnline) {
        while (isOnline) {
            delay(30_000)
            // Tick automático: si el server dice 403 se anota en la pantalla, pero
            // jamás salta un modal sobre el mesero cada 30 segundos.
            viewModel.refresh(showLoading = false, background = true)
        }
    }

    fun openSheetAtSlot(date: LocalDate, time: LocalTime) {
        pendingSlot = date to time
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.selectedDate.format(
                            DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es"))
                        ).replaceFirstChar { it.uppercase() },
                    )
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isLoading,
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, "Actualizar")
                        }
                    }
                    IconButton(onClick = { showSheetForNow = true }) {
                        Icon(Icons.Filled.Add, "Crear")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.MoreHoriz, "Ajustes")
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isOnline) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = if (pending > 0) "Sin conexión — $pending acciones pendientes" else "Sin conexión",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            val openReschedule: (Reservation, ZonedDateTime, ZonedDateTime) -> Unit = { r, s, e ->
                pendingReschedule = Triple(r, s, e)
            }
            when (state.view) {
                CalendarView.DAY -> CalendarDayView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                    onClassSessionClick = { onOpenClassSession(it.id) },
                    onSlotTap = ::openSheetAtSlot,
                    onReservationReschedule = openReschedule,
                )
                CalendarView.WEEK -> CalendarWeekView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                    onClassSessionClick = { onOpenClassSession(it.id) },
                    onSlotTap = ::openSheetAtSlot,
                    onReservationReschedule = openReschedule,
                )
            }
        }
    }

    pendingReschedule?.let { (reservation, newStarts, newEnds) ->
        RescheduleConfirmSheet(
            reservation = reservation,
            newStarts = newStarts,
            newEnds = newEnds,
            venueZone = venueZone,
            isSubmitting = rescheduleSubmitting,
            onConfirm = { channel, message ->
                viewModel.reschedule(
                    reservationId = reservation.id,
                    newStarts = newStarts,
                    newEnds = newEnds,
                    channel = channel,
                    customMessage = message,
                ) { success, error ->
                    pendingReschedule = null
                    if (success) {
                        rescheduleSuccessLabel = "¡Reserva reagendada!"
                    } else if (error != null) {
                        scope.launch { snackbar.showSnackbar(error) }
                    }
                }
            },
            onDismiss = { if (!rescheduleSubmitting) pendingReschedule = null },
        )
    }

    if (rescheduleOverCapacityConfirmation != null) {
        AvoqadoDialog(
            title = "Horario lleno",
            description = rescheduleOverCapacityConfirmation,
            onDismiss = viewModel::dismissRescheduleOverCapacity,
            actionButton = {
                PrimaryButton(
                    text = "Sobre-agendar",
                    onClick = viewModel::confirmRescheduleOverCapacity,
                    isLoading = rescheduleSubmitting,
                )
            },
            content = {
                Text(
                    text = "Los conflictos personales del profesionista no se pueden omitir.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    rescheduleSuccessLabel?.let { label ->
        AvoqadoSuccessToast(message = label, onDismiss = { rescheduleSuccessLabel = null })
    }

    val sheetSlot = pendingSlot
    if (sheetSlot != null || showSheetForNow) {
        val target = sheetSlot
            ?: (state.selectedDate to LocalTime.now(venueZone))
        ActionSheetCenter(
            onDismiss = {
                pendingSlot = null
                showSheetForNow = false
            },
            actions = listOf(
                ActionSheetItem(
                    label = "Crear cita",
                    onClick = {
                        onCreateReservation(target.first, target.second, false)
                        pendingSlot = null
                        showSheetForNow = false
                    },
                ),
                ActionSheetItem(
                    label = "Registrar walk-in",
                    onClick = {
                        pendingSlot = null
                        showSheetForNow = false
                        onCreateReservation(LocalDate.now(venueZone), LocalTime.now(venueZone), true)
                    },
                ),
                ActionSheetItem(
                    label = "Crear clase",
                    onClick = {
                        onCreateClassSession(target.first, target.second)
                        pendingSlot = null
                        showSheetForNow = false
                    },
                ),
                ActionSheetItem(
                    label = "Crear evento personal",
                    onClick = { showComingSoon(scope, snackbar, "Crear evento personal", 5) },
                ),
                ActionSheetItem(
                    label = "Cancelar",
                    onClick = {},
                    destructive = true,
                ),
            ),
        )
    }
}

private fun showComingSoon(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    action: String,
    phase: Int,
) {
    scope.launch {
        snackbar.showSnackbar("$action — disponible en Fase $phase")
    }
}
