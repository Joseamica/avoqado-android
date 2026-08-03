package com.avoqado.pos.sync.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cuarentena de sincronización — bottom sheet que lista las operaciones offline
 * que el server RECHAZÓ al reconectar, con guía de resolución y "Descartar".
 * Es la superficie que hace VISIBLES los rechazos (antes eran silenciosos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarantineSheet(
    onDismissSheet: () -> Unit,
    viewModel: QuarantineViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val failedPayments by viewModel.failedPayments.collectAsState()
    val failedReservations by viewModel.failedReservationActions.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { viewModel.load() }

    ModalBottomSheet(onDismissRequest = onDismissSheet, sheetState = sheetState) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg).padding(bottom = AvoqadoTheme.spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Warning, modifier = Modifier.size(24.dp))
                Text(
                "Conciliación sin conexión",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                "Revisa operaciones rechazadas y cobros que no pudieron confirmarse. Avoqado conserva sus datos hasta que un gerente los resuelva.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))

            if (items.isEmpty() && failedPayments.isEmpty() && failedReservations.isEmpty()) {
                Text(
                    "No hay operaciones pendientes de revisión.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.lg),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    if (failedPayments.isNotEmpty()) {
                        item {
                            Text(
                                "Cobros sin confirmar",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    items(failedPayments, key = { "payment-${it.id}" }) { payment ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AvoqadoTheme.spacing.md))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(AvoqadoTheme.spacing.md),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
                        ) {
                            Text(
                                "${payment.method} · ${formatMoney(payment.amountCents)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            payment.orderNumber?.let {
                                Text("Orden $it", style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                viewModel.paymentResolutionHint(payment),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTime(payment.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (viewModel.canResolve) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    OutlinedButton(onClick = { viewModel.retryPayment(payment.id) }) {
                                        Text("Reintentar")
                                    }
                                }
                            }
                        }
                    }
                    if (failedReservations.isNotEmpty()) {
                        item {
                            Text(
                                "Reservas sin sincronizar",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
                            )
                        }
                    }
                    items(failedReservations, key = { "reservation-${it.rowId}" }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AvoqadoTheme.spacing.md))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(AvoqadoTheme.spacing.md),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
                        ) {
                            Text(
                                viewModel.describeReservationAction(entry.action),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                viewModel.reservationResolutionHint(entry),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTime(entry.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (viewModel.canResolve) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    OutlinedButton(onClick = { viewModel.dismissReservationAction(entry.rowId) }) {
                                        Text("Marcar resuelta")
                                    }
                                }
                            }
                        }
                    }
                    if (items.isNotEmpty()) {
                        item {
                            Text(
                                "Operaciones rechazadas",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
                            )
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AvoqadoTheme.spacing.md))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(AvoqadoTheme.spacing.md),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
                        ) {
                            Text(
                                viewModel.describeType(item.type),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            item.message?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                viewModel.resolutionHint(item.errorCode, item.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTime(item.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (viewModel.canResolve) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    OutlinedButton(onClick = { viewModel.dismiss(item.id) }) {
                                        Text("Marcar resuelta")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!viewModel.canResolve && (items.isNotEmpty() || failedPayments.isNotEmpty())) {
                Spacer(Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    "Pide a un gerente que revise y resuelva estas operaciones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            HorizontalDivider()
        }
    }

    successMessage?.let {
        AvoqadoSuccessToast(message = it, onDismiss = viewModel::consumeSuccess)
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale("es", "MX")).format(Date(epochMs))

private fun formatMoney(cents: Int): String =
    NumberFormat.getCurrencyInstance(Locale("es", "MX")).format(cents / 100.0)

/**
 * Banner persistente (visible AUNQUE haya conexión) cuando hay operaciones
 * rechazadas en cuarentena. Tocarlo abre la hoja de resolución. Ámbar, no rojo:
 * requiere atención pero nada se perdió.
 */
@Composable
fun QuarantineBanner(count: Int, onClick: () -> Unit) {
    if (count <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Warning)
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(AvoqadoTheme.spacing.sm))
        Text(
            text = if (count == 1) "1 operación necesita revisión — toca para resolver" else "$count operaciones necesitan revisión — toca para resolver",
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
