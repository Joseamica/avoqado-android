package com.avoqado.pos.inventory.presentation.transfers

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
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.avoqado.pos.inventory.data.model.InventoryTransfer
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import com.avoqado.pos.inventory.presentation.purchaseorders.StatusBadge

// MARK: - Transfers List

@Composable
fun TransfersView(viewModel: InventoryViewModel) {
    val transfers by viewModel.transfers.collectAsState()
    val searchQuery by viewModel.transferSearchQuery.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }

    val filteredTransfers = if (searchQuery.isBlank()) {
        transfers
    } else {
        val query = searchQuery.lowercase()
        transfers.filter {
            it.fromLocationName.lowercase().contains(query) ||
                it.toLocationName.lowercase().contains(query) ||
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
                onQueryChange = { viewModel.updateTransferSearch(it) },
                placeholder = "Buscar por ubicación",
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
                    contentDescription = "Crear transferencia",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        if (filteredTransfers.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    Text(
                        text = "Sin transferencias",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        text = if (searchQuery.isNotEmpty()) {
                            "Intenta con otro término de búsqueda"
                        } else {
                            "Crea tu primera transferencia para mover inventario entre ubicaciones"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchQuery.isEmpty()) {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                        PrimaryButton(
                            text = "Crear transferencia",
                            onClick = { showCreateSheet = true },
                            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xxxl),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(filteredTransfers, key = { it.id }) { transfer ->
                    TransferRow(
                        transfer = transfer,
                        onClick = { viewModel.selectTransfer(transfer) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    // Create sheet
    if (showCreateSheet) {
        CreateTransferSheet(
            viewModel = viewModel,
            onDismiss = { showCreateSheet = false },
        )
    }
}

// MARK: - Transfer Row

@Composable
private fun TransferRow(
    transfer: InventoryTransfer,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        val (iconBg, iconTint) = transferStatusColors(transfer.status)

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            // From -> To
            Text(
                text = "${transfer.fromLocationName} → ${transfer.toLocationName}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(status = transfer.status, label = transfer.statusDisplay)
                Text(
                    text = "${transfer.items.size} artículos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Created by
        Text(
            text = transfer.createdByName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Color Helpers

@Composable
private fun transferStatusColors(status: String): Pair<Color, Color> {
    return when (status) {
        "COMPLETED" -> Success.copy(alpha = 0.15f) to Success
        "IN_TRANSIT" -> Info.copy(alpha = 0.15f) to Info
        "CANCELLED" -> Error.copy(alpha = 0.15f) to Error
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant // DRAFT
    }
}
