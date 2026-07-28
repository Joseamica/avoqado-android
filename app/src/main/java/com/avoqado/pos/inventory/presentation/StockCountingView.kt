package com.avoqado.pos.inventory.presentation

import androidx.compose.foundation.background
import com.avoqado.pos.inventory.data.model.onHandDisplay
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.unitSuffixOf

// MARK: - Main Counting View

@Composable
fun StockCountingView(
    viewModel: InventoryViewModel,
    isTablet: Boolean,
) {
    val countItems by viewModel.countItems.collectAsState()
    val selectedIndex by viewModel.selectedItemIndex.collectAsState()
    val countedText by viewModel.countedText.collectAsState()
    val activeCountType by viewModel.activeCountType.collectAsState()
    val stockItems by viewModel.stockItems.collectAsState()
    val countableRawMaterials by viewModel.countableRawMaterials.collectAsState()

    var searchText by remember { mutableStateOf("") }
    var showAddPopup by remember { mutableStateOf(false) }

    val filteredItems = if (searchText.isBlank()) countItems
    else countItems.filter {
        it.productName.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        CountingHeader(
            onCancel = { viewModel.cancelCounting() },
            onNext = { viewModel.finishCounting() },
            hasItems = countItems.isNotEmpty(),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (isTablet) {
            // iPad-style split layout
            Row(modifier = Modifier.fillMaxSize()) {
                // Left: Item list
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    ItemListPanel(
                        items = filteredItems,
                        selectedIndex = if (searchText.isBlank()) selectedIndex else -1,
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onItemTap = { index ->
                            val realIndex = if (searchText.isBlank()) index
                            else countItems.indexOf(filteredItems[index])
                            viewModel.selectCountItem(realIndex)
                            searchText = ""
                        },
                        onAddItems = { showAddPopup = true },
                        isCycle = activeCountType == StockCountType.CYCLE,
                        stockSuggestions = if (searchText.isBlank()) emptyList()
                        else (stockItems + countableRawMaterials).filter { stock ->
                            stock.id !in countItems.map { it.productId } && (
                                stock.name.contains(searchText, ignoreCase = true) ||
                                    (stock.sku?.contains(searchText, ignoreCase = true) == true)
                                )
                        }.take(10),
                        onAddSuggestion = { stock ->
                            viewModel.addItemsToCycleCount(listOf(stock))
                            searchText = ""
                        },
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                // Right: Quantity controls + keypad
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .padding(AvoqadoTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val selectedItem = countItems.getOrNull(selectedIndex)
                    if (selectedItem != null) {
                        SelectedItemInfo(item = selectedItem)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    QuantityControls(
                        countedText = countedText,
                        onIncrement = { viewModel.incrementCount() },
                        onDecrement = { viewModel.decrementCount() },
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))

                    NumericKeypad(
                        onDigit = { digit ->
                            val updated = when (digit) {
                                "." -> when {
                                    countedText.contains(".") -> countedText
                                    countedText.isEmpty() -> "0."
                                    else -> countedText + "."
                                }
                                else -> countedText + digit
                            }
                            viewModel.updateCountedText(updated)
                        },
                        onBackspace = {
                            if (countedText.isNotEmpty()) {
                                viewModel.updateCountedText(countedText.dropLast(1))
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                    PrimaryButton(
                        text = "Guardar conteo",
                        onClick = { viewModel.moveToNextItem() },
                        enabled = selectedIndex >= 0 && countItems.isNotEmpty(),
                    )
                }
            }
        } else {
            // iPhone-style stacked layout
            Column(modifier = Modifier.fillMaxSize()) {
                // Search + add
                SearchBar(
                    searchText = searchText,
                    onSearchChange = { searchText = it },
                    onAddItems = { showAddPopup = true },
                    isCycle = activeCountType == StockCountType.CYCLE,
                )

                // Quantity controls
                val selectedItem = countItems.getOrNull(selectedIndex)
                if (selectedItem != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AvoqadoTheme.spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedItem.productName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        QuantityControls(
                            countedText = countedText,
                            onIncrement = { viewModel.incrementCount() },
                            onDecrement = { viewModel.decrementCount() },
                            compact = true,
                        )
                    }
                }

                // Keypad
                NumericKeypad(
                    onDigit = { digit ->
                        val updated = when (digit) {
                            "." -> when {
                                countedText.contains(".") -> countedText
                                countedText.isEmpty() -> "0."
                                else -> countedText + "."
                            }
                            else -> countedText + digit
                        }
                        viewModel.updateCountedText(updated)
                    },
                    onBackspace = {
                        if (countedText.isNotEmpty()) {
                            viewModel.updateCountedText(countedText.dropLast(1))
                        }
                    },
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg),
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                PrimaryButton(
                    text = "Siguiente artículo",
                    onClick = { viewModel.moveToNextItem() },
                    enabled = selectedIndex < countItems.size - 1,
                    modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg),
                )

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

                // Items list
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(filteredItems, key = { _, it -> it.productId }) { index, item ->
                        val realIndex = if (searchText.isBlank()) index
                        else countItems.indexOf(item)
                        CountItemRow(
                            item = item,
                            isSelected = realIndex == selectedIndex,
                            onTap = {
                                viewModel.selectCountItem(realIndex)
                                searchText = ""
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // Add Items Popup
    if (showAddPopup) {
        AddItemsPopup(
            stockItems = stockItems + countableRawMaterials,
            existingIds = countItems.map { it.productId }.toSet(),
            onAdd = { selected ->
                viewModel.addItemsToCycleCount(selected)
                searchText = ""
                showAddPopup = false
            },
            onDismiss = { showAddPopup = false },
        )
    }
}

// MARK: - Header

@Composable
private fun CountingHeader(
    onCancel: () -> Unit,
    onNext: () -> Unit,
    hasItems: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cancel pill button
        Surface(
            onClick = onCancel,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = "Cancelar",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Conteo de existencias",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Next pill button
        Surface(
            onClick = onNext,
            enabled = hasItems,
            shape = RoundedCornerShape(50),
            color = if (hasItems) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = "Siguiente",
                style = MaterialTheme.typography.labelLarge,
                color = if (hasItems) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
            )
        }
    }
}

// MARK: - Search Bar

@Composable
private fun SearchBar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onAddItems: () -> Unit,
    isCycle: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        TextField(
            value = searchText,
            onValueChange = onSearchChange,
            placeholder = { Text("Buscar artículos") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        )

        if (isCycle) {
            IconButton(onClick = onAddItems) {
                Icon(Icons.Filled.Add, contentDescription = "Agregar artículos")
            }
        }
    }
}

// MARK: - Item List Panel (iPad left side)

@Composable
private fun ItemListPanel(
    items: List<StockCountItem>,
    selectedIndex: Int,
    searchText: String,
    onSearchChange: (String) -> Unit,
    onItemTap: (Int) -> Unit,
    onAddItems: () -> Unit,
    isCycle: Boolean,
    stockSuggestions: List<StockItem> = emptyList(),
    onAddSuggestion: (StockItem) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            searchText = searchText,
            onSearchChange = onSearchChange,
            onAddItems = onAddItems,
            isCycle = isCycle,
        )

        val isSearching = searchText.isNotBlank()
        val hasItems = items.isNotEmpty()
        val hasSuggestions = stockSuggestions.isNotEmpty()

        when {
            // Empty state: no items and no search
            !hasItems && !isSearching -> {
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Text(
                            text = "Busca o agrega artículos para contar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isCycle) {
                            PrimaryButton(
                                text = "Agregar artículos",
                                onClick = onAddItems,
                                )
                        }
                    }
                }
            }
            // Searching with no matches and no suggestions
            isSearching && !hasItems && !hasSuggestions -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Sin resultados para \"$searchText\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Show items + (when searching) suggestions
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (hasItems) {
                        itemsIndexed(items, key = { _, it -> "count_${it.productId}" }) { index, item ->
                            CountItemRow(
                                item = item,
                                isSelected = index == selectedIndex,
                                onTap = { onItemTap(index) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (isSearching && hasSuggestions) {
                        item(key = "suggestions_header") {
                            Text(
                                text = "Agregar al conteo",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = AvoqadoTheme.spacing.lg,
                                    vertical = AvoqadoTheme.spacing.sm,
                                ),
                            )
                        }
                        items(stockSuggestions, key = { "stock_${it.id}" }) { stock ->
                            StockSuggestionRow(
                                stock = stock,
                                onTap = { onAddSuggestion(stock) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Stock Suggestion Row (tap to add to count)

@Composable
private fun StockSuggestionRow(
    stock: StockItem,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stock.name.take(2).uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stock.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stock.sku != null) {
                Text(
                    text = stock.sku,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Icon(
            Icons.Filled.Add,
            contentDescription = "Agregar",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

// MARK: - Count Item Row

@Composable
private fun CountItemRow(
    item: StockCountItem,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                else Modifier,
            )
            .clickable(onClick = onTap)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Initials avatar
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.productName.take(2).uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.sku != null) {
                Text(
                    text = item.sku,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Counted badge
        if (item.counted > 0) {
            Surface(
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = if (item.counted == item.counted.toLong().toDouble()) {
                        item.counted.toLong().toString()
                    } else {
                        String.format(java.util.Locale.US, "%.2f", item.counted)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// MARK: - Selected Item Info (iPad right panel)

@Composable
private fun SelectedItemInfo(item: StockCountItem) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md),
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.productName.take(2).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        Text(
            text = item.productName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        if (item.isIngredient) {
            Text(
                text = "Insumo" + unitSuffixOf(item.unit).let { if (it.isNotEmpty()) " ·${it}" else "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (item.sku != null) {
            Text(
                text = "SKU: ${item.sku}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Esperado: ${item.expectedDisplay}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Quantity Controls

@Composable
private fun QuantityControls(
    countedText: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    compact: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.xl),
    ) {
        // Minus button
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(if (compact) 36.dp else 48.dp)
                .clickable(onClick = onDecrement),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = "Menos",
                    modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                )
            }
        }

        // Current count display
        Text(
            text = countedText.ifEmpty { "0" },
            style = if (compact) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(if (compact) 60.dp else 120.dp),
            textAlign = TextAlign.Center,
        )

        // Plus button
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(if (compact) 36.dp else 48.dp)
                .clickable(onClick = onIncrement),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Mas",
                    modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                )
            }
        }
    }
}

// MARK: - Numeric Keypad

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                row.forEach { key ->
                    Surface(
                        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable {
                                if (key == "⌫") onBackspace()
                                else onDigit(key)
                            },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (key == "⌫") {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Borrar",
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Add Items Popup

@Composable
private fun AddItemsPopup(
    stockItems: List<StockItem>,
    existingIds: Set<String>,
    onAdd: (List<StockItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by remember { mutableStateOf("") }
    val selectedItems = remember { mutableStateListOf<StockItem>() }

    val available = stockItems.filter { it.id !in existingIds }
    val filtered = if (searchText.isBlank()) available
    else available.filter {
        it.name.contains(searchText, ignoreCase = true) ||
            (it.sku?.contains(searchText, ignoreCase = true) == true)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Agregar artículos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = { onAdd(selectedItems.toList()) },
                    enabled = selectedItems.isNotEmpty(),
                ) {
                    Text("Agregar (${selectedItems.size})")
                }
            }

            HorizontalDivider()

            // Search
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Buscar por nombre o SKU") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            )

            // Items list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
            ) {
                items(filtered.size, key = { filtered[it].id }) { index ->
                    val item = filtered[index]
                    val isSelected = item in selectedItems

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selectedItems.remove(item)
                                else selectedItems.add(item)
                            }
                            .padding(vertical = AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (isSelected) selectedItems.remove(item)
                                else selectedItems.add(item)
                            },
                        )

                        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (item.sku != null) {
                                Text(
                                    text = item.sku,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Text(
                            text = "En mano: ${item.onHandDisplay}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
