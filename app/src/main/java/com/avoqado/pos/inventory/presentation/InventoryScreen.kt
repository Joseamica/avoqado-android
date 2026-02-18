package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.StockSortOption

@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel = hiltViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val stockItems by viewModel.stockItems.collectAsState()
    val stockCounts by viewModel.stockCounts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Inventario",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
        )

        TabRow(selectedTabIndex = InventoryTab.entries.indexOf(selectedTab)) {
            InventoryTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        when {
            isLoading && stockItems.isEmpty() && stockCounts.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            selectedTab == InventoryTab.OVERVIEW -> {
                StockOverviewContent(
                    items = stockItems,
                    searchQuery = searchQuery,
                    sortOption = sortOption,
                    onSearchChange = { viewModel.updateSearch(it) },
                    onSortChange = { viewModel.updateSort(it) },
                )
            }
            selectedTab == InventoryTab.COUNTS -> {
                StockCountsContent(counts = stockCounts)
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
        // Search bar + sort button (matching iOS)
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
            // Search bar
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Buscar por nombre, SKU o GTIN") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                    ),
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Limpiar",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Sort button (matching iOS: arrow.up.arrow.down)
            IconButton(onClick = { showSortSheet = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Ordenar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Text(
                        text = "No hay productos con inventario",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "Intenta con otro termino de busqueda",
                            style = MaterialTheme.typography.bodySmall,
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
                    HorizontalDivider()
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
        // Product initials avatar (matching iOS)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
            item.isLowStock -> Warning
            item.onHand > 0 -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.error
        }
        Text(
            text = "${item.currentQuantity}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = qtyColor,
        )
    }
}

// MARK: - Stock Counts Content (matching iOS: count type button + list)

@Composable
private fun StockCountsContent(counts: List<StockCount>) {
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
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    text = "No hay conteos de inventario",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Crea tu primer conteo para verificar existencias",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                PrimaryButton(
                    text = "Contar existencia",
                    onClick = { /* TODO: open StockCountTypeSheet */ },
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
                    onClick = { /* TODO: open StockCountTypeSheet */ },
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(counts, key = { it.id }) { count ->
                    StockCountRow(count = count)
                    HorizontalDivider()
                }
            }
        }
    }
}

// MARK: - Stock Count Row (matching iOS: colored icon + type + status)

@Composable
private fun StockCountRow(count: StockCount) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* TODO: open StockCountDetailView */ }
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
                HorizontalDivider()
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}
