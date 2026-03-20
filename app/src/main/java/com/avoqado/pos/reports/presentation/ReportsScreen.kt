package com.avoqado.pos.reports.presentation

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.ActionPurple
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Info
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.reports.data.model.CategorySales
import com.avoqado.pos.reports.data.model.HourlySales
import com.avoqado.pos.reports.data.model.PaymentMethodBreakdown
import com.avoqado.pos.reports.data.model.PeriodSales
import com.avoqado.pos.reports.data.model.ReportData
import com.avoqado.pos.reports.data.model.ReportPeriod
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import com.avoqado.pos.reports.data.model.TopProduct
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// MARK: - File-level constants

private val reportPeriods = listOf(
    ReportPeriod.TODAY,
    ReportPeriod.THIS_WEEK,
    ReportPeriod.THIS_MONTH,
    ReportPeriod.THREE_MONTHS,
    ReportPeriod.THIS_YEAR,
)

// MARK: - Main Screen

@Composable
fun ReportsScreen(
    onDismiss: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val spacing = AvoqadoTheme.spacing
    val reportData by viewModel.reportData.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val showCustomDatePicker by viewModel.showCustomDatePicker.collectAsState()
    val showDetailedSummary by viewModel.showDetailedSummary.collectAsState()
    val customStartDate by viewModel.customStartDate.collectAsState()
    val customEndDate by viewModel.customEndDate.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        ReportsTopBar(onDismiss = onDismiss)

        when {
            isLoading && reportData == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Period Selector
                    item {
                        PeriodSelectorSection(
                            selectedPeriod = selectedPeriod,
                            onPeriodSelected = viewModel::selectPeriod,
                        )
                    }

                    // Custom Date Picker (conditional)
                    if (showCustomDatePicker) {
                        item {
                            CustomDatePickerSection(
                                startDateMillis = customStartDate,
                                endDateMillis = customEndDate,
                                onStartDateChanged = viewModel::setCustomStartDate,
                                onEndDateChanged = viewModel::setCustomEndDate,
                                onApply = viewModel::applyCustomDates,
                            )
                        }
                    }

                    // Content depends on data availability
                    if (reportData == null) {
                        item {
                            EmptyStateView()
                        }
                    } else {
                        val data = reportData!!

                        // Sales Summary
                        item {
                            SalesSummarySection(
                                summary = data.summary,
                                showDetailed = showDetailedSummary,
                                onToggleDetailed = viewModel::toggleDetailedSummary,
                            )
                        }

                        // Sales Line Chart
                        item {
                            SalesChartSection(
                                periodSales = data.periodSales,
                                hourlySales = data.hourlySales,
                                selectedPeriod = selectedPeriod,
                                totalGrossSales = data.summary.grossSales,
                            )
                        }

                        // Payment Methods
                        if (data.paymentMethods.isNotEmpty()) {
                            item {
                                PaymentMethodsSection(paymentMethods = data.paymentMethods)
                            }
                        }

                        // Top Products
                        if (data.topProducts.isNotEmpty()) {
                            item {
                                TopProductsSection(topProducts = data.topProducts)
                            }
                        }

                        // Categories
                        if (data.categories.isNotEmpty()) {
                            item {
                                CategoriesSection(categories = data.categories)
                            }
                        }

                        // Hourly Sales Chart (only for today)
                        if (selectedPeriod == ReportPeriod.TODAY && data.hourlySales.isNotEmpty()) {
                            item {
                                HourlySalesChartSection(hourlySales = data.hourlySales)
                            }
                        }

                        // Bottom padding
                        item {
                            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Top Bar

@Composable
private fun ReportsTopBar(onDismiss: () -> Unit) {
    val spacing = AvoqadoTheme.spacing
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onDismiss)
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = "Informes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Period Selector

@Composable
private fun PeriodSelectorSection(
    selectedPeriod: ReportPeriod,
    onPeriodSelected: (ReportPeriod) -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    val cornerRadius = AvoqadoTheme.cornerRadius

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reportPeriods.forEach { period ->
                val isSelected = selectedPeriod == period
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onPeriodSelected(period) }
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = period.shortLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Calendar icon button for custom range
            val isCustomSelected = selectedPeriod == ReportPeriod.CUSTOM
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isCustomSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onPeriodSelected(ReportPeriod.CUSTOM) }
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = "Personalizado",
                    modifier = Modifier.size(18.dp),
                    tint = if (isCustomSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// MARK: - Custom Date Picker

@Composable
private fun CustomDatePickerSection(
    startDateMillis: Long,
    endDateMillis: Long,
    onStartDateChanged: (Long) -> Unit,
    onEndDateChanged: (Long) -> Unit,
    onApply: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    val cornerRadius = AvoqadoTheme.cornerRadius
    val context = LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.US) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = "RANGO PERSONALIZADO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // Start date button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(cornerRadius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(cornerRadius.md),
                        )
                        .clickable {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = startDateMillis
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    val newCal = Calendar.getInstance()
                                    newCal.set(year, month, day, 0, 0, 0)
                                    onStartDateChanged(newCal.timeInMillis)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH),
                            ).show()
                        }
                        .padding(spacing.md),
                ) {
                    Column {
                        Text(
                            text = "Desde",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = dateFormatter.format(Date(startDateMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // End date button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(cornerRadius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(cornerRadius.md),
                        )
                        .clickable {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = endDateMillis
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    val newCal = Calendar.getInstance()
                                    newCal.set(year, month, day, 23, 59, 59)
                                    onEndDateChanged(newCal.timeInMillis)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH),
                            ).show()
                        }
                        .padding(spacing.md),
                ) {
                    Column {
                        Text(
                            text = "Hasta",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = dateFormatter.format(Date(endDateMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Aplicar")
            }
        }
    }
}

// MARK: - Empty State

@Composable
private fun EmptyStateView() {
    val spacing = AvoqadoTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.xxxl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No hay datos para este periodo",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Section Header

@Composable
private fun SectionHeader(title: String) {
    val spacing = AvoqadoTheme.spacing
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = spacing.sm),
    )
}

// MARK: - Sales Summary Section

@Composable
private fun SalesSummarySection(
    summary: SalesSummaryReport,
    showDetailed: Boolean,
    onToggleDetailed: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        ) {
            SectionHeader(title = "RESUMEN DE VENTAS")

            Spacer(modifier = Modifier.height(spacing.sm))

            // Primary metrics grid (2 columns)
            val primaryMetrics = remember(summary) {
                listOf(
                    MetricCardData(
                        icon = Icons.Filled.AttachMoney,
                        label = "Ventas brutas",
                        value = summary.grossSalesFormatted,
                    ),
                    MetricCardData(
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        label = "Ventas netas",
                        value = summary.netSalesFormatted,
                    ),
                    MetricCardData(
                        icon = Icons.Filled.Receipt,
                        label = "Venta promedio",
                        value = summary.averageTicketFormatted,
                    ),
                    MetricCardData(
                        icon = Icons.AutoMirrored.Filled.Undo,
                        label = "Devoluciones",
                        value = summary.refundsFormatted,
                    ),
                    MetricCardData(
                        icon = Icons.Filled.Percent,
                        label = "Descuentos",
                        value = "-${summary.discountsFormatted}",
                    ),
                    MetricCardData(
                        icon = Icons.Filled.LocalOffer,
                        label = "Transacciones",
                        value = summary.transactionCount.toString(),
                    ),
                )
            }

            MetricsGrid(metrics = primaryMetrics)

            Spacer(modifier = Modifier.height(spacing.sm))

            // Toggle details button
            TextButton(
                onClick = onToggleDetailed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showDetailed) "Ocultar detalles" else "Mostrar detalles",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.width(spacing.xxs))
                    Icon(
                        imageVector = if (showDetailed) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Detailed metrics (conditional)
            if (showDetailed) {
                Spacer(modifier = Modifier.height(spacing.sm))

                val detailMetrics = remember(summary) {
                    listOf(
                        MetricCardData(
                            icon = Icons.Filled.Star,
                            label = "Propinas",
                            value = summary.tipsFormatted,
                        ),
                        MetricCardData(
                            icon = Icons.Filled.Percent,
                            label = "Impuestos",
                            value = summary.taxesFormatted,
                        ),
                        MetricCardData(
                            icon = Icons.Filled.Payments,
                            label = "Com. plataforma",
                            value = summary.platformFeesFormatted,
                        ),
                        MetricCardData(
                            icon = Icons.Filled.Payments,
                            label = "Com. personal",
                            value = summary.staffCommissionsFormatted,
                        ),
                        MetricCardData(
                            icon = Icons.Filled.AttachMoney,
                            label = "Total recibido",
                            value = summary.totalCollectedFormatted,
                        ),
                        MetricCardData(
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            label = "Ganancia neta",
                            value = summary.netProfitFormatted,
                        ),
                    )
                }
                MetricsGrid(metrics = detailMetrics)
            }
        }
    }
}

