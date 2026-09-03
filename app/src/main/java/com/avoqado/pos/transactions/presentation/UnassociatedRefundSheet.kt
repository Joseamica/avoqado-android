package com.avoqado.pos.transactions.presentation

// ============================================================================
// TODO [HIGH RISK - DISABLED IN UI]
// ============================================================================
// This sheet is reachable through the "Reembolso desasociado" / "Devolver o
// cambiar" buttons. Both buttons are currently HIDDEN because creating a
// refund corrupts financial reports. The sheet code itself is kept so that
// re-enabling is just a matter of restoring the button.
//
// See RefundRepository.kt and TransactionsScreen.kt for the full risk
// breakdown (5 server bugs + missing inventory restoration).
// ============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.RefundItem

// MARK: - Unassociated Refund Bottom Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnassociatedRefundSheet(
    viewModel: TransactionsViewModel,
    onDismiss: () -> Unit,
) {
    val refundState by viewModel.refundState.collectAsState()

    ModalBottomSheet(
        onDismissRequest = {
            if (refundState !is RefundUiState.Loading) {
                viewModel.resetRefundState()
                onDismiss()
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        UnassociatedRefundContent(
            refundState = refundState,
            onProcess = { amount, reason, items ->
                viewModel.processUnassociatedRefund(amount, reason, items)
            },
            onDismiss = {
                viewModel.resetRefundState()
                onDismiss()
            },
        )
    }
}

// MARK: - Sheet Content

@Composable
private fun UnassociatedRefundContent(
    refundState: RefundUiState,
    onProcess: (amount: String, reason: String, items: List<RefundItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf("") }
    val refundItems = remember { mutableStateListOf<RefundItem>() }

    // Inline add-item fields
    var itemName by remember { mutableStateOf("") }
    var itemQty by remember { mutableStateOf("1") }
    var showAddItem by remember { mutableStateOf(false) }

    val isLoading = refundState is RefundUiState.Loading
    val isFormValid = amountText.isNotBlank() &&
        (amountText.toDoubleOrNull() ?: 0.0) > 0 &&
        reasonText.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
            // a su ventana, asi que el ajuste va en el contenido.
            .imePadding()
            .padding(horizontal = AvoqadoTheme.spacing.xl),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reembolso desasociado",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Amount field
        Text(
            text = "Monto",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        OutlinedTextField(
            value = amountText,
            onValueChange = { newValue ->
                // Allow only valid decimal input
                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                    amountText = newValue
                }
            },
            placeholder = { Text("0.00") },
            prefix = {
                Text(
                    text = "$ ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Reason field
        Text(
            text = "Razón",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        OutlinedTextField(
            value = reasonText,
            onValueChange = { reasonText = it },
            placeholder = { Text("Motivo del reembolso") },
            singleLine = false,
            minLines = 2,
            maxLines = 3,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Products to return section (optional)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Productos a devolver (opcional)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { showAddItem = !showAddItem },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Agregar producto",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        // Added items list
        if (refundItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            refundItems.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AvoqadoTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${item.name} x${item.quantity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { refundItems.removeAt(index) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Quitar",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // Add item inline form
        if (showAddItem) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    placeholder = { Text("Nombre del producto") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                OutlinedTextField(
                    value = itemQty,
                    onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal.matches(Regex("^\\d+$"))) {
                            itemQty = newVal
                        }
                    },
                    placeholder = { Text("Cant.") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            val qty = itemQty.toIntOrNull() ?: 1
                            if (itemName.isNotBlank() && qty > 0) {
                                refundItems.add(
                                    RefundItem(
                                        productId = "",
                                        name = itemName.trim(),
                                        quantity = qty,
                                        unitPrice = 0.0,
                                    ),
                                )
                                itemName = ""
                                itemQty = "1"
                                showAddItem = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Confirmar",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Refund method indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Icon(
                Icons.Filled.Payments,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Método: Efectivo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Error message
        if (refundState is RefundUiState.Error) {
            Text(
                text = refundState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
            )
        }

        // Success message
        if (refundState is RefundUiState.Success) {
            Text(
                text = "Reembolso procesado exitosamente",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
            )
        }

        // Process button (dark, full width)
        Button(
            onClick = { onProcess(amountText, reasonText, refundItems.toList()) },
            enabled = isFormValid && !isLoading && refundState !is RefundUiState.Success,
            modifier = Modifier
                .fillMaxWidth()
                .height(AvoqadoTheme.dimensions.buttonLarge),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.surface,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    text = "Procesar reembolso",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
    }
}
