package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancelReservationSheet(
    onDismiss: () -> Unit,
    viewModel: ReservationDetailViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reason by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.justCompletedAction) {
        if (state.justCompletedAction == ReservationAction.CANCEL) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text("Cancelar reserva", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Esta acción no se puede deshacer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.padding(8.dp))
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Motivo (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            )
            // A failed cancel used to be invisible: state.error was set on this
            // sheet's VM instance but never rendered — the button just reverted.
            state.error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.padding(12.dp))
            Button(
                onClick = {
                    viewModel.runAction(
                        ReservationAction.CANCEL,
                        ReservationRepository.ActionPayload.Cancel(reason.takeIf { it.isNotBlank() }),
                    )
                },
                enabled = state.pendingAction != ReservationAction.CANCEL,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    if (state.pendingAction == ReservationAction.CANCEL) "Cancelando..." else "Confirmar cancelación",
                )
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}
