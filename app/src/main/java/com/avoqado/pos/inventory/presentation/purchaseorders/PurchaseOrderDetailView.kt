package com.avoqado.pos.inventory.presentation.purchaseorders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.inventory.data.model.PurchaseOrder
import com.avoqado.pos.inventory.data.model.PurchaseOrderItem
import com.avoqado.pos.inventory.presentation.InventoryViewModel

// MARK: - Purchase Order Detail

@Composable
fun PurchaseOrderDetailView(
    order: PurchaseOrder,
    viewModel: InventoryViewModel,
    onBack: () -> Unit,
) {
    var showReceiveSheet by remember { mutableStateOf(false) }
    val isSaving by viewModel.isSaving.collectAsState()

    // Receive stock bottom sheet
    if (showReceiveSheet) {
        ReceiveStockSheet(
            order = order,
            viewModel = viewModel,
            onDismiss = { showReceiveSheet = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.supplierName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(status = order.status, label = order.statusDisplay)
                    // Sin nombre no se pinta: la palabra "por" colgando sola se lee
                    // como un texto a medio cargar. Visto en pantalla sobre una
                    // orden cuyo `createdById` venía vacío del server.
                    if (order.createdByName.isNotBlank()) {
                    Text(
                        text = "por ${order.createdByName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Info cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            InfoCard(
                label = "Artículos",
                value = "${order.items.size}",
                modifier = Modifier.weight(1f),
            )
            InfoCard(
                label = "Pedidos",
                value = "${order.totalItems}",
                modifier = Modifier.weight(1f),
            )
            InfoCard(
                label = "Recibidos",
                value = "${order.totalReceived}",
                modifier = Modifier.weight(1f),
            )
        }

        // Notes (if any)
        order.notes?.let { notes ->
            if (notes.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.lg),
                ) {
                    Text(
                        text = "Notas",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                }
            }
        }

        // Items table header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                ),
        ) {
            Text(
                text = "Artículo",
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
                text = "Recibido",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
            )
            Text(
                text = "Costo",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
            )
        }

        // Items list
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
        ) {
            items(order.items, key = { it.id }) { item ->
                PurchaseOrderItemRow(item = item)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // Action buttons (parity with iOS flow states)
        if (order.canBeSent || order.canReceiveStock) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                when {
                    order.canBeSent -> {
                        PrimaryButton(
                            text = "Enviar orden",
                            onClick = {
                                viewModel.updatePurchaseOrderStatus(
                                    poId = order.id,
                                    status = "SENT",
                                )
                            },
                            isLoading = isSaving,
                        )
                    }
                    order.canReceiveStock -> {
                        PrimaryButton(
                            text = "Recibir mercancía",
                            onClick = { showReceiveSheet = true },
                            isLoading = isSaving,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Purchase Order Item Row

@Composable
private fun PurchaseOrderItemRow(item: PurchaseOrderItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.productName ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${item.orderedQuantity}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
        )
        Text(
            text = "${item.receivedQuantity}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.isComplete) FontWeight.SemiBold else FontWeight.Normal,
            color = if (item.isComplete) Success else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
        )
        Text(
            text = item.displayCost,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
        )
    }
}

// MARK: - Info Card

@Composable
private fun InfoCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AvoqadoTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
