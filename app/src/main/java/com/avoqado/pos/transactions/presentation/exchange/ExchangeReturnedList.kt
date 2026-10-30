package com.avoqado.pos.transactions.presentation.exchange

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/** Lives in the cart's single scroll container; no nested list with unbounded height. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExchangeReturnedList(state: ExchangeUiState, viewModel: ExchangeViewModel) {
    Text(TextosCambio.REGRESA, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.returned.forEach { row ->
        Column(Modifier.fillMaxWidth().alpha(if (row.blockedReason == null) 1f else 0.4f)
            .padding(vertical = AvoqadoTheme.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.quantity > 0, enabled = row.blockedReason == null,
                    onCheckedChange = { row.item.id?.let(viewModel::toggleReturned) })
                Text(row.item.productName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            row.blockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.quantity > 0) {
                Text(exchangeMoney(-viewModel.returnedValue(row)), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.setReturnedQuantity(row.item.id!!, row.quantity - 1) },
                        enabled = row.quantity > 1 && row.item.isPromotionLine != true) {
                        Icon(Icons.Filled.Remove, TextosCambio.DISMINUIR_CANTIDAD)
                    }
                    Text(row.quantity.toString(), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { viewModel.setReturnedQuantity(row.item.id!!, row.quantity + 1) },
                        enabled = row.quantity < (row.item.remainingQtyOrder ?: 0) && row.item.isPromotionLine != true) {
                        Icon(Icons.Filled.Add, TextosCambio.AUMENTAR_CANTIDAD)
                    }
                }
                if (row.item.restockable == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.restock, onCheckedChange = { viewModel.setRestock(row.item.id!!, it) })
                        Text(TextosCambio.VUELVE_AL_INVENTARIO, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(TextosCambio.MOTIVO, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
                    ExchangeReason.entries.forEach { reason ->
                        FilterChip(selected = row.reason == reason, onClick = { viewModel.setReason(row.item.id!!, reason) },
                            label = { Text(reason.label) })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
