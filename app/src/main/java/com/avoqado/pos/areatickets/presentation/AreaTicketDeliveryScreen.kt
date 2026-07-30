package com.avoqado.pos.areatickets.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView

@Composable
fun AreaTicketDeliveryScreen(
    onDismiss: () -> Unit,
    viewModel: AreaTicketOperationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refresh(loadPendingDelivery = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Entregas por área", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state.settings?.terminal?.fulfillmentArea?.name ?: "Terminal de entrega",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.deliveryWorkspace) {
                    PrimaryButton(
                        text = "Escanear pagado",
                        onClick = { scanning = true },
                    )
                }
            }

            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                !state.deliveryWorkspace -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AvoqadoTheme.spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Esta terminal no está asignada para entregar vales. Configúrala desde Dashboard → Vales por área.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.pending.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No hay productos pagados pendientes de entrega.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(AvoqadoTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        items(state.pending, key = { it.id }) { ticket ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Vale ${ticket.code}",
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                ticket.lines.joinToString { it.productNameSnapshot },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            "$${ticket.total}",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
                                    PrimaryButton(
                                        text = "Revisé el papel y entregué",
                                        onClick = { viewModel.deliverWithPaper(ticket.id) },
                                        fullWidth = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (scanning) {
            BarcodeScannerView(
                onBarcodeScanned = { code ->
                    scanning = false
                    viewModel.deliverByReceiptCode(code)
                },
                onDismiss = { scanning = false },
            )
        }

        state.message?.let { message ->
            AvoqadoSuccessToast(
                message = message,
                onDismiss = viewModel::dismissFeedback,
            )
        }

        state.error?.let { message ->
            AvoqadoDialog(
                title = "No se pudo completar",
                description = message,
                onDismiss = viewModel::dismissFeedback,
                actionButton = {
                    PrimaryButton(
                        text = "Entendido",
                        onClick = viewModel::dismissFeedback,
                        fullWidth = true,
                    )
                },
            ) {}
        }

        if (state.submitting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