// MARK: - Metric Card

private data class MetricCardData(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

@Composable
private fun MetricsGrid(metrics: List<MetricCardData>) {
    val spacing = AvoqadoTheme.spacing
    // 3-column layout using rows of 3
    val chunked = remember(metrics) { metrics.chunked(3) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        chunked.forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                rowMetrics.forEach { metric ->
                    MetricCard(
                        data = metric,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill remaining cells if row is incomplete
                repeat(3 - rowMetrics.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    data: MetricCardData,
    modifier: Modifier = Modifier,
) {
    val spacing = AvoqadoTheme.spacing
    val cornerRadius = AvoqadoTheme.cornerRadius

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(cornerRadius.md),
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(cornerRadius.md),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Icon(
                imageVector = data.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = data.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = data.value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Sales Chart Section

@Composable
private fun SalesChartSection(
    periodSales: List<PeriodSales>,
    hourlySales: List<HourlySales>,
    selectedPeriod: ReportPeriod,
    totalGrossSales: Double,
) {
    val spacing = AvoqadoTheme.spacing
    val grossSalesFormatted = String.format(Locale.US, "$%,.2f", totalGrossSales)

    // Use hourly data for TODAY, period data otherwise
    val chartValues: List<Pair<String, Double>> = if (selectedPeriod == ReportPeriod.TODAY) {
        hourlySales.map { Pair(it.hourLabel, it.grossSales) }
    } else {
        periodSales.map { Pair(it.periodLabel, it.grossSales) }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        ) {
            SectionHeader(title = "VENTAS BRUTAS")
            Text(
                text = grossSalesFormatted,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(spacing.lg))

            if (chartValues.isEmpty() || chartValues.all { it.second == 0.0 }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Sin datos para este periodo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SalesLineChart(
                    data = chartValues,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                )
            }
        }
    }
}

// MARK: - Sales Line Chart (Canvas)

@Composable
private fun SalesLineChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val maxValue = data.maxOfOrNull { it.second } ?: 1.0
    val minValue = 0.0

    Canvas(modifier = modifier) {
        val paddingLeft = 60.dp.toPx()
        val paddingRight = 16.dp.toPx()
        val paddingTop = 16.dp.toPx()
        val paddingBottom = 40.dp.toPx()

        val chartWidth = size.width - paddingLeft - paddingRight
        val chartHeight = size.height - paddingTop - paddingBottom

        if (data.isEmpty()) return@Canvas

        // Grid lines & Y-axis labels (4 lines)
        val gridLineCount = 4
        for (i in 0..gridLineCount) {
            val ratio = i.toFloat() / gridLineCount
            val y = paddingTop + chartHeight * (1f - ratio)
            val value = minValue + (maxValue - minValue) * ratio
            val valueLabel = String.format(Locale.US, "$%,.0f", value)

            drawLine(
                color = outlineVariantColor,
                start = Offset(paddingLeft, y),
                end = Offset(paddingLeft + chartWidth, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // Compute points
        val points = data.mapIndexed { index, (_, value) ->
            val x = paddingLeft + index.toFloat() / (data.size - 1).coerceAtLeast(1) * chartWidth
            val normalizedValue = if (maxValue > 0) ((value - minValue) / (maxValue - minValue)).toFloat() else 0f
            val y = paddingTop + chartHeight * (1f - normalizedValue)
            Offset(x, y)
        }

        // Gradient fill path
        if (points.size >= 2) {
            val fillPath = Path().apply {
                moveTo(points.first().x, paddingTop + chartHeight)
                points.forEach { point ->
                    lineTo(point.x, point.y)
                }
                lineTo(points.last().x, paddingTop + chartHeight)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.3f),
                        Color.Transparent,
                    ),
                    startY = paddingTop,
                    endY = paddingTop + chartHeight,
                ),
            )

            // Line stroke
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { point ->
                    lineTo(point.x, point.y)
                }
            }
            drawPath(
                path = linePath,
                color = primaryColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        // X-axis labels (every Nth point to avoid crowding)
        val labelStep = (data.size / 5).coerceAtLeast(1)
        data.forEachIndexed { index, (label, _) ->
            if (index % labelStep == 0 || index == data.size - 1) {
                val x = paddingLeft + index.toFloat() / (data.size - 1).coerceAtLeast(1) * chartWidth
            }
        }
    }
}

// MARK: - Payment Methods Section

@Composable
private fun PaymentMethodsSection(paymentMethods: List<PaymentMethodBreakdown>) {
    val spacing = AvoqadoTheme.spacing

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            SectionHeader(title = "MÉTODOS DE PAGO")

            paymentMethods.forEach { method ->
                PaymentMethodRow(method = method)
            }
        }
    }
}

@Composable
private fun PaymentMethodRow(method: PaymentMethodBreakdown) {
    val spacing = AvoqadoTheme.spacing
    val cornerRadius = AvoqadoTheme.cornerRadius

    val methodColor = when (method.method.uppercase()) {
        "CASH" -> Success
        "CARD", "CREDIT_CARD", "DEBIT_CARD" -> Info
        "TRANSFER" -> ActionPurple
        "WALLET" -> Warning
        else -> MaterialTheme.colorScheme.primary
    }

    val methodIcon = when (method.method.uppercase()) {
        "CASH" -> Icons.Filled.Payments
        "TRANSFER" -> Icons.Filled.SwapHoriz
        else -> Icons.Filled.CreditCard
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = methodIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = methodColor,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = method.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(Locale.US, "$%,.2f", method.amount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = String.format(Locale.US, "%.0f%%", method.percentage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
            )
        }

        // Progress bar
        val filledFraction = (method.percentage / 100.0).toFloat().coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(cornerRadius.xs))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = filledFraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(cornerRadius.xs))
                    .background(methodColor),
            )
        }
    }
}

