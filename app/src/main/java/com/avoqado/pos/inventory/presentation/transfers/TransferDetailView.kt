package com.avoqado.pos.inventory.presentation.transfers

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.inventory.data.model.InventoryTransfer
import com.avoqado.pos.inventory.data.model.TransferItem
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import com.avoqado.pos.inventory.presentation.purchaseorders.StatusBadge

// MARK: - Transfer Detail

@Composable
fun TransferDetailView(
    transfer: InventoryTransfer,
    viewModel: InventoryViewModel,
    onBack: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()

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
                    text = "Detalle de transferencia",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(status = transfer.status, label = transfer.statusDisplay)
                    Text(
                        text = "por ${transfer.createdByName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Info cards (From / To / Items)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            InfoCard(
                label = "Origen",
                value = transfer.fromLocationName,
                modifier = Modifier.weight(1f),
            )
            InfoCard(
                label = "Destino",
                value = transfer.toLocationName,
                modifier = Modifier.weight(1f),
            )
            InfoCard(
                label = "Artículos",
                value = "${transfer.totalItems}",
                modifier = Modifier.weight(1f),
            )
        }

        // Notes (if any)
        transfer.notes?.let { notes ->
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
                text = "Cantidad",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
            )
            Text(
                text = "Unidad",
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
            items(transfer.items, key = { it.id }) { item ->
                TransferItemRow(item = item)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // Action buttons based on status
        if (transfer.status == "DRAFT" || transfer.status == "IN_TRANSIT") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                // Cancel button
                OutlinedButton(
                    onClick = {
                        viewModel.updateTransferStatus(transfer.id, "CANCELLED") {
                            onBack()
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Error,
                    ),
                ) {
                    Text(
                        text = "Cancelar",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (transfer.status == "DRAFT") {
                    // Send (mark as in transit)
                    PrimaryButton(
                        text = "Enviar",
                        onClick = {
                            viewModel.updateTransferStatus(transfer.id, "IN_TRANSIT") {
                                onBack()
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                } else if (transfer.status == "IN_TRANSIT") {
                    // Complete
                    PrimaryButton(
                        text = "Completar",
                        onClick = {
                            viewModel.updateTransferStatus(transfer.id, "COMPLETED") {
                                onBack()
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// MARK: - Transfer Item Row

@Composable
private fun TransferItemRow(item: TransferItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.productName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${item.quantity}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(AvoqadoTheme.spacing.xxxl * 2),
        )
        Text(
            text = item.unit ?: "—",
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
