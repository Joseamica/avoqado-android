package com.avoqado.pos.articles.presentation.creditpacks

import androidx.compose.foundation.ExperimentalFoundationApi
import com.avoqado.pos.core.util.Plurales
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoLoadingState
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success

@Composable
fun CreditPackListView(viewModel: ArticlesViewModel) {
    val creditPacks by viewModel.creditPacks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var editingPack by remember { mutableStateOf<CreditPack?>(null) }
    var showCreateForm by remember { mutableStateOf(false) }
    var deletingPack by remember { mutableStateOf<CreditPack?>(null) }
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseListMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val contentHorizontalPadding = if (denseListMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val headerVerticalPadding = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md

    // Show form sheet (create or edit)
    if (showCreateForm) {
        CreditPackFormSheet(
            pack = null,
            viewModel = viewModel,
            onDismiss = { showCreateForm = false },
        )
    }

    editingPack?.let { pack ->
        CreditPackFormSheet(
            pack = pack,
            viewModel = viewModel,
            onDismiss = { editingPack = null },
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
                text = "Paquetes de creditos",
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
                    contentDescription = "Nuevo paquete",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (denseListMode) 18.dp else 20.dp),
                )
            }
        }

        // MARK: - Content
        if (isLoading && creditPacks.isEmpty()) {
            AvoqadoLoadingState(
                message = "Cargando paquetes...",
                compact = denseListMode,
            )
        } else if (creditPacks.isEmpty()) {
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
                        imageVector = Icons.Filled.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "No hay paquetes",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Crea tu primer paquete de creditos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = contentHorizontalPadding,
                    vertical = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
                ),
            ) {
                items(creditPacks, key = { it.id }) { pack ->
                    CreditPackCard(
                        pack = pack,
                        denseMode = denseListMode,
                        onTap = { editingPack = pack },
                        onEdit = { editingPack = pack },
                        onDelete = { deletingPack = pack },
                    )
                }
            }
        }
    }

    // MARK: - Delete confirmation dialog
    deletingPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { deletingPack = null },
            title = {
                Text(text = "Eliminar paquete")
            },
            text = {
                Text(text = "Esta acción no se puede deshacer")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCreditPack(pack.id)
                    deletingPack = null
                }) {
                    Text(
                        text = "Eliminar",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPack = null }) {
                    Text(text = "Cancelar")
                }
            },
        )
    }
}

// MARK: - Credit Pack Card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CreditPackCard(
    pack: CreditPack,
    denseMode: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isActive = pack.active ?: false
    val packItems = pack.items ?: emptyList()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTap() },
                onLongClick = { showMenu = true },
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            ),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(if (denseMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm),
        ) {
            // MARK: - Name + Price row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pack.name,
                    style = if (denseMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pack.displayPrice,
                    style = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // MARK: - Description
            if (!pack.description.isNullOrBlank()) {
                Text(
                    text = pack.description,
                    style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // MARK: - Icons row: item count + validity + status capsule
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (denseMode) 14.dp else 16.dp),
                )
                Text(
                    text = " " + Plurales.articulos(pack.itemCount),
                    style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(AvoqadoTheme.spacing.sm))
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (denseMode) 14.dp else 16.dp),
                )
                Text(
                    text = " ${pack.validityDays ?: "∞"} días",
                    style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))

                // Status capsule + dropdown anchor
                Box {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isActive) {
                            Success.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ) {
                        Text(
                            text = if (isActive) "Activo" else "Inactivo",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = AvoqadoTheme.spacing.sm,
                                vertical = if (denseMode) 1.dp else AvoqadoTheme.spacing.xxs,
                            ),
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Editar") },
                            onClick = {
                                showMenu = false
                                onEdit()
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
                                onDelete()
                            },
                        )
                    }
                }
            }

            // MARK: - Items list (if any)
            if (packItems.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                packItems.forEach { item ->
                    val productName = item.product?.name ?: item.productId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = productName,
                            style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "x${item.quantity}",
                            style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
