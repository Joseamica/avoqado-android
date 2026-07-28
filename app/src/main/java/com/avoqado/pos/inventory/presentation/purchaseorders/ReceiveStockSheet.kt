package com.avoqado.pos.inventory.presentation.purchaseorders

import androidx.compose.foundation.background
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.ReceiveItemRequest
import com.avoqado.pos.inventory.data.model.PurchaseOrder
import com.avoqado.pos.inventory.data.model.PurchaseOrderItem
import com.avoqado.pos.inventory.presentation.InventoryViewModel

// MARK: - Receive Stock Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveStockSheet(
    order: PurchaseOrder,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

    // Local state: map of item.id -> received quantity text
    // Default each item's received quantity to the remaining (ordered - already received)
    val invSheetError by viewModel.errorMessage.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.clearErrorMessage() }
    val receivedQuantities = remember {
        mutableStateMapOf<String, String>().apply {
            order.items.forEach { item ->
                val remaining = (item.orderedQuantity - item.receivedQuantity).coerceAtLeast(0)
                put(item.id, remaining.toString())
            }
        }
    }

    if (invSheetError != null) {
        AvoqadoDialog(
            title = "No se pudo completar",
            description = invSheetError ?: "",
            onDismiss = { viewModel.clearErrorMessage() },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { viewModel.clearErrorMessage() })
            },
            content = {},
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Recibir mercancia",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            Text(
                text = order.supplierName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // Table header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(
                        horizontal = AvoqadoTheme.spacing.md,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
            ) {
                Text(
                    text = "Articulo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Pedido",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
                )
                Text(
                    text = "Recibir",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
                )
            }

            // Item rows
            order.items.forEach { item ->
                val maxReceivable = (item.orderedQuantity - item.receivedQuantity).coerceAtLeast(0)
                ReceiveItemRow(
                    item = item,
                    maxReceivable = maxReceivable,
                    receivedText = receivedQuantities[item.id] ?: "0",
                    onReceivedChange = { text ->
                        // Only allow digits
                        val filtered = text.filter { it.isDigit() }
                        val normalized = (filtered.toIntOrNull() ?: 0)
                            .coerceAtMost(maxReceivable)
                            .toString()
                        receivedQuantities[item.id] = normalized
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Confirm button
            PrimaryButton(
                text = if (isSaving) "Enviando..." else "Confirmar recepcion",
                onClick = {
                    if (!order.canReceiveStock) {
                        viewModel.showError("La orden debe estar enviada antes de recibir mercancía")
                        return@PrimaryButton
                    }
                    val receiveItems = order.items.mapNotNull { item ->
                        val maxReceivable = (item.orderedQuantity - item.receivedQuantity).coerceAtLeast(0)
                        val qty = (receivedQuantities[item.id]?.toIntOrNull() ?: 0)
                            .coerceAtMost(maxReceivable)
                        if (qty > 0) {
                            ReceiveItemRequest(
                                purchaseOrderItemId = item.id,
                                receivedQuantity = qty,
                            )
                        } else {
                            null
                        }
                    }
                    if (receiveItems.isNotEmpty()) {
                        viewModel.receivePurchaseOrder(
                            poId = order.id,
                            items = receiveItems,
                            onSuccess = { onDismiss() },
                        )
                    }
                },
                enabled = !isSaving && receivedQuantities.values.any {
                    (it.toIntOrNull() ?: 0) > 0
                } && order.canReceiveStock,
                isLoading = isSaving,
                fullWidth = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Receive Item Row

@Composable
private fun ReceiveItemRow(
    item: PurchaseOrderItem,
    maxReceivable: Int,
    receivedText: String,
    onReceivedChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.md,
                vertical = AvoqadoTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Product name + already received info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName ?: "---",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.receivedQuantity > 0) {
                Text(
                    text = "Ya recibidos: ${item.receivedQuantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Por recibir: $maxReceivable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Ordered quantity
        Text(
            text = "${item.orderedQuantity}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
        )

        // Editable received quantity
        OutlinedTextField(
            value = receivedText,
            onValueChange = onReceivedChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}
