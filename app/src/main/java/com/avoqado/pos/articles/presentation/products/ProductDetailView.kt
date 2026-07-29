package com.avoqado.pos.articles.presentation.products

import androidx.compose.foundation.background
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.data.model.InventoryMethod
import com.avoqado.pos.articles.data.model.MeasurementUnit
import com.avoqado.pos.articles.data.model.PriceType
import com.avoqado.pos.articles.data.model.ProductType
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

// MARK: - ProductDetailView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailView(
    product: ArticleProduct?,  // null = create mode
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val categories by viewModel.categories.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    // MARK: - Form State
    var name by remember { mutableStateOf(product?.name ?: "") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf(product?.description ?: "") }
    var type by remember { mutableStateOf(product?.productType ?: ProductType.REGULAR) }
    var categoryId by remember { mutableStateOf(product?.categoryId) }
    var sku by remember { mutableStateOf(product?.sku ?: "") }
    var gtin by remember { mutableStateOf(product?.gtin ?: "") }
    var priceType by remember {
        mutableStateOf(if (product?.priceType == "VARIABLE") PriceType.VARIABLE else PriceType.FIXED)
    }
    var price by remember { mutableStateOf(product?.price?.toString() ?: "") }
    var cost by remember { mutableStateOf(product?.cost?.toString() ?: "") }
    var taxRate by remember { mutableStateOf(product?.taxRate ?: 0.16) }
    var isActive by remember { mutableStateOf(product?.active ?: true) }
    var trackInventory by remember { mutableStateOf(product?.trackInventory ?: false) }
    var inventoryMethod by remember {
        mutableStateOf(
            product?.inventoryMethod?.let {
                try { InventoryMethod.valueOf(it) } catch (_: Exception) { null }
            } ?: InventoryMethod.QUANTITY
        )
    }
    var unit by remember {
        mutableStateOf(
            product?.unit?.let {
                try { MeasurementUnit.valueOf(it) } catch (_: Exception) { null }
            } ?: MeasurementUnit.UNIT
        )
    }
    var durationMinutes by remember { mutableStateOf(product?.durationMinutes?.toString() ?: "") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // MARK: - Save action
    fun onSave() {
        val handle: (Boolean) -> Unit = { ok ->
            if (ok) onDismiss() else saveError = "No se pudo guardar. Intenta de nuevo."
        }
        if (product == null) {
            viewModel.createProduct(
                name = name,
                description = description.ifBlank { null },
                type = type,
                categoryId = categoryId,
                sku = sku.ifBlank { null },
                gtin = gtin.ifBlank { null },
                priceType = priceType,
                price = price.toDoubleOrNull(),
                cost = cost.toDoubleOrNull(),
                taxRate = taxRate,
                isActive = isActive,
                trackInventory = trackInventory,
                inventoryMethod = if (trackInventory) inventoryMethod.name else null,
                unit = if (trackInventory) unit.name else null,
                onResult = handle,
            )
        } else {
            viewModel.updateProduct(
                productId = product.id,
                name = name,
                description = description.ifBlank { null },
                type = type,
                categoryId = categoryId,
                sku = sku.ifBlank { null },
                gtin = gtin.ifBlank { null },
                priceType = priceType,
                price = price.toDoubleOrNull(),
                cost = cost.toDoubleOrNull(),
                taxRate = taxRate,
                isActive = isActive,
                trackInventory = trackInventory,
                inventoryMethod = if (trackInventory) inventoryMethod.name else null,
                unit = if (trackInventory) unit.name else null,
                onResult = handle,
            )
        }
    }

    if (saveError != null) {
        AvoqadoDialog(
            title = "No se pudo guardar",
            description = saveError ?: "",
            onDismiss = { saveError = null },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { saveError = null })
            },
            content = {},
        )
    }

    AvoqadoFullScreenModal(
        title = if (product == null) "Nuevo artículo" else "Editar artículo",
        onDismiss = onDismiss,
        primaryActionText = if (product == null) "Crear" else "Guardar",
        onPrimaryAction = { onSave() },
        primaryActionEnabled = name.isNotBlank() && !isSaving,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {

            // MARK: 1. Avatar
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = name.take(2).uppercase().ifEmpty { "?" },
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 36.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // MARK: 2. DETALLES
            item {
                SectionCard(title = "DETALLES") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = {
                            Text("Nombre")
                        },
                        placeholder = {
                            Text("Nombre del artículo *")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = {
                            Text("Descripción (opcional)")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }

            // MARK: 3. TIPO Y CATEGORIA
            item {
                SectionCard(title = "TIPO Y CATEGORÍA") {
                    DropdownSelector(
                        label = "Tipo",
                        options = ProductType.entries,
                        selected = type,
                        display = { it.label },
                        onSelected = { type = it },
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    // Category dropdown — includes a "Sin categoría" null option
                    CategoryDropdownSelector(
                        categories = categories,
                        selectedId = categoryId,
                        onSelected = { categoryId = it },
                    )
                }
            }

            // MARK: 3.5. DURACION (services only)
            if (type == ProductType.APPOINTMENTS_SERVICE) {
                item {
                    SectionCard(title = "DURACIÓN DEL SERVICIO") {
                        OutlinedTextField(
                            value = durationMinutes,
                            onValueChange = { durationMinutes = it },
                            label = { Text("Duración (minutos)") },
                            placeholder = { Text("Ej: 30, 60, 90") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if (durationMinutes.isNotBlank()) {
                            val mins = durationMinutes.toIntOrNull() ?: 0
                            if (mins > 0) {
                                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                                Text(
                                    text = if (mins >= 60) {
                                        val h = mins / 60
                                        val m = mins % 60
                                        if (m > 0) "= ${h}h ${m}min" else "= ${h}h"
                                    } else {
                                        "= ${mins}min"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // MARK: 4. PRECIO Y CODIGOS
            item {
                SectionCard(title = "PRECIO Y CODIGOS") {
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it },
                        label = { Text("SKU") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    OutlinedTextField(
                        value = gtin,
                        onValueChange = { gtin = it },
                        label = { Text("Código de barras") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    DropdownSelector(
                        label = "Tipo de precio",
                        options = PriceType.entries,
                        selected = priceType,
                        display = { if (it == PriceType.FIXED) "Precio fijo" else "Precio variable" },
                        onSelected = { priceType = it },
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("Precio") },
                        prefix = { Text("$") },
                        enabled = priceType == PriceType.FIXED,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    OutlinedTextField(
                        value = cost,
                        onValueChange = { cost = it },
                        label = { Text("Costo") },
                        prefix = { Text("$") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }

            // MARK: 5. IMPUESTOS
            item {
                val taxOptions = listOf(0.16 to "IVA 16%", 0.0 to "Exento")
                SectionCard(title = "IMPUESTOS") {
                    DropdownSelector(
                        label = "Tasa de impuesto",
                        options = taxOptions,
                        selected = taxOptions.find { it.first == taxRate } ?: taxOptions.first(),
                        display = { it.second },
                        onSelected = { taxRate = it.first },
                    )
                }
            }

            // MARK: 6. INVENTARIO
            item {
                SectionCard(title = "INVENTARIO") {
                    SwitchRow(
                        label = "Rastrear inventario",
                        subtitle = "Controla existencias de este artículo",
                        checked = trackInventory,
                        onCheckedChange = { trackInventory = it },
                    )
                    if (trackInventory) {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                        InventoryMethod.entries.forEach { method ->
                            RadioRow(
                                label = method.label,
                                description = method.description,
                                selected = inventoryMethod == method,
                                onClick = { inventoryMethod = method },
                            )
                        }
                        if (inventoryMethod == InventoryMethod.RECIPE) {
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                            Text(
                                text = "La receta se configura después de crear el artículo desde la sección de inventario",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (inventoryMethod == InventoryMethod.QUANTITY) {
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                            DropdownSelector(
                                label = "Unidad de medida",
                                options = MeasurementUnit.entries,
                                selected = unit,
                                display = { "${it.label} (${it.abbreviation})" },
                                onSelected = { unit = it },
                            )
                        }
                    }
                }
            }

            // MARK: 7. MODIFICADORES (edit mode only, read-only)
            if (product != null && !product.modifierGroups.isNullOrEmpty()) {
                item {
                    SectionCard(title = "MODIFICADORES") {
                        product.modifierGroups.forEachIndexed { index, join ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                            }
                            Text(
                                text = join.group?.name ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${if (join.group?.required == true) "Requerido" else "Opcional"} · ${join.group?.modifierCount ?: 0} opciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // MARK: 8. Active toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                ) {
                    SwitchRow(
                        label = "Activo",
                        checked = isActive,
                        onCheckedChange = { isActive = it },
                        checkedThumbColor = Success,
                        modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                    )
                }
            }

            // MARK: 9. Delete button (edit mode only)
            if (product != null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { showDeleteDialog = true }) {
                            Text(
                                text = "Eliminar artículo",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Bottom padding
            item { Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl)) }
        }
    }

    // MARK: - Delete confirmation dialog
    if (showDeleteDialog && product != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar artículo") },
            text = { Text("Esta acción no se puede deshacer. El artículo sera eliminado permanentemente.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProduct(product.id)
                    showDeleteDialog = false
                    onDismiss()
                }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

// MARK: - SectionCard

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        ) {
            Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                content()
            }
        }
    }
}

// MARK: - SwitchRow

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    checkedThumbColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = checkedThumbColor,
            ),
        )
    }
}

// MARK: - RadioRow

@Composable
private fun RadioRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - DropdownSelector (generic)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSelector(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = display(option),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

// MARK: - CategoryDropdownSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdownSelector(
    categories: List<com.avoqado.pos.articles.data.model.ArticleCategory>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.find { it.id == selectedId }?.name ?: "Sin categoría"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Categoría") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // "Sin categoría" option (null)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Sin categoría",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onSelected(category.id)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
