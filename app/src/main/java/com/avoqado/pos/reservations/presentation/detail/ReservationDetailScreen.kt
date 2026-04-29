package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.presentation.components.ReservationStatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationDetailScreen(
    onClose: () -> Unit,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    formatter: VenueDateTimeFormatter,
    viewModel: ReservationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var successLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it); viewModel.consumeError() } }
    LaunchedEffect(state.justCompletedAction) {
        successLabel = state.justCompletedAction?.let { successCopy(it) }
        if (successLabel != null) viewModel.consumeJustCompleted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.reservation?.confirmationCode ?: "") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            state.reservation?.let {
                ActionBar(
                    state = state,
                    onConfirm = { viewModel.runAction(ReservationAction.CONFIRM) },
                    onCheckIn = { viewModel.runAction(ReservationAction.CHECK_IN) },
                    onComplete = { viewModel.runAction(ReservationAction.COMPLETE) },
                    onNoShow = { viewModel.runAction(ReservationAction.NO_SHOW) },
                    onReschedule = onReschedule,
                    onCancel = onCancel,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.reservation == null -> Text("No se pudo cargar la reserva", Modifier.align(Alignment.Center))
                else -> {
                    val r = state.reservation!!
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .widthIn(max = 880.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(r.displayName, style = MaterialTheme.typography.headlineSmall)
                        ReservationStatusBadge(r.status)
                        InfoSection("Cita") {
                            InfoRow("Fecha", formatter.formatDate(r.startsAt))
                            InfoRow("Hora", "${formatter.formatTime(r.startsAt)} – ${formatter.formatTime(r.endsAt)}")
                            InfoRow("Duración", "${r.duration} min")
                            InfoRow("Personas", r.partySize.toString())
                            InfoRow("Servicio", r.displayServiceName)
                            InfoRow("Mesa", r.table?.let { "Mesa ${it.number}" })
                            InfoRow("Personal", r.assignedStaff?.displayName)
                        }
                        InfoSection("Cliente") {
                            InfoRow("Teléfono", r.displayPhone)
                            InfoRow("Email", r.customer?.email ?: r.guestEmail)
                            InfoRow("Canal", r.channel.displayLabel)
                        }
                        if (!r.specialRequests.isNullOrBlank() || !r.internalNotes.isNullOrBlank()) {
                            InfoSection("Notas") {
                                InfoRow("Solicitudes", r.specialRequests)
                                InfoRow("Internas", r.internalNotes)
                            }
                        }
                        if (r.depositAmount != null) {
                            InfoSection("Depósito") {
                                InfoRow("Monto", r.depositAmount)
                                InfoRow("Estado", r.depositStatus?.name)
                            }
                        }
                    }
                }
            }
        }
    }

    successLabel?.let { label ->
        AvoqadoSuccessToast(message = label, onDismiss = { successLabel = null })
    }
}

private fun successCopy(action: ReservationAction): String = when (action) {
    ReservationAction.CONFIRM -> "¡Reserva confirmada!"
    ReservationAction.CHECK_IN -> "¡Cliente registrado!"
    ReservationAction.COMPLETE -> "¡Reserva completada!"
    ReservationAction.NO_SHOW -> "Marcada como no-show"
    ReservationAction.CANCEL -> "Reserva cancelada"
    ReservationAction.RESCHEDULE -> "¡Reserva reagendada!"
    ReservationAction.CREATE -> "¡Reserva creada!"
    ReservationAction.UPDATE -> "¡Reserva actualizada!"
}

@Composable
private fun ActionBar(
    state: ReservationDetailUiState,
    onConfirm: () -> Unit,
    onCheckIn: () -> Unit,
    onComplete: () -> Unit,
    onNoShow: () -> Unit,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pending = state.pendingAction
            ActionPill("Confirmar", state.isAllowed(ReservationAction.CONFIRM), pending == ReservationAction.CONFIRM, onConfirm, Modifier.weight(1f))
            ActionPill("Check-in", state.isAllowed(ReservationAction.CHECK_IN), pending == ReservationAction.CHECK_IN, onCheckIn, Modifier.weight(1f))
            ActionPill("Completar", state.isAllowed(ReservationAction.COMPLETE), pending == ReservationAction.COMPLETE, onComplete, Modifier.weight(1f))
            ActionPill("No-show", state.isAllowed(ReservationAction.NO_SHOW), pending == ReservationAction.NO_SHOW, onNoShow, Modifier.weight(1f))
            ActionPill("Reagendar", state.isAllowed(ReservationAction.RESCHEDULE), false, onReschedule, Modifier.weight(1f))
            ActionPill("Cancelar", state.isAllowed(ReservationAction.CANCEL), false, onCancel, Modifier.weight(1f), destructive = true)
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
        colors = if (destructive) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
        shape = RoundedCornerShape(50),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(label, maxLines = 1)
        }
    }
}
