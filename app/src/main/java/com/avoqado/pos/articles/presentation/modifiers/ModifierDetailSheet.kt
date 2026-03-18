package com.avoqado.pos.articles.presentation.modifiers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.data.model.MeasurementUnit
import com.avoqado.pos.articles.data.model.ModifierInventoryMode
import com.avoqado.pos.articles.data.model.RawMaterial
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - ModifierDetailSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifierDetailSheet(
    groupId: String,
    modifier: ArticleModifier,  // NOT Modifier — that's Compose's Modifier
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val rawMaterialResults by viewModel.rawMaterialResults.collectAsState()

    // MARK: - Form state
    var name by remember { mutableStateOf(modifier.name) }
    var price by remember { mutableStateOf(modifier.price?.toString() ?: "0") }
    var active by remember { mutableStateOf(modifier.active ?: true) }
    var trackInventory by remember { mutableStateOf(modifier.rawMaterialId != null) }
    var inventoryMode by remember {
        mutableStateOf(
            modifier.inventoryMode?.let {
                try { ModifierInventoryMode.valueOf(it) } catch (_: Exception) { null }
            } ?: ModifierInventoryMode.ADDITION,
        )
    }
    var selectedMaterial by remember { mutableStateOf<RawMaterial?>(null) }
    var quantityPerUnit by remember { mutableStateOf(modifier.quantityPerUnit?.toString() ?: "") }
    var selectedUnit by remember {
        mutableStateOf<MeasurementUnit?>(
            modifier.unit?.let {
                try { MeasurementUnit.valueOf(it) } catch (_: Exception) { null }
            },
        )
    }
    var materialSearchQuery by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                text = "Editar modificador",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // MARK: - DETALLES section
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "DETALLES",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
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
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Precio") },
                    prefix = { Text("$") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                SwitchRowWithSubtitle(
                    label = "Activo",
                    checked = active,
                    onCheckedChange = { active = it },
                )
            }

            // MARK: - INVENTARIO section
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "INVENTARIO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                )
                SwitchRowWithSubtitle(
                    label = "Rastrear inventario",
                    subtitle = "Controla existencias de este modificador",
                    checked = trackInventory,
                    onCheckedChange = { trackInventory = it },
                )

                if (trackInventory) {
                    // MARK: - Inventory mode radio group
                    Text(
                        text = "Modo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModifierInventoryMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = inventoryMode == mode,
                                    onClick = { inventoryMode = mode },
                                )
                                .background(
                                    color = if (inventoryMode == mode) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                                )
                                .padding(vertical = AvoqadoTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = inventoryMode == mode,
                                onClick = { inventoryMode = mode },
                            )
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                            Column {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // MARK: - Raw material search
                    Text(
                        text = "Materia prima",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = materialSearchQuery,
                        onValueChange = { query ->
                            materialSearchQuery = query
                            viewModel.searchRawMaterials(query)
                        },
                        label = { Text("Buscar materia prima...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Buscar",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    // MARK: - Search results
                    if (rawMaterialResults.isNotEmpty() && selectedMaterial == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                                ),
                        ) {
                            rawMaterialResults.forEach { material ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedMaterial = material
                                            materialSearchQuery = material.name
                                            selectedUnit = material.unit?.let {
                                                try { MeasurementUnit.valueOf(it) } catch (_: Exception) { null }
                                            }
                                            viewModel.clearRawMaterialResults()
                                        }
                                        .padding(
                                            horizontal = AvoqadoTheme.spacing.md,
                                            vertical = AvoqadoTheme.spacing.sm,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = material.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        material.sku?.let {
                                            Text(
                                                text = "SKU: $it",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // MARK: - Selected material card
                    if (selectedMaterial != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AvoqadoTheme.spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedMaterial!!.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    selectedMaterial!!.sku?.let {
                                        Text(
                                            text = "SKU: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    selectedMaterial!!.currentStock?.let {
                                        Text(
                                            text = "Stock: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        selectedMaterial = null
                                        materialSearchQuery = ""
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Quitar materia prima",
                                    )
                                }
                            }
                        }

                        // MARK: - Quantity per use + unit (only after selecting a material)
                        OutlinedTextField(
                            value = quantityPerUnit,
                            onValueChange = { quantityPerUnit = it },
                            label = { Text("Cantidad por uso") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )

                        // MARK: - Unit dropdown (auto-populated from material)
                        UnitDropdownSelector(
                            selectedUnit = selectedUnit,
                            onSelected = { selectedUnit = it },
                        )
                    }
                }
            }

            // MARK: - Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        viewModel.updateModifier(
                            groupId = groupId,
                            modifierId = modifier.id,
                            name = name,
                            price = price.toDoubleOrNull() ?: 0.0,
                            active = active,
                            rawMaterialId = if (trackInventory) selectedMaterial?.id else null,
                            inventoryMode = if (trackInventory) inventoryMode.name else null,
                            quantityPerUnit = if (trackInventory) quantityPerUnit.toDoubleOrNull() else null,
                            unit = if (trackInventory) selectedUnit?.name else null,
                        )
                        onDismiss()
                    },
                    enabled = name.isNotBlank() && !isSaving,
                ) {
                    Text("Guardar")
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - SwitchRowWithSubtitle helper

@Composable
private fun SwitchRowWithSubtitle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        )
    }
}

// MARK: - UnitDropdownSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdownSelector(
    selectedUnit: MeasurementUnit?,
    onSelected: (MeasurementUnit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedUnit?.let { "${it.label} (${it.abbreviation})" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Unidad de medida") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MeasurementUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${unit.label} (${unit.abbreviation})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onSelected(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}
