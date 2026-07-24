package com.avoqado.pos.inventory.presentation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.PlanGate
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.components.TierBadge
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.StockSortOption
import com.avoqado.pos.inventory.presentation.purchaseorders.PurchaseOrderDetailView
import com.avoqado.pos.inventory.presentation.purchaseorders.PurchaseOrdersView
import com.avoqado.pos.inventory.presentation.transfers.TransferDetailView
import com.avoqado.pos.inventory.presentation.transfers.TransfersView
import com.avoqado.pos.inventory.presentation.traslados.InterVenueTransfersView

// MARK: - Entry Point

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    isTablet: Boolean,
    viewModel: InventoryViewModel = hiltViewModel(),
) {
    val selectedSection by viewModel.selectedSection.collectAsState()
    val selectedPO by viewModel.selectedPurchaseOrder.collectAsState()
    val selectedTransfer by viewModel.selectedTransfer.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val stockItems by viewModel.stockItems.collectAsState()
    val stockCounts by viewModel.stockCounts.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error as Snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Stock counting state
    val showCounting by viewModel.showCounting.collectAsState()
    val showReview by viewModel.showReview.collectAsState()
    val showCountTypeSheet by viewModel.showCountTypeSheet.collectAsState()
    val selectedDetail by viewModel.selectedDetail.collectAsState()

    // Show counting view (full screen)
    if (showCounting) {
        StockCountingView(viewModel = viewModel, isTablet = isTablet)
        return
    }

    // Show review view (full screen)
    if (showReview) {
        StockCountReviewView(viewModel = viewModel, isTablet = isTablet)
        return
    }

    // Show count detail view (full screen)
    if (selectedDetail != null) {
        StockCountDetailView(
            count = selectedDetail!!,
            viewModel = viewModel,
            isTablet = isTablet,
            onBack = { viewModel.selectCountDetail(null) },
        )
        return
    }

    // Show PO detail if one is selected
    if (selectedPO != null) {
        PurchaseOrderDetailView(
            order = selectedPO!!,
            viewModel = viewModel,
            onBack = { viewModel.selectPurchaseOrder(null) },
        )
        return
    }

    // Show transfer detail if one is selected
    if (selectedTransfer != null) {
        TransferDetailView(
            transfer = selectedTransfer!!,
            viewModel = viewModel,
            onBack = { viewModel.selectTransfer(null) },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isTablet) {
            TabletInventoryLayout(
                selectedSection = selectedSection,
                onSectionSelected = { viewModel.selectSection(it) },
                isLoading = isLoading,
                stockItemsEmpty = stockItems.isEmpty(),
                stockCountsEmpty = stockCounts.isEmpty(),
                viewModel = viewModel,
            )
        } else {
            PhoneInventoryLayout(
                selectedSection = selectedSection,
                onSectionSelected = { viewModel.selectSection(it) },
                isLoading = isLoading,
                stockItemsEmpty = stockItems.isEmpty(),
                stockCountsEmpty = stockCounts.isEmpty(),
                viewModel = viewModel,
            )
        }

        // Snackbar for error feedback
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Count type bottom sheet
    if (showCountTypeSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeCountTypeSheet() },
        ) {
            StockCountTypeSheet(viewModel = viewModel)
        }
    }
}

