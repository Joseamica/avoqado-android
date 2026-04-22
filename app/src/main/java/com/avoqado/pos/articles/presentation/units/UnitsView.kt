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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.MeasurementUnit
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
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
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseListMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val contentHorizontalPadding = if (denseListMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val headerVerticalPadding = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md
    val rowVerticalPadding = if (denseListMode) 9.dp else AvoqadoTheme.spacing.md

    // Combine built-in units with custom ones
    val builtInUnits = MeasurementUnit.entries

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with create button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPadding, vertical = headerVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Unidades de medida",
                style = if (denseListMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
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
                                horizontal = contentHorizontalPadding,
                                vertical = AvoqadoTheme.spacing.sm,
                            ),
                        )
                    }

                items(builtInUnits.toList()) { unit ->
                    UnitRow(
                        name = unit.label,
                        abbreviation = unit.abbreviation,
                        denseMode = denseListMode,
                        horizontalPadding = contentHorizontalPadding,
                        rowVerticalPadding = rowVerticalPadding,
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
                                horizontal = contentHorizontalPadding,
                                vertical = AvoqadoTheme.spacing.sm,
                            ),
                        )
                    }

                    items(customUnits.size) { index ->
                        val unit = customUnits[index]
                        UnitRow(
                            name = unit.first,
                            abbreviation = unit.second,
                            denseMode = denseListMode,
                            horizontalPadding = contentHorizontalPadding,
                            rowVerticalPadding = rowVerticalPadding,
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
    denseMode: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    rowVerticalPadding: androidx.compose.ui.unit.Dp,
    isBuiltIn: Boolean,
    onEdit: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBuiltIn) { onEdit() }
            .padding(horizontal = horizontalPadding, vertical = rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = if (denseMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = abbreviation,
            style = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Create Unit Sheet

@Composable
private fun CreateUnitSheet(
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    var unitName by remember { mutableStateOf("") }
    var abbreviation by remember { mutableStateOf("") }

    AvoqadoFullScreenModal(
        title = "Nueva unidad",
        onDismiss = onDismiss,
        primaryActionText = "Crear",
        onPrimaryAction = {
            viewModel.addCustomUnit(unitName, abbreviation)
            onDismiss()
        },
        primaryActionEnabled = unitName.isNotBlank() && abbreviation.isNotBlank(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
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
        }
    }
}

// MARK: - Edit Unit Sheet

@Composable
private fun EditUnitSheet(
    initialName: String,
    initialAbbreviation: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var unitName by remember { mutableStateOf(initialName) }
    var abbreviation by remember { mutableStateOf(initialAbbreviation) }

    AvoqadoFullScreenModal(
        title = "Editar unidad",
        onDismiss = onDismiss,
        primaryActionText = "Guardar",
        onPrimaryAction = { onSave(unitName, abbreviation) },
        primaryActionEnabled = unitName.isNotBlank() && abbreviation.isNotBlank(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
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
        }
    }
}
