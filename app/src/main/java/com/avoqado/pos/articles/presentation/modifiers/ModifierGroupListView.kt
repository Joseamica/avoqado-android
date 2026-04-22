package com.avoqado.pos.articles.presentation.modifiers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoLoadingState
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Info

@Composable
fun ModifierGroupListView(viewModel: ArticlesViewModel) {
    val modifierGroups by viewModel.modifierGroups.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    var editingGroup by remember { mutableStateOf<ModifierGroup?>(null) }
    var showCreateForm by remember { mutableStateOf(false) }
    var editingModifier by remember { mutableStateOf<Pair<String, ArticleModifier>?>(null) }
    var deletingGroup by remember { mutableStateOf<ModifierGroup?>(null) }
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseListMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val contentHorizontalPadding = if (denseListMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val headerVerticalPadding = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md

    // MARK: - Sheets

    if (showCreateForm) {
        ModifierGroupFormSheet(
            group = null,
            viewModel = viewModel,
            onDismiss = { showCreateForm = false },
        )
    }

    editingGroup?.let { group ->
        ModifierGroupFormSheet(
            group = group,
            viewModel = viewModel,
            onDismiss = { editingGroup = null },
        )
    }

    editingModifier?.let { (groupId, modifier) ->
        ModifierDetailSheet(
            groupId = groupId,
            modifier = modifier,
            viewModel = viewModel,
            onDismiss = { editingModifier = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // MARK: - Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = contentHorizontalPadding,
                    vertical = headerVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Modificadores",
                style = if (denseListMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(if (denseListMode) 34.dp else 36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    )
                    .clickable { showCreateForm = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Nuevo grupo de modificadores",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (denseListMode) 18.dp else 20.dp),
                )
            }
        }

        // MARK: - Content
        if (isLoading && modifierGroups.isEmpty()) {
            AvoqadoLoadingState(
                message = "Cargando modificadores...",
                compact = denseListMode,
            )
        } else if (modifierGroups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AvoqadoTheme.spacing.xxxl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "No hay modificadores",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Crea tu primer grupo de modificadores",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = contentHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm),
            ) {
                item { /* top padding */ }
                items(modifierGroups, key = { it.id }) { group ->
                    ModifierGroupCard(
                        group = group,
                        denseMode = denseListMode,
                        isExpanded = group.id in expandedGroupIds,
                        onToggleExpand = {
                            expandedGroupIds = if (group.id in expandedGroupIds) {
                                expandedGroupIds - group.id
                            } else {
                                expandedGroupIds + group.id
                            }
                        },
                        onEditGroup = { editingGroup = group },
                        onDeleteGroup = { deletingGroup = group },
                        onModifierTap = { modifier -> editingModifier = Pair(group.id, modifier) },
                    )
                }
                item { /* bottom padding */ }
            }
        }
    }

    // MARK: - Delete confirmation dialog
    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = {
                Text(text = "Eliminar grupo de modificadores")
            },
            text = {
                Text(text = "Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModifierGroup(group.id)
                    deletingGroup = null
                }) {
                    Text(
                        text = "Eliminar",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) {
                    Text(text = "Cancelar")
                }
            },
        )
    }
}

// MARK: - Modifier Group Card

@Composable
private fun ModifierGroupCard(
    group: ModifierGroup,
    denseMode: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onModifierTap: (ArticleModifier) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val cornerRadius = AvoqadoTheme.cornerRadius

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(cornerRadius.md),
            )
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { showMenu = true })
            },
        shape = RoundedCornerShape(cornerRadius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            // MARK: Card Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AvoqadoTheme.spacing.xs,
                        top = if (denseMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
                        bottom = if (denseMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
                        end = AvoqadoTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
            ) {
                // Expand/Collapse chevron
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(if (denseMode) 30.dp else 32.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Colapsar" else "Expandir",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (denseMode) 18.dp else 20.dp),
                    )
                }

                // Group name tap target (opens edit sheet)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onEditGroup),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    // Group name
                    Text(
                        text = group.name,
                        style = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    // Required / Optional badge
                    RequiredBadge(required = group.required, denseMode = denseMode)

                    // Modifier count
                    Text(
                        text = "${group.modifierCount} opciones",
                        style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Right chevron + dropdown anchor
                    Box {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (denseMode) 14.dp else 16.dp),
                        )

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Editar") },
                                onClick = {
                                    showMenu = false
                                    onEditGroup()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Eliminar",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDeleteGroup()
                                },
                            )
                        }
                    }
                }
            }

            // MARK: Expanded modifiers
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val modifiers = group.modifiers ?: emptyList()
                    if (modifiers.isEmpty()) {
                        Text(
                            text = "Sin opciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 40.dp,
                                top = AvoqadoTheme.spacing.sm,
                                bottom = AvoqadoTheme.spacing.sm,
                                end = AvoqadoTheme.spacing.md,
                            ),
                        )
                    } else {
                        modifiers.forEach { modifier ->
                            ModifierRow(
                                modifier = modifier,
                                denseMode = denseMode,
                                onTap = { onModifierTap(modifier) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Required Badge

@Composable
private fun RequiredBadge(
    required: Boolean,
    denseMode: Boolean,
) {
    val bgColor = if (required) Info.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant
    val textColor = if (required) Info else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (required) "Requerido" else "Opcional"

    Box(
        modifier = Modifier
            .background(
                color = bgColor,
                shape = RoundedCornerShape(50),
            )
            .padding(
                horizontal = AvoqadoTheme.spacing.sm,
                vertical = if (denseMode) 1.dp else 2.dp,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

// MARK: - Modifier Row

@Composable
private fun ModifierRow(
    modifier: ArticleModifier,
    denseMode: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(
                start = 40.dp,
                end = AvoqadoTheme.spacing.sm,
                top = if (denseMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
                bottom = if (denseMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        // Modifier name
        Text(
            text = modifier.name,
            style = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        // Price (if any)
        val priceText = modifier.displayPrice
        if (priceText.isNotEmpty()) {
            Text(
                text = priceText,
                style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Right chevron
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (denseMode) 14.dp else 16.dp),
        )
    }
}