@Composable
private fun TabletInventoryLayout(
    selectedSection: InventorySection,
    onSectionSelected: (InventorySection) -> Unit,
    isLoading: Boolean,
    stockItemsEmpty: Boolean,
    stockCountsEmpty: Boolean,
    viewModel: InventoryViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Sidebar (280dp) - matching ArticlesScreen pattern
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
            ) {
                Text(
                    text = "Inventario",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Section rows
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                InventorySection.entries.forEach { section ->
                    SectionRow(
                        section = section,
                        isSelected = section == selectedSection,
                        onClick = { onSectionSelected(section) },
                        tierBadgeLabel = sectionTierBadge(section, viewModel),
                    )
                }
            }
        }

        // Hairline divider
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            InventorySectionContentOrLoading(
                isLoading = isLoading,
                stockItemsEmpty = stockItemsEmpty,
                stockCountsEmpty = stockCountsEmpty,
                selectedSection = selectedSection,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun PhoneInventoryLayout(
    selectedSection: InventorySection,
    onSectionSelected: (InventorySection) -> Unit,
    isLoading: Boolean,
    stockItemsEmpty: Boolean,
    stockCountsEmpty: Boolean,
    viewModel: InventoryViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = "Inventario",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            InventorySection.entries.forEach { section ->
                PhoneSectionChip(
                    section = section,
                    isSelected = section == selectedSection,
                    onClick = { onSectionSelected(section) },
                    tierBadgeLabel = sectionTierBadge(section, viewModel),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            InventorySectionContentOrLoading(
                isLoading = isLoading,
                stockItemsEmpty = stockItemsEmpty,
                stockCountsEmpty = stockCountsEmpty,
                selectedSection = selectedSection,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun PhoneSectionChip(
    section: InventorySection,
    isSelected: Boolean,
    onClick: () -> Unit,
    tierBadgeLabel: String? = null,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.md,
                vertical = AvoqadoTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
    ) {
        Text(
            text = section.label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (tierBadgeLabel != null) {
            TierBadge(tierLabel = tierBadgeLabel)
        }
    }
}

@Composable
private fun InventorySectionContentOrLoading(
    isLoading: Boolean,
    stockItemsEmpty: Boolean,
    stockCountsEmpty: Boolean,
    selectedSection: InventorySection,
    viewModel: InventoryViewModel,
) {
    when {
        isLoading && stockItemsEmpty && stockCountsEmpty -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AvoqadoBrandLoader(size = 72.dp)
            }
        }
        else -> {
            SectionContent(section = selectedSection, viewModel = viewModel)
        }
    }
}

// MARK: - Section Row (matching ArticlesScreen SectionRow)

@Composable
private fun SectionRow(
    section: InventorySection,
    isSelected: Boolean,
    onClick: () -> Unit,
    tierBadgeLabel: String? = null,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left border bar for selected state
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = section.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = if (isSelected) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
        )
        if (tierBadgeLabel != null) {
            TierBadge(
                tierLabel = tierBadgeLabel,
                modifier = Modifier.padding(end = AvoqadoTheme.spacing.md),
            )
        }
    }
}

// MARK: - Plan gating (INVENTORY_TRACKING, Premium)

/**
 * Advanced inventory sections gated by INVENTORY_TRACKING. The basic stock
 * OVERVIEW stays free; METRICS is derived from the gated advanced data and
 * must be gated too (matches backend: the entire inventory suite behind
 * INVENTORY_TRACKING is a Premium-only differentiator — see
 * avoqado-server/src/routes/dashboard/inventory.routes.ts).
 */
private val inventoryGatedSections = setOf(
    InventorySection.COUNTS,
    InventorySection.PURCHASE_ORDERS,
    InventorySection.TRANSFERS,
    // Traslados entre sucursales: el backend los gatea con INVENTORY_TRACKING
    // (inventory.routes.ts monta checkFeatureAccess sobre todo el router).
    InventorySection.INTER_VENUE,
    InventorySection.METRICS,
)

/** Tier badge label for a section, or null when the section is available. */
private fun sectionTierBadge(section: InventorySection, viewModel: InventoryViewModel): String? =
    if (section in inventoryGatedSections && !viewModel.hasInventoryTracking) {
        viewModel.inventoryTierLabel
    } else {
        null
    }

// MARK: - Section Content Dispatcher

@Composable
private fun SectionContent(
    section: InventorySection,
    viewModel: InventoryViewModel,
) {
    // Blur-preview paywall: gated sections stay discoverable and PREVIEW the
    // real advanced-inventory UI (blurred + inert) behind the Premium upsell
    // card; the entitlement decision (PlanManager, fail-open) is unchanged.
    PlanGate(
        locked = section in inventoryGatedSections && !viewModel.hasInventoryTracking,
        featureName = section.label,
        requiredTierLabel = viewModel.inventoryTierLabel,
    ) {
        when (section) {
            InventorySection.OVERVIEW -> {
                val stockItems by viewModel.stockItems.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val sortOption by viewModel.sortOption.collectAsState()
                StockOverviewContent(
                    items = stockItems,
                    searchQuery = searchQuery,
                    sortOption = sortOption,
                    onSearchChange = { viewModel.updateSearch(it) },
                    onSortChange = { viewModel.updateSort(it) },
                )
            }
            InventorySection.COUNTS -> {
                val stockCounts by viewModel.stockCounts.collectAsState()
                StockCountsContent(counts = stockCounts, viewModel = viewModel)
            }
            InventorySection.PURCHASE_ORDERS -> {
                PurchaseOrdersView(viewModel = viewModel)
            }
            InventorySection.TRANSFERS -> {
                TransfersView(viewModel = viewModel)
            }
            InventorySection.INTER_VENUE -> {
                InterVenueTransfersView()
            }
            InventorySection.METRICS -> {
                InventoryMetricsView(viewModel = viewModel)
            }
        }
    }
}

// MARK: - Stock Overview (matching iOS: search + sort + item rows)

@Composable
private fun StockOverviewContent(
    items: List<StockItem>,
    searchQuery: String,
    sortOption: StockSortOption,
    onSearchChange: (String) -> Unit,
    onSortChange: (StockSortOption) -> Unit,
) {
    var showSortSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Filter by search
    val filteredItems = if (searchQuery.isBlank()) {
        items
    } else {
        val query = searchQuery.lowercase()
        items.filter {
            it.productName.lowercase().contains(query) ||
                it.sku?.lowercase()?.contains(query) == true ||
                it.gtin?.lowercase()?.contains(query) == true
        }
    }

    // Sort
    val sortedItems = when (sortOption) {
        StockSortOption.NAME_ASC -> filteredItems.sortedBy { it.productName }
        StockSortOption.NAME_DESC -> filteredItems.sortedByDescending { it.productName }
        StockSortOption.STOCK_LOW -> filteredItems.sortedBy { it.onHand }
        StockSortOption.STOCK_HIGH -> filteredItems.sortedByDescending { it.onHand }
    }

    Column {
        // Search bar + sort button (matching iOS: systemGray6 pill)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            // Search bar (pill style matching iOS)
            SearchPillField(
                query = searchQuery,
                onQueryChange = onSearchChange,
                placeholder = "Buscar por nombre, SKU o GTIN",
                modifier = Modifier.weight(1f),
            )

            // Print labels button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        Toast
                            .makeText(
                                context,
                                "Impresion no disponible",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Print,
                    contentDescription = "Imprimir etiquetas",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Sort button (matching iOS: systemGray6 square)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showSortSheet = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Ordenar",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (sortedItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    Text(
                        text = "Sin articulos con inventario",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "Intenta con otro termino de busqueda",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Los productos con seguimiento de inventario apareceran aqui",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(sortedItems, key = { it.id }) { item ->
                    StockItemRow(item = item)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    // Sort options sheet
    if (showSortSheet) {
        SortOptionsSheet(
            currentSort = sortOption,
            onSortSelected = {
                onSortChange(it)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }
}

// MARK: - Stock Item Row (matching iOS: avatar + name/SKU + stock quantities)

@Composable
private fun StockItemRow(item: StockItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Product initials avatar (matching iOS: systemGray5)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.initials,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.sku?.let { sku ->
                Text(
                    text = "SKU: $sku",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Stock quantity (matching iOS: colored based on level)
        val qtyColor = when {
            item.onHand <= 0 -> MaterialTheme.colorScheme.error
            item.isLowStock -> Warning
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(
            text = item.currentQuantityDisplay,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (item.onHand <= 0) FontWeight.SemiBold else FontWeight.Bold,
            color = qtyColor,
        )
    }
}

// MARK: - Stock Counts Content (matching iOS: count type button + list)

@Composable
private fun StockCountsContent(
    counts: List<StockCount>,
    viewModel: InventoryViewModel,
) {
    if (counts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Icon(
                    Icons.Filled.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = "No hay conteos de inventario",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Crea tu primer conteo para verificar existencias",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                PrimaryButton(
                    text = "Contar existencia",
                    onClick = { viewModel.openCountTypeSheet() },
                )
            }
        }
    } else {
        Column {
            // Header with "Contar existencia" button (matching iOS)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                horizontalArrangement = Arrangement.End,
            ) {
                PrimaryButton(
                    text = "Contar existencia",
                    onClick = { viewModel.openCountTypeSheet() },
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(counts, key = { it.id }) { count ->
                    StockCountRow(
                        count = count,
                        onTap = { viewModel.selectCountDetail(count) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// MARK: - Stock Count Row (matching iOS: colored icon + type + status)

@Composable
private fun StockCountRow(
    count: StockCount,
    onTap: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon (matching iOS: green for completed, orange for in-progress)
        val iconBg = when (count.status) {
            "COMPLETED" -> Success.copy(alpha = 0.15f)
            "IN_PROGRESS" -> Warning.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val iconTint = when (count.status) {
            "COMPLETED" -> Success
            "IN_PROGRESS" -> Warning
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Inventory2,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = count.type.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${count.statusDisplay} - ${count.itemCount} articulos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        count.createdBy?.let { staff ->
            Text(
                text = staff,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Sort Options Sheet (matching iOS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortOptionsSheet(
    currentSort: StockSortOption,
    onSortSelected: (StockSortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            Text(
                text = "Ordenar por",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.md),
            )

            StockSortOption.entries.forEach { option ->
                val isSelected = option == currentSort
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(option) }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
