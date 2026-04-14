package com.avoqado.pos.articles.presentation.units

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.MeasurementUnit
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Units View

@Composable
fun UnitsView(
    viewModel: ArticlesViewModel,
) {
    val customUnits by viewModel.customUnits.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }
    var editingUnitIndex by remember { mutableStateOf<Int?>(null) }

    // Combine built-in units with custom ones
    val builtInUnits = MeasurementUnit.entries

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with create button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Unidades de medida",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { showCreateSheet = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                Text("Crear")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (builtInUnits.isEmpty() && customUnits.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Straighten,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                Text(
                    text = "Sin unidades personalizadas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Crea unidades que puedan aplicarse a cualquier articulo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.xxl),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Built-in section header
                item {
                    Text(
                        text = "PREDEFINIDAS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = AvoqadoTheme.spacing.lg,
                            vertical = AvoqadoTheme.spacing.sm,
                        ),
                    )
                }

                items(builtInUnits.toList()) { unit ->
                    UnitRow(
                        name = unit.label,
                        abbreviation = unit.abbreviation,
                        isBuiltIn = true,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                // Custom units section
                if (customUnits.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                        Text(
                            text = "PERSONALIZADAS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = AvoqadoTheme.spacing.lg,
                                vertical = AvoqadoTheme.spacing.sm,
                            ),
                        )
                    }

                    items(customUnits.size) { index ->
                        val unit = customUnits[index]
                        UnitRow(
                            name = unit.first,
                            abbreviation = unit.second,
                            isBuiltIn = false,
                            onEdit = { editingUnitIndex = index },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateUnitSheet(onDismiss = { showCreateSheet = false }, viewModel = viewModel)
    }

    editingUnitIndex?.let { index ->
        val unit = customUnits.getOrNull(index)
        if (unit != null) {
            EditUnitSheet(
                initialName = unit.first,
                initialAbbreviation = unit.second,
                onDismiss = { editingUnitIndex = null },
                onSave = { name, abbreviation ->
                    viewModel.updateCustomUnit(index, name, abbreviation)
                    editingUnitIndex = null
                },
            )
        }
    }
}

// MARK: - Unit Row

@Composable
private fun UnitRow(
    name: String,
    abbreviation: String,
    isBuiltIn: Boolean,
    onEdit: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBuiltIn) { onEdit() }
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = abbreviation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Create Unit Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateUnitSheet(
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    var unitName by remember { mutableStateOf("") }
    var abbreviation by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Nueva unidad",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            OutlinedTextField(
                value = unitName,
                onValueChange = { unitName = it },
                label = { Text("Nombre") },
                placeholder = { Text("Ej: Metro, Galones") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            OutlinedTextField(
                value = abbreviation,
                onValueChange = { abbreviation = it },
                label = { Text("Abreviacion") },
                placeholder = { Text("Ej: m, gal") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            PrimaryButton(
                text = "Crear unidad",
                onClick = {
                    viewModel.addCustomUnit(unitName, abbreviation)
                    onDismiss()
                },
                enabled = unitName.isNotBlank() && abbreviation.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

// MARK: - Edit Unit Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditUnitSheet(
    initialName: String,
    initialAbbreviation: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var unitName by remember { mutableStateOf(initialName) }
    var abbreviation by remember { mutableStateOf(initialAbbreviation) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Editar unidad",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

            OutlinedTextField(
                value = unitName,
                onValueChange = { unitName = it },
                label = { Text("Nombre") },
                placeholder = { Text("Ej: Metro, Galones") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            OutlinedTextField(
                value = abbreviation,
                onValueChange = { abbreviation = it },
                label = { Text("Abreviacion") },
                placeholder = { Text("Ej: m, gal") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            PrimaryButton(
                text = "Guardar cambios",
                onClick = { onSave(unitName, abbreviation) },
                enabled = unitName.isNotBlank() && abbreviation.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
