package com.avoqado.pos.transactions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.model.AmountOperator
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionActiveFilter
import com.avoqado.pos.transactions.data.model.TransactionFilters
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

@Composable
fun TransactionsScreen(
    isTablet: Boolean = false,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val activeFilter by viewModel.activeFilter.collectAsState()
    val showRefundSheet by viewModel.showRefundSheet.collectAsState()
    val adaptive = AvoqadoTheme.adaptive
    val denseTabletLandscape = !adaptive.isPortrait && adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (isTablet) {
                TabletTransactionsLayout(viewModel = viewModel, denseTabletLandscape = denseTabletLandscape)
            } else {
                PhoneTransactionsLayout(viewModel = viewModel, denseTabletLandscape = denseTabletLandscape)
            }
        }
    }

    // Filter bottom sheets
    FilterSheets(viewModel = viewModel, activeFilter = activeFilter)

    // Unassociated refund bottom sheet — DISABLED, see TODO at the refund button above.
    // The ViewModel state and sheet code are kept intact so re-enabling is just a matter
    // of uncommenting the button once the backend bugs are fixed.
}

// MARK: - Tablet Layout (40/60 split)

@Composable
private fun TabletTransactionsLayout(
    viewModel: TransactionsViewModel,
    denseTabletLandscape: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val listWidth = if (denseTabletLandscape) maxWidth * 0.37f else maxWidth * 0.4f

        Row(modifier = Modifier.fillMaxSize()) {
            // Left panel: transaction list (40%)
            TransactionListPanel(
                viewModel = viewModel,
                isTablet = true,
                denseTabletLandscape = denseTabletLandscape,
                modifier = Modifier
                    .width(listWidth)
                    .fillMaxHeight(),
            )

            // Hairline divider
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // Right panel: detail (60%)
            TransactionDetailPanel(
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneTransactionsLayout(
    viewModel: TransactionsViewModel,
    denseTabletLandscape: Boolean,
) {
    val selectedTransaction by viewModel.selectedTransaction.collectAsState()

    if (selectedTransaction != null) {
        // Show detail full screen on phone
        TransactionDetailPanel(
            viewModel = viewModel,
            showBackButton = true,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        TransactionListPanel(
            viewModel = viewModel,
            isTablet = false,
            denseTabletLandscape = denseTabletLandscape,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// MARK: - Transaction List Panel

@Composable
private fun TransactionListPanel(
    viewModel: TransactionsViewModel,
    isTablet: Boolean,
    denseTabletLandscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val transactions by viewModel.transactions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val selectedTransactionId by viewModel.selectedTransactionId.collectAsState()

    // Auto-refresh every time this composable enters composition
    val refreshKey = remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey.intValue) {
        viewModel.refresh()
    }
    // Increment key when lifecycle resumes (tab re-selected)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshKey.intValue++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filteredTransactions = remember(transactions, filters) {
        if (filters.hasActiveFilters) filters.apply(transactions) else transactions
    }
    val grouped = remember(filteredTransactions) {
        filteredTransactions.groupBy { it.dateGroup }.toList()
    }
    val adaptive = AvoqadoTheme.adaptive
    val denseList = adaptive.isAggressiveCompact || denseTabletLandscape
    val horizontalPadding = if (denseList) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val searchHeight = if (denseList) 36.dp else adaptive.listSearchFieldHeight
    val searchVerticalPadding = if (denseList) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm
    val dateHeaderTopPadding = if (denseList) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.xl
    val dateHeaderBottomPadding = if (denseList) AvoqadoTheme.spacing.xxs else AvoqadoTheme.spacing.sm

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        // Header (tablet only)
        if (isTablet) {
            Text(
                text = "Transacciones",
                style = if (denseList) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = if (denseList) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.xl,
                    bottom = dateHeaderBottomPadding,
                ),
            )
        }

        // TODO [HIGH RISK - DISABLED]: Unassociated Refund button
        //
        // This feature is HIDDEN until the backend financial integrity bugs are fixed.
        //
        // **WHY THIS IS DISABLED**
        // Creating an unassociated refund today causes 5 critical data inconsistencies in
        // the backend reports because the aggregation queries were written assuming all
        // Payments have positive amounts and status='COMPLETED'. Refund Payments have a
        // NEGATIVE amount and status='REFUNDED', so they break every report that touches
        // money.
        //
        // **CRITICAL BUGS (server) — must be fixed before re-enabling:**
        //
        // 1. sales-summary.dashboard.service.ts:150-160
        //    Sums REFUNDED payment amounts WITHOUT Math.abs(). Negative refunds end up
        //    being SUBTRACTED from a SUBTRACTION, so gross sales are INFLATED by the
        //    refund amount. Every refund overstates revenue.
        //
        // 2. cashCloseout.dashboard.service.ts:66-74
        //    Cash closeout filters Payment by status='COMPLETED' only. REFUNDED payments
        //    are skipped, so the expected cash count is WRONG when a refund happened
        //    during the shift. Cashier sees a fake "missing money" variance.
        //
        // 3. moneygiver-settlement.job.ts:265
        //    Settlement job filters by status='COMPLETED'. Card refunds are excluded from
        //    the merchant settlement report, so processor reconciliation is wrong.
        //
        // 4. availableBalance.dashboard.service.ts:102-113, 248-261
        //    Available cash balance only counts COMPLETED payments. Refunds are missing,
        //    so the dashboard shows MORE cash than the venue actually has.
        //
        // 5. refund.mobile.service.ts:97-117 (potential double-count)
        //    Each refund creates BOTH a negative Payment AND a CashDrawerEvent PAY_OUT.
        //    Today they don't double-count because most queries filter REFUNDED out, but
        //    when the bugs above are fixed by including REFUNDED, this will start double-
        //    counting. Need to pick ONE source of truth (Payment OR CashDrawerEvent).
        //
        // **ALSO BROKEN**
        // - The Android client sends `items` in the refund body (line 80 of
        //   RefundRepository.kt) but the backend controller never reads them. Inventory
        //   is NEVER restored on a refund, even though the UI lets you add products.
        //
        // **RE-ENABLING CHECKLIST**
        // Before uncommenting this button:
        //   [ ] Fix the 5 server bugs above (with tests).
        //   [ ] Decide single source of truth for cash impact (Payment vs CashDrawerEvent).
        //   [ ] Pass `items` from controller -> service and restore inventory atomically
        //       with the financial records (single Prisma transaction).
        //   [ ] Verify a sale + refund cycle leaves: gross sales unchanged, net sales
        //       reduced, cash drawer balanced, inventory restored, no duplicates in any
        //       report.
        //   [ ] Rename UI label to "Reembolso sin ticket" (more user-friendly than the
        //       current technical "Reembolso desasociado").
        //
        // See the auditoria-cambios output from 2026-04-07 for the full breakdown.
        /*
        Button(
            onClick = { viewModel.showRefundSheet() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.sm,
                )
                .height(AvoqadoTheme.dimensions.buttonLarge),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                text = "Reembolso desasociado",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        */

        // Search bar
        SearchBar(
            value = searchText,
            onValueChange = { viewModel.setSearchText(it) },
            height = searchHeight,
            modifier = Modifier.padding(
                horizontal = horizontalPadding,
                vertical = searchVerticalPadding,
            ),
        )

        // Filter pills
        TransactionFilterBar(
            filters = filters,
            dense = denseList,
            onFilterTap = { viewModel.setActiveFilter(it) },
            onFilterClear = { filter ->
                val updated = when (filter) {
                    TransactionActiveFilter.METHOD -> filters.copy(methods = emptySet())
                    TransactionActiveFilter.STAFF -> filters.copy(staffNames = emptySet())
                    TransactionActiveFilter.SUBTOTAL -> filters.copy(
                        subtotalOperator = null, subtotalValue1 = null, subtotalValue2 = null,
                    )
                    TransactionActiveFilter.TIP -> filters.copy(
                        tipOperator = null, tipValue1 = null, tipValue2 = null,
                    )
                    TransactionActiveFilter.TOTAL -> filters.copy(
                        totalOperator = null, totalValue1 = null, totalValue2 = null,
                    )
                }
                viewModel.updateFilters(updated)
            },
            onClearAll = { viewModel.clearAllFilters() },
        )

        // Content
        when {
            isLoading && transactions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            filteredTransactions.isEmpty() -> {
                EmptyState(
                    hasFilters = filters.hasActiveFilters,
                    searchText = searchText,
                )
            }
            else -> {
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = horizontalPadding),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (dateGroup, groupTransactions) ->
                        item(key = "header_$dateGroup") {
                            Text(
                                text = dateGroup,
                                style = if (denseList) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    top = dateHeaderTopPadding,
                                    bottom = dateHeaderBottomPadding,
                                ),
                            )
                        }

                        items(groupTransactions, key = { it.id }) { transaction ->
                            val isSelected = isTablet && selectedTransactionId == transaction.id

                            TransactionRow(
                                transaction = transaction,
                                dense = denseList,
                                isSelected = isSelected,
                                onClick = { viewModel.selectTransaction(transaction.id) },
                            )

                            // Trigger load-more
                            LaunchedEffect(transaction.id) {
                                viewModel.loadMoreIfNeeded(transaction)
                            }
                        }
                    }

                    // Loading more indicator
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(if (denseList) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Search Bar

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    SearchPillField(
        query = value,
        onQueryChange = onValueChange,
        placeholder = "Buscar transacciones...",
        modifier = modifier.fillMaxWidth(),
        height = height,
    )
}

// MARK: - Transaction Row

@Composable
private fun TransactionRow(
    transaction: Transaction,
    dense: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val rowVerticalPadding = if (dense) AvoqadoTheme.spacing.xxs else AvoqadoTheme.spacing.xs
    val iconBoxSize = if (dense) 34.dp else 40.dp
    val paymentIconSize = if (dense) 14.dp else 16.dp
    val rowGap = if (dense) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(vertical = rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon box
        Box(
            modifier = Modifier
                .size(iconBoxSize)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = paymentIcon(transaction.paymentMethod),
                contentDescription = null,
                modifier = Modifier.size(paymentIconSize),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(rowGap))

        // Amount + method
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.totalDisplay,
                style = if (dense) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = transaction.methodDescription,
                style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Time
        transaction.timeDisplay?.let { time ->
            Text(
                text = time,
                style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = if (dense) 42.dp else 52.dp),
    )
}

// MARK: - Empty State

@Composable
private fun EmptyState(
    hasFilters: Boolean,
    searchText: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.CompareArrows,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "Sin transacciones",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = when {
                    hasFilters -> "No se encontraron resultados con los filtros seleccionados"
                    searchText.isNotEmpty() -> "No se encontraron resultados para \"$searchText\""
                    else -> "Las transacciones aparecerán aquí"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: - Filter Pill Bar

@Composable
private fun TransactionFilterBar(
    filters: TransactionFilters,
    dense: Boolean = false,
    onFilterTap: (TransactionActiveFilter) -> Unit,
    onFilterClear: (TransactionActiveFilter) -> Unit,
    onClearAll: () -> Unit,
) {
    val pillSpacing = if (dense) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm
    val verticalPadding = if (dense) AvoqadoTheme.spacing.xxs else AvoqadoTheme.spacing.xs

    LazyRow(
        contentPadding = PaddingValues(horizontal = if (dense) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(pillSpacing),
        modifier = Modifier.padding(vertical = verticalPadding),
    ) {
        // Método
        item {
            FilterPill(
                label = "Método",
                dense = dense,
                isActive = filters.methods.isNotEmpty(),
                activeValue = if (filters.methods.isNotEmpty()) "${filters.methods.size}" else null,
                onTap = { onFilterTap(TransactionActiveFilter.METHOD) },
                onClear = { onFilterClear(TransactionActiveFilter.METHOD) },
            )
        }
        // Mesero
        item {
            FilterPill(
                label = "Mesero",
                dense = dense,
                isActive = filters.staffNames.isNotEmpty(),
                activeValue = if (filters.staffNames.isNotEmpty()) "${filters.staffNames.size}" else null,
                onTap = { onFilterTap(TransactionActiveFilter.STAFF) },
                onClear = { onFilterClear(TransactionActiveFilter.STAFF) },
            )
        }
        // Subtotal
        item {
            FilterPill(
                label = "Subtotal",
                dense = dense,
                isActive = filters.subtotalOperator != null,
                activeValue = filters.subtotalOperator?.let { op ->
                    filters.subtotalValue1?.let { v1 -> op.summary(v1, filters.subtotalValue2) }
                },
                onTap = { onFilterTap(TransactionActiveFilter.SUBTOTAL) },
                onClear = { onFilterClear(TransactionActiveFilter.SUBTOTAL) },
            )
        }
        // Propina
        item {
            FilterPill(
                label = "Propina",
                dense = dense,
                isActive = filters.tipOperator != null,
                activeValue = filters.tipOperator?.let { op ->
                    filters.tipValue1?.let { v1 -> op.summary(v1, filters.tipValue2) }
                },
                onTap = { onFilterTap(TransactionActiveFilter.TIP) },
                onClear = { onFilterClear(TransactionActiveFilter.TIP) },
            )
        }
        // Total
        item {
            FilterPill(
                label = "Total",
                dense = dense,
                isActive = filters.totalOperator != null,
                activeValue = filters.totalOperator?.let { op ->
                    filters.totalValue1?.let { v1 -> op.summary(v1, filters.totalValue2) }
                },
                onTap = { onFilterTap(TransactionActiveFilter.TOTAL) },
                onClear = { onFilterClear(TransactionActiveFilter.TOTAL) },
            )
        }
        // Clear all
        if (filters.hasActiveFilters) {
            item {
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier.size(if (dense) 28.dp else 32.dp),
                ) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = "Limpiar filtros",
                        modifier = Modifier.size(if (dense) 14.dp else 16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// MARK: - Filter Pill

@Composable
private fun FilterPill(
    label: String,
    dense: Boolean,
    isActive: Boolean,
    activeValue: String?,
    onTap: () -> Unit,
    onClear: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.onSurface
    val onPrimaryColor = MaterialTheme.colorScheme.surface
    val dashedBorderColor = MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(if (dense) 16.dp else 20.dp))
            .then(
                if (isActive) {
                    Modifier.background(primaryColor)
                } else {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = dashedBorderColor,
                            cornerRadius = CornerRadius(20.dp.toPx()),
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                                    0f,
                                ),
                            ),
                        )
                    }
                },
            )
            .clickable(onClick = onTap)
            .padding(
                horizontal = if (dense) 10.dp else 12.dp,
                vertical = if (dense) 5.dp else 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (dense) 3.dp else 4.dp),
    ) {
        if (isActive) {
            Text(
                text = label,
                fontSize = if (dense) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                color = onPrimaryColor,
            )
            activeValue?.let {
                Text(
                    text = it,
                    fontSize = if (dense) 11.sp else 13.sp,
                    color = onPrimaryColor.copy(alpha = 0.8f),
                )
            }
            Box(
                modifier = Modifier
                    .size(if (dense) 14.dp else 16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Limpiar",
                    modifier = Modifier.size(if (dense) 9.dp else 10.dp),
                    tint = onPrimaryColor.copy(alpha = 0.7f),
                )
            }
        } else {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(if (dense) 10.dp else 11.dp),
                tint = primaryColor,
            )
            Text(
                text = label,
                fontSize = if (dense) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                color = primaryColor,
            )
        }
    }
}

// MARK: - Filter Bottom Sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheets(
    viewModel: TransactionsViewModel,
    activeFilter: TransactionActiveFilter?,
) {
    val filters by viewModel.filters.collectAsState()

    when (activeFilter) {
        TransactionActiveFilter.METHOD -> {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setActiveFilter(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                FilterCheckboxContent(
                    title = "Método",
                    options = viewModel.methodOptions(),
                    selection = filters.methods,
                    onSelectionChange = { viewModel.updateFilters(filters.copy(methods = it)) },
                    onDismiss = { viewModel.setActiveFilter(null) },
                )
            }
        }
        TransactionActiveFilter.STAFF -> {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setActiveFilter(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                FilterCheckboxContent(
                    title = "Mesero",
                    options = viewModel.staffOptions(),
                    selection = filters.staffNames,
                    onSelectionChange = { viewModel.updateFilters(filters.copy(staffNames = it)) },
                    onDismiss = { viewModel.setActiveFilter(null) },
                )
            }
        }
        TransactionActiveFilter.SUBTOTAL -> {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setActiveFilter(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                FilterAmountContent(
                    title = "Subtotal",
                    selectedOperator = filters.subtotalOperator,
                    value1 = filters.subtotalValue1,
                    value2 = filters.subtotalValue2,
                    onApply = { op, v1, v2 ->
                        viewModel.updateFilters(
                            filters.copy(subtotalOperator = op, subtotalValue1 = v1, subtotalValue2 = v2),
                        )
                        viewModel.setActiveFilter(null)
                    },
                    onClear = {
                        viewModel.updateFilters(
                            filters.copy(subtotalOperator = null, subtotalValue1 = null, subtotalValue2 = null),
                        )
                        viewModel.setActiveFilter(null)
                    },
                    onDismiss = { viewModel.setActiveFilter(null) },
                )
            }
        }
        TransactionActiveFilter.TIP -> {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setActiveFilter(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                FilterAmountContent(
                    title = "Propina",
                    selectedOperator = filters.tipOperator,
                    value1 = filters.tipValue1,
                    value2 = filters.tipValue2,
                    onApply = { op, v1, v2 ->
                        viewModel.updateFilters(
                            filters.copy(tipOperator = op, tipValue1 = v1, tipValue2 = v2),
                        )
                        viewModel.setActiveFilter(null)
                    },
                    onClear = {
                        viewModel.updateFilters(
                            filters.copy(tipOperator = null, tipValue1 = null, tipValue2 = null),
                        )
                        viewModel.setActiveFilter(null)
                    },
                    onDismiss = { viewModel.setActiveFilter(null) },
                )
            }
        }
        TransactionActiveFilter.TOTAL -> {
            ModalBottomSheet(
                onDismissRequest = { viewModel.setActiveFilter(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                FilterAmountContent(
                    title = "Total",
                    selectedOperator = filters.totalOperator,
                    value1 = filters.totalValue1,
                    value2 = filters.totalValue2,
                    onApply = { op, v1, v2 ->
                        viewModel.updateFilters(
                            filters.copy(totalOperator = op, totalValue1 = v1, totalValue2 = v2),
                        )
                        viewModel.setActiveFilter(null)
                    },
                    onClear = {
                        viewModel.updateFilters(
                            filters.copy(totalOperator = null, totalValue1 = null, totalValue2 = null),
                        )
                        viewModel.setActiveFilter(null)
                    },
                    onDismiss = { viewModel.setActiveFilter(null) },
                )
            }
        }
        null -> { /* no sheet */ }
    }
}

// MARK: - Filter Checkbox Content

@Composable
private fun FilterCheckboxContent(
    title: String,
    options: List<Pair<String, String>>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Options
        options.forEach { (key, label) ->
            val isChecked = key in selection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newSelection = if (isChecked) selection - key else selection + key
                        onSelectionChange(newSelection)
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (isChecked) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isChecked) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// MARK: - Filter Amount Content

@Composable
private fun FilterAmountContent(
    title: String,
    selectedOperator: AmountOperator?,
    value1: Double?,
    value2: Double?,
    onApply: (AmountOperator, Double, Double?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var localOp by remember { mutableStateOf(selectedOperator ?: AmountOperator.GREATER_THAN) }
    var localV1 by remember { mutableStateOf(value1?.let { String.format("%.0f", it) } ?: "") }
    var localV2 by remember { mutableStateOf(value2?.let { String.format("%.0f", it) } ?: "") }

    Column {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Segmented operator selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AmountOperator.entries.forEach { op ->
                val isSelected = localOp == op
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surface
                            else Color.Transparent,
                        )
                        .clickable { localOp = op }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = op.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Amount input(s)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // First field
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = localV1,
                    onValueChange = { localV1 = it },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                    ),
                )
            }

            if (localOp == AmountOperator.BETWEEN) {
                Text("y", color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextField(
                        value = localV2,
                        onValueChange = { localV2 = it },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Limpiar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onClear() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Limpiar",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Aplicar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable {
                        val v = localV1.toDoubleOrNull() ?: return@clickable
                        val v2 = if (localOp == AmountOperator.BETWEEN) localV2.toDoubleOrNull() else null
                        onApply(localOp, v, v2)
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Aplicar",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// MARK: - Helpers

private fun paymentIcon(method: String?): ImageVector {
    return when (method) {
        "CASH" -> Icons.Filled.Payments
        "CARD", "CREDIT_CARD", "DEBIT_CARD" -> Icons.Filled.CreditCard
        else -> Icons.Filled.Description
    }
}
