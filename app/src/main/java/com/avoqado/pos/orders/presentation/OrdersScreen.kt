package com.avoqado.pos.orders.presentation

import androidx.compose.foundation.background
import com.avoqado.pos.core.util.Plurales
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.avoqado.pos.designsystem.components.AvoqadoRefreshable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Info
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.data.model.OrderDetailItem
import com.avoqado.pos.orders.data.model.OrderPaymentInfo
import com.avoqado.pos.orders.data.model.OrderStatus
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.model.PaymentStatus
import androidx.compose.runtime.remember
import java.util.Locale

// MARK: - File-level constants

private data class FilterOption(val label: String, val value: String?)

private val statusFilters = listOf(
    FilterOption("Todos", null),
    FilterOption("Pendientes", "PENDING"),
    FilterOption("Completados", "COMPLETED"),
    FilterOption("Cancelados", "CANCELLED"),
)

// Status badge color constants (avoids Color.copy() allocations on every recomposition)
private val OrderPendingBg = Warning.copy(alpha = 0.15f)
private val OrderCompletedBg = Success.copy(alpha = 0.15f)
private val OrderConfirmedBg = Info.copy(alpha = 0.15f)
private val OrderCancelledBg = Error.copy(alpha = 0.15f)
private val PaymentPendingBg = Warning.copy(alpha = 0.15f)
private val PaymentPartialBg = Info.copy(alpha = 0.15f)
private val PaymentPaidBg = Success.copy(alpha = 0.15f)
private val WarningSubtleBg = Warning.copy(alpha = 0.1f)

private val detailDateOutputFormatter = java.time.format.DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm", Locale("es", "MX"))

// MARK: - Main Entry Point

@Composable
fun OrdersScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    // Overlay del Más (spec de refresco §4.8): LaunchedEffect cubre el "al
    // mostrarse" (el ON_RESUME de la Activity NO dispara al abrir un overlay)
    // y LifecycleResumeEffect el regreso desde background. La duplicación la
    // absorbe el single-flight del gate.
    LaunchedEffect(Unit) { viewModel.autoRefresh() }
    LifecycleResumeEffect(Unit) {
        viewModel.autoRefresh()
        onPauseOrDispose { }
    }

    if (isTablet) {
        TabletOrdersLayout(viewModel = viewModel, onDismiss = onDismiss)
    } else {
        PhoneOrdersLayout(viewModel = viewModel, onDismiss = onDismiss)
    }
}

// MARK: - Tablet Layout (40/60 split)