// MARK: - Top Products Section

@Composable
private fun TopProductsSection(topProducts: List<TopProduct>) {
    val spacing = AvoqadoTheme.spacing

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        ) {
            SectionHeader(title = "PRODUCTOS MÁS VENDIDOS")
            Spacer(modifier = Modifier.height(spacing.sm))

            topProducts.take(10).forEachIndexed { index, product ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                TopProductRow(
                    rank = index + 1,
                    product = product,
                )
            }
        }
    }
}

@Composable
private fun TopProductRow(
    rank: Int,
    product: TopProduct,
) {
    val spacing = AvoqadoTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.productName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!product.categoryName.isNullOrEmpty()) {
                Text(
                    text = product.categoryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${product.unitsSold} uds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = String.format(Locale.US, "$%,.2f", product.grossSales),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Categories Section

@Composable
private fun CategoriesSection(categories: List<CategorySales>) {
    val spacing = AvoqadoTheme.spacing

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        ) {
            SectionHeader(title = "VENTAS POR CATEGORÍA")
            Spacer(modifier = Modifier.height(spacing.sm))

            // Table header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xs),
            ) {
                Text(
                    text = "CATEGORÍA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "UNIDADES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = "BRUTO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(88.dp),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            categories.forEach { category ->
                CategoryRow(category = category)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CategoryRow(category: CategorySales) {
    val spacing = AvoqadoTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category.categoryName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = category.unitsSold.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = String.format(Locale.US, "$%,.2f", category.grossSales),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(88.dp),
        )
    }
}

// MARK: - Hourly Sales Bar Chart Section

@Composable
private fun HourlySalesChartSection(hourlySales: List<HourlySales>) {
    val spacing = AvoqadoTheme.spacing

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        ) {
            SectionHeader(title = "VENTAS POR HORA")
            Spacer(modifier = Modifier.height(spacing.sm))

            HourlyBarChart(
                data = hourlySales,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
        }
    }
}

// MARK: - Hourly Bar Chart (Canvas)

@Composable
private fun HourlyBarChart(
    data: List<HourlySales>,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val maxValue = data.maxOfOrNull { it.grossSales } ?: 1.0

    Canvas(modifier = modifier) {
        val paddingLeft = 8.dp.toPx()
        val paddingRight = 8.dp.toPx()
        val paddingTop = 8.dp.toPx()
        val paddingBottom = 28.dp.toPx()
        val labelStep = 3

        val chartWidth = size.width - paddingLeft - paddingRight
        val chartHeight = size.height - paddingTop - paddingBottom

        if (data.isEmpty()) return@Canvas

        val barCount = data.size
        val totalGapWidth = (barCount - 1) * 2.dp.toPx()
        val barWidth = (chartWidth - totalGapWidth) / barCount

        data.forEachIndexed { index, hourSales ->
            val x = paddingLeft + index * (barWidth + 2.dp.toPx())
            val normalizedValue = if (maxValue > 0) (hourSales.grossSales / maxValue).toFloat() else 0f
            val barHeight = chartHeight * normalizedValue
            val y = paddingTop + chartHeight - barHeight
            val barColor = if (hourSales.grossSales > 0) primaryColor else outlineVariantColor

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}
