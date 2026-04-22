package com.avoqado.pos.inventory.presentation.purchaseorders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Info
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.model.PurchaseOrder
import com.avoqado.pos.inventory.presentation.InventoryViewModel

// MARK: - Purchase Orders List

@Composable
fun PurchaseOrdersView(viewModel: InventoryViewModel) {
    val purchaseOrders by viewModel.purchaseOrders.collectAsState()
    val searchQuery by viewModel.poSearchQuery.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }

    val filteredOrders = if (searchQuery.isBlank()) {
        purchaseOrders
    } else {
        val query = searchQuery.lowercase()
        purchaseOrders.filter {
            it.supplierName.lowercase().contains(query) ||
                it.statusDisplay.lowercase().contains(query) ||
                it.createdByName.lowercase().contains(query)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar + Add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            // Search bar (pill style)
            SearchPillField(
                query = searchQuery,
                onQueryChange = { viewModel.updatePOSearch(it) },
                placeholder = "Buscar por proveedor",
                modifier = Modifier.weight(1f),
            )

            // Add button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { showCreateSheet = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Crear orden",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        if (filteredOrders.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.LocalShipping,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    Text(
                        text = "Sin órdenes de compra",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        text = if (searchQuery.isNotEmpty()) {
                            "Intenta con otro término de búsqueda"
                        } else {
                            "Crea tu primera orden de compra para gestionar pedidos a proveedores"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchQuery.isEmpty()) {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                        PrimaryButton(
                            text = "Crear orden de compra",
                            onClick = { showCreateSheet = true },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(filteredOrders, key = { it.id }) { order ->
                    PurchaseOrderRow(
                        order = order,
                        onClick = { viewModel.selectPurchaseOrder(order) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    // Create sheet
    if (showCreateSheet) {
        CreatePurchaseOrderSheet(
            viewModel = viewModel,
            onDismiss = { showCreateSheet = false },
        )
    }
}

// MARK: - Purchase Order Row

@Composable
private fun PurchaseOrderRow(
    order: PurchaseOrder,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon with colored background
        val (iconBg, iconTint) = statusColors(order.status)

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocalShipping,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.supplierName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(status = order.status, label = order.statusDisplay)
                Text(
                    text = "${order.items.size} artículos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Created by
        Text(
            text = order.createdByName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Status Badge (colored pill)

@Composable
fun StatusBadge(
    status: String,
    label: String,
) {
    val (bgColor, textColor) = statusBadgeColors(status)

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
    )
}

// MARK: - Color Helpers

@Composable
private fun statusColors(status: String): Pair<Color, Color> {
    return when (status) {
        "RECEIVED", "COMPLETED" -> Success.copy(alpha = 0.15f) to Success
        "SENT", "IN_TRANSIT" -> Info.copy(alpha = 0.15f) to Info
        "PARTIALLY_RECEIVED" -> Warning.copy(alpha = 0.15f) to Warning
        "CANCELLED" -> Error.copy(alpha = 0.15f) to Error
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant // DRAFT
    }
}

@Composable
fun statusBadgeColors(status: String): Pair<Color, Color> {
    return when (status) {
        "RECEIVED", "COMPLETED" -> Success.copy(alpha = 0.15f) to Success
        "SENT", "IN_TRANSIT" -> Info.copy(alpha = 0.15f) to Info
        "PARTIALLY_RECEIVED" -> Warning.copy(alpha = 0.15f) to Warning
        "CANCELLED" -> Error.copy(alpha = 0.15f) to Error
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant // DRAFT
    }
}