@Composable
private fun TabletOrdersLayout(
    viewModel: OrdersViewModel,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val listWidth = maxWidth * 0.4f

        Row(modifier = Modifier.fillMaxSize()) {
            // Left panel: order list (40%)
            OrderListView(
                viewModel = viewModel,
                isTablet = true,
                onBack = onDismiss,
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
            OrderDetailPanel(
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
private fun PhoneOrdersLayout(
    viewModel: OrdersViewModel,
    onDismiss: () -> Unit,
) {
    val selectedOrder by viewModel.selectedOrder.collectAsState()
    val selectedOrderId by viewModel.selectedOrderId.collectAsState()

    if (selectedOrderId != null && selectedOrder != null) {
        // Show detail full screen on phone
        OrderDetailView(
            order = selectedOrder!!,
            onBack = { viewModel.clearSelection() },
            modifier = Modifier.fillMaxSize(),
        )
    } else if (selectedOrderId != null && selectedOrder == null) {
        // Loading detail
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AvoqadoBrandLoader(size = 72.dp)
        }
    } else {
        OrderListView(
            viewModel = viewModel,
            isTablet = false,
            onBack = onDismiss,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// MARK: - Order List View

@Composable
private fun OrderListView(
    viewModel: OrdersViewModel,
    isTablet: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val orders by viewModel.orders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selectedOrderId by viewModel.selectedOrderId.collectAsState()

    val grouped = remember(orders) { viewModel.groupOrdersByDate(orders) }

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AvoqadoTheme.spacing.lg,
                    end = AvoqadoTheme.spacing.lg,
                    top = AvoqadoTheme.spacing.xl,
                    bottom = AvoqadoTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Text(
                text = "Pedidos",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
            )
        }

        // Search bar
        OrderSearchBar(
            value = searchText,
            onValueChange = { viewModel.updateSearch(it) },
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.sm,
            ),
        )

        // Status filter pills
        StatusFilterRow(
            currentFilter = statusFilter,
            onFilterSelected = { viewModel.setStatusFilter(it) },
        )

        // Content — envuelto con el gesto de refresco (spec §4.7):
        // isRefreshing = SOLO el gesto manual, nunca la carga inicial.
        val isManualRefreshing by viewModel.isManualRefreshing.collectAsState()
        AvoqadoRefreshable(
            isRefreshing = isManualRefreshing,
            onRefresh = viewModel::manualRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
        when {
            isLoading && orders.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AvoqadoBrandLoader(size = 72.dp)
                }
            }
            orders.isEmpty() -> {
                OrdersEmptyState()
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (dateGroup, groupOrders) ->
                        item(key = "header_$dateGroup") {
                            Text(
                                text = dateGroup.uppercase(Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    top = AvoqadoTheme.spacing.xl,
                                    bottom = AvoqadoTheme.spacing.sm,
                                ),
                            )
                        }

                        itemsIndexed(groupOrders, key = { _, order -> order.id }) { index, order ->
                            val isSelected = isTablet && selectedOrderId == order.id

                            OrderRow(
                                order = order,
                                isSelected = isSelected,
                                onClick = { viewModel.selectOrder(order.id) },
                            )

                            // Trigger load-more when last item is visible
                            if (index == groupOrders.lastIndex && dateGroup == grouped.lastOrNull()?.first) {
                                LaunchedEffect(order.id) {
                                    viewModel.loadMore()
                                }
                            }
                        }
                    }

                    // Loading more indicator
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AvoqadoTheme.spacing.lg),
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
}

// MARK: - Order Detail Panel (tablet right side)

@Composable
private fun OrderDetailPanel(
    viewModel: OrdersViewModel,
    modifier: Modifier = Modifier,
) {
    val selectedOrder by viewModel.selectedOrder.collectAsState()
    val isLoadingDetail by viewModel.isLoadingDetail.collectAsState()

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        when {
            isLoadingDetail -> {
                AvoqadoBrandLoader(
                    modifier = Modifier.align(Alignment.Center),
                    size = 72.dp,
                )
            }
            selectedOrder != null -> {
                OrderDetailView(
                    order = selectedOrder!!,
                    onBack = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                // Empty state (tablet right panel)
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Selecciona un pedido",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// MARK: - Order Detail View

@Composable
fun OrderDetailView(
    order: OrderDetail,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AvoqadoTheme.spacing.lg,
                    end = AvoqadoTheme.spacing.lg,
                    top = AvoqadoTheme.spacing.xl,
                    bottom = AvoqadoTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                CircleBackButton(onClick = onBack)
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            }
            Text(
                text = "Pedido #${order.orderNumber}",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            // Status section
            OrderStatusSection(order = order)

            // Items section
            if (!order.items.isNullOrEmpty()) {
                OrderItemsSection(items = order.items)
            }

            // Totals section
            OrderTotalsSection(order = order)

            // Payments section
            if (!order.payments.isNullOrEmpty()) {
                OrderPaymentsSection(payments = order.payments)
            }

            // Info section
            OrderInfoSection(order = order)

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xl))
        }
    }
}

// MARK: - Status Section

@Composable
private fun OrderStatusSection(order: OrderDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        // Badge row
        Row(
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OrderStatusBadge(status = order.orderStatus)
            PaymentStatusBadge(status = order.orderPaymentStatus)
            order.orderType?.let { type ->
                OrderTypeBadge(label = type.label)
            }
        }

        // Date
        val formattedDate = formatDetailDate(order.createdAt)
        if (formattedDate.isNotEmpty()) {
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Special requests
        if (!order.specialRequests.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = WarningSubtleBg,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            ) {
                Text(
                    text = order.specialRequests,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(AvoqadoTheme.spacing.md),
                )
            }
        }
    }
}

// MARK: - Items Section

@Composable
private fun OrderItemsSection(items: List<OrderDetailItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        DetailSectionHeader(title = "ARTÍCULOS")

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AvoqadoTheme.spacing.md),
                    ) {
                        // Item row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Quantity badge
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${item.quantity}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.surface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            }

                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))

                            Text(
                                text = item.productName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )

                            Text(
                                text = "$%,.2f".format(item.total),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        // Modifiers
                        if (!item.modifiers.isNullOrEmpty()) {
                            item.modifiers.forEach { modifier ->
                                Row(
                                    modifier = Modifier.padding(start = 30.dp, top = 2.dp),
                                ) {
                                    Text(
                                        text = "+ ${modifier.name}" + if (modifier.price > 0) " (+$%,.2f)".format(modifier.price) else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Notes
                        if (!item.notes.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
                            ) {
                                Text(
                                    text = "Nota: ${item.notes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// MARK: - Totals Section

@Composable
private fun OrderTotalsSection(order: OrderDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        DetailSectionHeader(title = "TOTAL")

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
            ) {
                TotalRow(label = "Subtotal", amount = "$%,.2f".format(order.subtotal))
                TotalRow(label = "IVA", amount = "$%,.2f".format(order.taxAmount))
                if (order.discountAmount > 0) {
                    TotalRow(label = "Descuento", amount = "-$%,.2f".format(order.discountAmount))
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xs),
                )

                // Total (bold)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Total",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$%,.2f".format(order.total),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Payments Section

@Composable
private fun OrderPaymentsSection(payments: List<OrderPaymentInfo>) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        DetailSectionHeader(title = "PAGOS")

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        ) {
            Column {
                payments.forEachIndexed { index, payment ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AvoqadoTheme.spacing.md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = paymentMethodIcon(payment.method),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                            Text(
                                text = payment.methodLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "$%,.2f".format(payment.amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                            Text(
                                text = payment.timeDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (payment.tipAmount > 0) {
                            Text(
                                text = "+ $%,.2f propina".format(payment.tipAmount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                            )
                        }

                        if (!payment.processedBy.isNullOrBlank()) {
                            Text(
                                text = "Procesado por: ${payment.processedBy}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                            )
                        }
                    }

                    if (index < payments.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// MARK: - Info Section

@Composable
private fun OrderInfoSection(order: OrderDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        DetailSectionHeader(title = "INFORMACIÓN")

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                if (!order.staffName.isNullOrBlank()) {
                    InfoRow(label = "Atendido por", value = order.staffName)
                }
                if (!order.customerName.isNullOrBlank()) {
                    InfoRow(label = "Cliente", value = order.customerName)
                }
                val formattedDate = formatDetailDate(order.createdAt)
                if (formattedDate.isNotEmpty()) {
                    InfoRow(label = "Fecha", value = formattedDate)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
        )
    }
}

// MARK: - Section Header

@Composable
private fun DetailSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = AvoqadoTheme.spacing.xs),
    )
}

// MARK: - Order Row

@Composable
private fun OrderRow(
    order: OrderSummary,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon box
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val (icon, iconTint) = when (order.orderStatus) {
                OrderStatus.COMPLETED, OrderStatus.CONFIRMED -> Icons.Filled.CheckCircle to Success
                OrderStatus.PENDING -> Icons.Filled.Schedule to Warning
                OrderStatus.CANCELLED -> Icons.Filled.Cancel to Error
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Order number + status
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${order.orderNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Plurales.articulos(order.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrderStatusBadge(status = order.orderStatus)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = order.formattedTotal,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                Text(
                    text = order.timeDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 52.dp),
    )
}

// MARK: - Status Badges

@Composable
private fun OrderStatusBadge(status: OrderStatus) {
    val (bgColor, textColor) = when (status) {
        OrderStatus.PENDING -> OrderPendingBg to Warning
        OrderStatus.COMPLETED -> OrderCompletedBg to Success
        OrderStatus.CONFIRMED -> OrderConfirmedBg to Info
        OrderStatus.CANCELLED -> OrderCancelledBg to Error
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PaymentStatusBadge(status: PaymentStatus) {
    val (bgColor, textColor) = when (status) {
        PaymentStatus.PENDING -> PaymentPendingBg to Warning
        PaymentStatus.PARTIAL -> PaymentPartialBg to Info
        PaymentStatus.PAID -> PaymentPaidBg to Success
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OrderTypeBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

// MARK: - Status Filter Row

@Composable
private fun StatusFilterRow(
    currentFilter: String?,
    onFilterSelected: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xs),
    ) {
        items(statusFilters) { option ->
            val isSelected = currentFilter == option.value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onFilterSelected(option.value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// MARK: - Search Bar

@Composable
private fun OrderSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "Buscar por número de orden...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
        )

        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Limpiar",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Empty State

@Composable
private fun OrdersEmptyState() {
    Box(
        // Desplazable aunque esté vacío: el gesto de refresco DEBE funcionar
        // justo cuando "no veo mis órdenes".
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "No hay pedidos",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "No se encontraron pedidos para los filtros seleccionados",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: - Helpers

private fun paymentMethodIcon(method: String) = when (method.uppercase()) {
    "CASH" -> Icons.Filled.Payments
    "CARD", "CREDIT_CARD", "DEBIT_CARD" -> Icons.Filled.CreditCard
    "TRANSFER" -> Icons.Filled.SwapHoriz
    else -> Icons.Filled.AttachMoney
}

private fun formatDetailDate(isoString: String): String {
    val instant = com.avoqado.pos.core.util.VenueDateTimeFormatter.parseIso(isoString) ?: return ""
    return instant.atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
        .format(detailDateOutputFormatter)
}
