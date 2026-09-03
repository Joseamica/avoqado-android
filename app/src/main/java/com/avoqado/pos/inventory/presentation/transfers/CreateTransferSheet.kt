package com.avoqado.pos.inventory.presentation.transfers

import androidx.compose.foundation.background
import com.avoqado.pos.inventory.data.model.onHandDisplay
import androidx.compose.runtime.LaunchedEffect
import com.avoqado.pos.designsystem.components.AvoqadoDialog
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.CreateTransferItemRequest
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import androidx.compose.ui.text.style.TextAlign

// MARK: - Create Transfer Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTransferSheet(
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val stockItems by viewModel.stockItems.collectAsState()
    var fromLocation by remember { mutableStateOf("") }
    val invSheetError by viewModel.errorMessage.collectAsState()
    LaunchedEffect(Unit) { viewModel.clearErrorMessage() }
    var toLocation by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val selectedItems = remember { mutableStateListOf<TransferItemDraft>() }
    var showItemPicker by remember { mutableStateOf(false) }

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
                // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
                // a su ventana, asi que el ajuste va en el contenido.
                .imePadding()
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Nueva transferencia",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            // From location
            Text(
                text = "Ubicación de origen",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            OutlinedTextField(
                value = fromLocation,
                onValueChange = { fromLocation = it },
                placeholder = { Text("Seleccionar ubicación") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // To location
            Text(
                text = "Ubicación de destino",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            OutlinedTextField(
                value = toLocation,
                onValueChange = { toLocation = it },
                placeholder = { Text("Seleccionar ubicación") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Items section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Artículos a transferir",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showItemPicker = true }) {
                    Text(
                        text = "+ Agregar artículo",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (selectedItems.isEmpty()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "No hay artículos seleccionados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                selectedItems.forEachIndexed { index, draft ->
                    TransferItemRow(
                        draft = draft,
                        onQuantityChange = { newQty ->
                            selectedItems[index] = draft.copy(quantity = newQty)
                        },
                        onRemove = { selectedItems.removeAt(index) },
                    )
                    if (index < selectedItems.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            // Notes
            Text(
                text = "Notas (opcional)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = { Text("Notas adicionales...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Qué falta para poder crear. Antes el botón se apagaba en silencio y
            // podían faltar TRES cosas distintas —origen, destino o artículos— sin
            // que nada lo dijera. El caso de origen == destino era el peor: todo
            // parecía lleno y el botón seguía muerto.
            val faltaParaCrear: String? = when {
                isSaving -> null
                fromLocation.isBlank() && toLocation.isBlank() ->
                    "Elige la ubicación de origen y la de destino"
                fromLocation.isBlank() -> "Elige la ubicación de origen"
                toLocation.isBlank() -> "Elige la ubicación de destino"
                fromLocation.trim().lowercase() == toLocation.trim().lowercase() ->
                    "El origen y el destino no pueden ser la misma ubicación"
                selectedItems.isEmpty() -> "Agrega al menos un artículo para transferir"
                else -> null
            }
            faltaParaCrear?.let { falta ->
                Text(
                    text = falta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            }

            // Create button
            PrimaryButton(
                text = if (isSaving) "Creando..." else "Crear transferencia",
                onClick = {
                    val items = selectedItems.map { draft ->
                        CreateTransferItemRequest(
                            productId = draft.productId,
                            productName = draft.productName,
                            quantity = draft.quantity,
                            unit = draft.unit,
                        )
                    }
                    viewModel.createTransfer(
                        fromLocationName = fromLocation.trim(),
                        toLocationName = toLocation.trim(),
                        items = items,
                        notes = notes.ifBlank { null },
                        onSuccess = { onDismiss() },
                    )
                },
                // Se exige al menos un artículo: una transferencia sin líneas no
                // mueve nada y sólo ensucia el historial de inventario.
                enabled = faltaParaCrear == null,
                fullWidth = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }

    // Item picker overlay
    if (showItemPicker) {
        ItemPickerSheet(
            stockItems = stockItems,
            existingIds = selectedItems.map { it.productId }.toSet(),
            onAdd = { selected ->
                selected.forEach { stock ->
                    selectedItems.add(
                        TransferItemDraft(
                            productId = stock.id,
                            productName = stock.name,
                            quantity = 1,
                            unit = stock.unit,
                        )
                    )
                }
                showItemPicker = false
            },
            onDismiss = { showItemPicker = false },
        )
    }
}

// MARK: - Data classes

data class TransferItemDraft(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unit: String? = null,
)

// MARK: - Transfer Item Row (shows in the list after adding)

@Composable
private fun TransferItemRow(
    draft: TransferItemDraft,
    onQuantityChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = draft.productName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (draft.unit != null) {
                Text(
                    text = draft.unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Quantity controls
        IconButton(
            onClick = { if (draft.quantity > 1) onQuantityChange(draft.quantity - 1) },
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Menos", modifier = Modifier.size(18.dp))
        }
        Text(
            text = draft.quantity.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = { onQuantityChange(draft.quantity + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = "Más", modifier = Modifier.size(18.dp))
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Quitar",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// MARK: - Item Picker Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPickerSheet(
    stockItems: List<StockItem>,
    existingIds: Set<String>,
    onAdd: (List<StockItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<StockItem>() }

    val available = stockItems.filter { it.id !in existingIds }
    val filtered = if (searchText.isBlank()) available
    else available.filter {
        it.name.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Seleccionar artículos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onAdd(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text("Agregar (${selected.size})")
                }
            }

            HorizontalDivider()

            // Search
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Buscar por nombre o SKU") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            )

            // List
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(filtered, key = { it.id }) { item ->
                    val isSelected = item in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selected.remove(item)
                                else selected.add(item)
                            }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else Color.Transparent,
                            )
                            .padding(
                                horizontal = AvoqadoTheme.spacing.lg,
                                vertical = AvoqadoTheme.spacing.md,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = item.name.take(2).uppercase(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (item.sku != null) {
                                Text(
                                    text = "SKU: ${item.sku}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "En mano: ${item.onHandDisplay}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
