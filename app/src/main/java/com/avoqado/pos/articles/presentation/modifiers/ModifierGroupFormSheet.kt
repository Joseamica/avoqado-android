package com.avoqado.pos.articles.presentation.modifiers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Local form state

private data class InlineModifierState(
    val id: String? = null,
    val name: String = "",
    val price: String = "0",
    val isDeleted: Boolean = false,
    val key: Long = System.nanoTime(),
)

// MARK: - ModifierGroupFormSheet

@Composable
fun ModifierGroupFormSheet(
    group: ModifierGroup?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    val isSaving by viewModel.isSaving.collectAsState()
    val isEditing = group != null

    // MARK: - Form state
    var groupName by remember { mutableStateOf(group?.name ?: "") }
    var required by remember { mutableStateOf(group?.required ?: false) }
    var allowMultiple by remember { mutableStateOf(group?.allowMultiple ?: false) }
    var minSelections by remember { mutableStateOf(group?.minSelections?.toString() ?: "") }
    var maxSelections by remember { mutableStateOf(group?.maxSelections?.toString() ?: "") }
    val modifiers = remember {
        mutableStateListOf<InlineModifierState>().apply {
            group?.modifiers?.forEach { m ->
                add(
                    InlineModifierState(
                        id = m.id,
                        name = m.name,
                        price = m.price?.toString() ?: "0",
                        key = m.id.hashCode().toLong(),
                    ),
                )
            }
        }
    }
    var showDeleteModifierDialog by remember { mutableStateOf<Int?>(null) }

    // MARK: - Delete modifier confirmation dialog
    if (showDeleteModifierDialog != null) {
        val index = showDeleteModifierDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteModifierDialog = null },
            title = { Text("Eliminar modificador") },
            text = { Text("Esta accion no se puede deshacer") },
            confirmButton = {
                TextButton(
                    onClick = {
                        modifiers[index] = modifiers[index].copy(isDeleted = true)
                        showDeleteModifierDialog = null
                    },
                ) {
                    Text(
                        text = "Eliminar",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModifierDialog = null }) {
                    Text("Cancelar")
                }
            },
        )
    }

    // MARK: - Sheet
    AvoqadoFullScreenModal(
        title = if (isEditing) "Editar grupo" else "Nuevo grupo",
        onDismiss = onDismiss,
        primaryActionText = if (isEditing) "Guardar" else "Crear",
        onPrimaryAction = {
            if (isEditing) {
                viewModel.updateModifierGroup(
                    groupId = group!!.id,
                    name = groupName,
                    required = required,
                    allowMultiple = allowMultiple,
                    minSelections = if (allowMultiple) minSelections.toIntOrNull() else null,
                    maxSelections = if (allowMultiple) maxSelections.toIntOrNull() else null,
                    originalModifiers = group.modifiers ?: emptyList(),
                    currentModifiers = modifiers.map {
                        ArticlesViewModel.InlineModifier(
                            id = it.id,
                            name = it.name,
                            price = it.price.toDoubleOrNull() ?: 0.0,
                            isDeleted = it.isDeleted,
                        )
                    },
                )
            } else {
                viewModel.createModifierGroup(
                    name = groupName,
                    required = required,
                    allowMultiple = allowMultiple,
                    minSelections = if (allowMultiple) minSelections.toIntOrNull() else null,
                    maxSelections = if (allowMultiple) maxSelections.toIntOrNull() else null,
                    modifiers = modifiers.filter { !it.isDeleted }.map {
                        ArticlesViewModel.InlineModifier(
                            name = it.name,
                            price = it.price.toDoubleOrNull() ?: 0.0,
                        )
                    },
                )
            }
            onDismiss()
        },
        primaryActionEnabled = groupName.isNotBlank() && !isSaving,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            // MARK: - GROUP DETAILS section
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Nombre del grupo (ej: Tamano)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = groupName.isBlank(),
            )

            // MARK: - SELECTION RULES section
            SwitchRow(
                label = "Requerido",
                checked = required,
                onCheckedChange = { required = it },
            )
            SwitchRow(
                label = "Permitir multiples",
                checked = allowMultiple,
                onCheckedChange = { allowMultiple = it },
            )

            if (allowMultiple) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = minSelections,
                        onValueChange = { minSelections = it },
                        label = { Text("Min") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                    OutlinedTextField(
                        value = maxSelections,
                        onValueChange = { maxSelections = it },
                        label = { Text("Max") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            // MARK: - OPTIONS section
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(
                    text = "OPCIONES",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Modifier rows
                modifiers.forEachIndexed { index, mod ->
                    if (!mod.isDeleted) {
                        key(mod.key) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = mod.name,
                                    onValueChange = { modifiers[index] = modifiers[index].copy(name = it) },
                                    label = { Text("Nombre") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                                OutlinedTextField(
                                    value = mod.price,
                                    onValueChange = { modifiers[index] = modifiers[index].copy(price = it) },
                                    label = { Text("$") },
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                )
                                IconButton(
                                    onClick = {
                                        if (mod.id != null) {
                                            showDeleteModifierDialog = index
                                        } else {
                                            modifiers.removeAt(index)
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Eliminar modificador",
                                        tint = if (mod.id != null) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // "Agregar" button
                TextButton(
                    onClick = { modifiers.add(InlineModifierState()) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                    Text("Agregar")
                }
            }
        }
    }
}

// MARK: - SwitchRow helper

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
