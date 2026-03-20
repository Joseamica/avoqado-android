package com.avoqado.pos.articles.presentation.creditpacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

// MARK: - Local state class for an item row

private data class CreditPackItemState(
    val productId: String = "",
    val quantity: String = "1",
    val key: Long = System.nanoTime(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditPackFormSheet(
    pack: CreditPack?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val products by viewModel.products.collectAsState()

    // MARK: - Form state
    var name by remember { mutableStateOf(pack?.name ?: "") }
    var description by remember { mutableStateOf(pack?.description ?: "") }
    var price by remember { mutableStateOf(pack?.price?.let {
        if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
    } ?: "") }
    var validityDays by remember { mutableStateOf(pack?.validityDays?.toString() ?: "") }
    var maxPerCustomer by remember { mutableStateOf(pack?.maxPerCustomer?.toString() ?: "") }
    var active by remember { mutableStateOf(pack?.active ?: true) }

    val items = remember {
        mutableStateListOf<CreditPackItemState>().apply {
            pack?.items?.forEach { item ->
                add(CreditPackItemState(
                    productId = item.productId,
                    quantity = item.quantity.toString(),
                    key = item.id.hashCode().toLong(),
                ))
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = pack != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            // MARK: - Title
            Text(
                text = if (isEditing) "Editar paquete" else "Nuevo paquete",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - DETALLES
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "DETALLES",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = name.isBlank(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripcion (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }

            // MARK: - PRECIO Y VALIDEZ
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "PRECIO Y VALIDEZ",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Precio") },
                    prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = validityDays,
                    onValueChange = { validityDays = it },
                    label = { Text("Dias de validez") },
                    placeholder = { Text("Sin limite") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxPerCustomer,
                    onValueChange = { maxPerCustomer = it },
                    label = { Text("Max por cliente") },
                    placeholder = { Text("Ilimitado") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // MARK: - ARTICULOS INCLUIDOS
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "ARTICULOS INCLUIDOS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                items.forEachIndexed { index, item ->
                    key(item.key) {
                        CreditPackItemRow(
                            item = item,
                            products = products,
                            onProductSelected = { productId ->
                                items[index] = items[index].copy(productId = productId)
                            },
                            onQuantityChanged = { qty ->
                                items[index] = items[index].copy(quantity = qty)
                            },
                            onRemove = { items.removeAt(index) },
                        )
                    }
                }

                // "Agregar" button
                TextButton(
                    onClick = { items.add(CreditPackItemState()) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                    Text(text = "Agregar")
                }
            }

            // MARK: - Active toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Activo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = active,
                    onCheckedChange = { active = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = Success,
                    ),
                )
            }

            // MARK: - Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancelar")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        val packItems = items
                            .filter { it.productId.isNotBlank() }
                            .map {
                                ArticlesViewModel.CreditPackItemInput(
                                    productId = it.productId,
                                    quantity = it.quantity.toIntOrNull() ?: 1,
                                )
                            }
                        if (isEditing) {
                            viewModel.updateCreditPack(
                                packId = pack!!.id,
                                name = name,
                                description = description.ifBlank { null },
                                price = price.toDoubleOrNull() ?: 0.0,
                                validityDays = validityDays.toIntOrNull(),
                                maxPerCustomer = maxPerCustomer.toIntOrNull(),
                                active = active,
                                items = packItems,
                            )
                        } else {
                            viewModel.createCreditPack(
                                name = name,
                                description = description.ifBlank { null },
                                price = price.toDoubleOrNull() ?: 0.0,
                                validityDays = validityDays.toIntOrNull(),
                                maxPerCustomer = maxPerCustomer.toIntOrNull(),
                                active = active,
                                items = packItems,
                            )
                        }
                        onDismiss()
                    },
                    enabled = name.isNotBlank() && !isSaving,
                ) {
                    Text(text = if (isEditing) "Guardar" else "Crear")
                }
            }
        }
    }
}

// MARK: - Item row sub-composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreditPackItemRow(
    item: CreditPackItemState,
    products: List<com.avoqado.pos.articles.data.model.ArticleProduct>,
    onProductSelected: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProduct = remember(products, item.productId) {
        products.firstOrNull { it.id == item.productId }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
    ) {
        // Product dropdown picker
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedProduct?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Seleccionar...") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                products.forEach { product ->
                    DropdownMenuItem(
                        text = { Text(product.name) },
                        onClick = {
                            onProductSelected(product.id)
                            expanded = false
                        },
                    )
                }
            }
        }

        // Quantity prefix label
        Text(
            text = "x",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xs),
        )

        // Quantity field
        OutlinedTextField(
            value = item.quantity,
            onValueChange = onQuantityChanged,
            modifier = Modifier.width(60.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        // Delete button
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Eliminar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
