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
import com.avoqado.pos.core.util.FechaDelCalendario
import com.avoqado.pos.designsystem.components.CircleBackButton
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avoqado.pos.designsystem.components.AvoqadoRefreshable
import com.avoqado.pos.designsystem.components.AvisoDeLecturaFallida
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.TierBadge
import com.avoqado.pos.designsystem.components.AvoqadoLoadingState
import com.avoqado.pos.core.util.formatMoney
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
import java.util.Calendar
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
    // Overlay del Más (spec de refresco §4.8): LaunchedEffect cubre el "al
    // mostrarse" y LifecycleResumeEffect el regreso desde background. La
    // duplicación la absorbe el single-flight del gate.
    LaunchedEffect(Unit) { viewModel.autoRefresh() }
    LifecycleResumeEffect(Unit) {
        viewModel.autoRefresh()
        onPauseOrDispose { }
    }
    // D22: mientras Informes se ve, se vuelve a pedir solo cuando hace falta (al volver la red o al subir un cobro).
    val duenoDelCicloDeVida = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, duenoDelCicloDeVida) { viewModel.recargarSolaMientrasSeVe(duenoDelCicloDeVida) }

    val informe by viewModel.informe.collectAsState()
    val consultaElegida by viewModel.consultaElegida.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val ultimoFallo by viewModel.ultimoFallo.collectAsState()
    val isManualRefreshing by viewModel.isManualRefreshing.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val showCustomDatePicker by viewModel.showCustomDatePicker.collectAsState()
    val showDetailedSummary by viewModel.showDetailedSummary.collectAsState()
    val customStartDate by viewModel.customStartDate.collectAsState()
    val customEndDate by viewModel.customEndDate.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        ReportsTopBar(onDismiss = onDismiss)

        // Gesto de refresco (spec §4.7): isRefreshing = SOLO el gesto manual.
        AvoqadoRefreshable(
            isRefreshing = isManualRefreshing,
            onRefresh = viewModel::manualRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            CuerpoDeInformes(
                estado = estadoDelInforme(consultaElegida, informe, isLoading, ultimoFallo, eligiendoFechas = showCustomDatePicker),
                selectedPeriod = selectedPeriod,
                onPeriodSelected = viewModel::selectPeriod,
                historyLocked = !viewModel.hasAdvancedReports,
                lockedTierLabel = viewModel.reportsTierLabel,
                showCustomDatePicker = showCustomDatePicker,
                customStartDate = customStartDate,
                customEndDate = customEndDate,
                onStartDateChanged = viewModel::setCustomStartDate,
                onEndDateChanged = viewModel::setCustomEndDate,
                onApplyCustomDates = viewModel::applyCustomDates,
                onCancelCustomDates = viewModel::cancelCustomDates,
                showDetailedSummary = showDetailedSummary,
                onToggleDetailedSummary = viewModel::toggleDetailedSummary,
                onReintentar = viewModel::manualRefresh,
            )
        }
    }
}

/**
 * Lo que va debajo de la barra de Informes: los periodos y, debajo, lo que diga [estado] (D18). Los periodos se quedan
 * a la vista aunque no haya informe: sin red se puede volver a uno que ya estaba cargado.
 */
@Composable
internal fun CuerpoDeInformes(
    estado: EstadoDelInforme,
    selectedPeriod: ReportPeriod,
    onPeriodSelected: (ReportPeriod) -> Unit,
    historyLocked: Boolean,
    lockedTierLabel: String,
    showCustomDatePicker: Boolean,
    customStartDate: Long,
    customEndDate: Long,
    onStartDateChanged: (Long) -> Unit,
    onEndDateChanged: (Long) -> Unit,
    onApplyCustomDates: () -> Unit,
    onCancelCustomDates: () -> Unit,
    showDetailedSummary: Boolean,
    onToggleDetailedSummary: () -> Unit,
    onReintentar: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Period Selector (history locked to TODAY on the Free plan)
        item {
            PeriodSelectorSection(
                selectedPeriod = selectedPeriod,
                onPeriodSelected = onPeriodSelected,
                historyLocked = historyLocked,
                lockedTierLabel = lockedTierLabel,
            )
        }

        // Custom Date Picker (conditional)
        if (showCustomDatePicker) {
            item {
                CustomDatePickerSection(
                    startDateMillis = customStartDate,
                    endDateMillis = customEndDate,
                    onStartDateChanged = onStartDateChanged,
                    onEndDateChanged = onEndDateChanged,
                    onApply = onApplyCustomDates,
                    onCancel = onCancelCustomDates,
                )
            }
        }

        when (estado) {
            // El selector de arriba es todo: el informe de otro periodo no se enseña bajo fechas nuevas (D18, Codex r1 #5).
            EstadoDelInforme.EligiendoFechas -> Unit

            EstadoDelInforme.Cargando -> item {
                AvoqadoLoadingState(
                    message = "Cargando informes...",
                    modifier = Modifier.fillParentMaxHeight(0.6f),
                )
            }

            is EstadoDelInforme.NoSeCargo -> item {
                AvisoDeLecturaFallida(
                    fallo = estado.fallo,
                    textos = textosDeInformeSinCargar(estado.fallo),
                    onReintentar = onReintentar,
                    modifier = Modifier.fillParentMaxWidth(),
                )
            }

            is EstadoDelInforme.Listo -> {
                val data = estado.datos

                // Sales Summary
                item {
                    SalesSummarySection(
                        summary = data.summary,
                        showDetailed = showDetailedSummary,
                        onToggleDetailed = onToggleDetailedSummary,
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

                if ((data.summary.giftCardLoadsCount ?: 0) > 0) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Tarjetas de regalo vendidas",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${data.summary.giftCardLoads?.let { formatMoney(it) } ?: "—"} · ${data.summary.giftCardLoadsCount}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
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

                // Hourly Sales Chart (only for today). Trae las 24 horas aunque no haya ventas: sólo se enseña con
                // alguna venta, como iOS.
                if (selectedPeriod == ReportPeriod.TODAY && data.hourlySales.any { it.grossSales > 0 }) {
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
    historyLocked: Boolean = false,
    lockedTierLabel: String = "Pro",
) {
    val spacing = AvoqadoTheme.spacing
    val cornerRadius = AvoqadoTheme.cornerRadius

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
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
                // Visible teaser: historical pills stay discoverable but
                // locked on the Free plan (clamp is enforced in the VM too).
                val isLocked = historyLocked && period != ReportPeriod.TODAY
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable(enabled = !isLocked) { onPeriodSelected(period) }
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    if (isLocked) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Disponible en $lockedTierLabel",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = period.shortLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            isLocked -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            // Calendar icon button for custom range (Pro: locked on Free)
            val isCustomSelected = selectedPeriod == ReportPeriod.CUSTOM
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isCustomSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable(enabled = !historyLocked) { onPeriodSelected(ReportPeriod.CUSTOM) }
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (historyLocked) Icons.Filled.Lock else Icons.Filled.CalendarMonth,
                    contentDescription = "Personalizado",
                    modifier = Modifier.size(18.dp),
                    tint = when {
                        isCustomSelected -> MaterialTheme.colorScheme.onPrimary
                        historyLocked -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        // Upsell note (instructional only — no purchase links)
        if (historyLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TierBadge(tierLabel = lockedTierLabel)
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    text = "El histórico está disponible en $lockedTierLabel. " +
                        "Actívalo desde tu dashboard web (Configuración → Plan).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
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
    onCancel: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    val cornerRadius = AvoqadoTheme.cornerRadius
    val context = LocalContext.current
    val dateFormatter = remember {
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
    }
    fun formatDate(epochMillis: Long): String =
        com.avoqado.pos.core.util.VenueTimeZone.at(java.time.Instant.ofEpochMilli(epochMillis)).format(dateFormatter)

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
                            val tz = java.util.TimeZone.getTimeZone(
                                com.avoqado.pos.core.util.VenueTimeZone.current,
                            )
                            val cal = Calendar.getInstance(tz)
                            cal.timeInMillis = startDateMillis
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    onStartDateChanged(FechaDelCalendario.inicioDelDia(year, month, day, tz))
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
                            text = formatDate(startDateMillis),
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
                            val tz = java.util.TimeZone.getTimeZone(
                                com.avoqado.pos.core.util.VenueTimeZone.current,
                            )
                            val cal = Calendar.getInstance(tz)
                            cal.timeInMillis = endDateMillis
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    onEndDateChanged(FechaDelCalendario.finDelDia(year, month, day, tz))
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
                            text = formatDate(endDateMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // D18: cerrar sin aplicar vuelve al periodo de antes con su informe.
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(text = "Cancelar")
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Aplicar")
                }
            }
        }
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

    // HOY va por hora del día; los demás periodos, por la serie del periodo. Cada punto trae ya la etiqueta de su eje.
    val chartValues: List<Pair<String, Double>> = puntosDeVentasBrutas(selectedPeriod, periodSales, hourlySales)

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
    // Hasta 7 fechas y los 2 textos del eje de dinero: que quepan en la caché del medidor y no se midan en cada cuadro.
    val medidor = rememberTextMeasurer(cacheSize = 16)
    val estiloDelEje = MaterialTheme.typography.labelSmall.copy(color = onSurfaceVariantColor)

    val maxValue = data.maxOfOrNull { it.second } ?: 1.0
    val minValue = 0.0
    val conFecha = remember(data.size) { indicesConEtiqueta(data.size).toSet() }

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        // D6 (2-nov): la escala y las fechas se calculaban pero nunca se dibujaban, así que la gráfica salía sin escala. Como
        // iOS (30-oct), el eje de dinero va a la DERECHA y a la altura de la gráfica: el máximo arriba y el cero abajo; las
        // fechas van debajo de su punto, sin meterse bajo el eje de dinero.
        val maximo = medidor.measure(etiquetaCortaDeDinero(maxValue), estiloDelEje)
        val cero = medidor.measure(etiquetaCortaDeDinero(minValue), estiloDelEje)
        val fechas = conFecha.filter { it < data.size }.associateWith { medidor.measure(data[it].first, estiloDelEje) }
        val separacion = 6.dp.toPx()
        val altoDeFecha = fechas.values.maxOfOrNull { it.size.height } ?: 0

        val paddingLeft = 8.dp.toPx()
        val paddingRight = maxOf(maximo.size.width, cero.size.width) + separacion
        val paddingTop = maxOf(maximo.size.height, cero.size.height) / 2f
        val paddingBottom = altoDeFecha + separacion

        val chartWidth = size.width - paddingLeft - paddingRight
        val chartHeight = size.height - paddingTop - paddingBottom
        if (chartWidth <= 0f || chartHeight <= 0f) return@Canvas

        // Grid lines (5 lines)
        val gridLineCount = 4
        for (i in 0..gridLineCount) {
            val ratio = i.toFloat() / gridLineCount
            val y = paddingTop + chartHeight * (1f - ratio)

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

        // Eje de dinero, pegado a la derecha y centrado en su línea: el máximo arriba, el cero abajo.
        drawText(maximo, topLeft = Offset(size.width - maximo.size.width, paddingTop - maximo.size.height / 2f))
        drawText(cero, topLeft = Offset(size.width - cero.size.width, paddingTop + chartHeight - cero.size.height / 2f))

        // Fechas: una de cada `n / 6` y siempre la última, centradas en su punto sin salirse de la gráfica.
        val bordeDerecho = paddingLeft + chartWidth
        fechas.forEach { (index, fecha) ->
            val x = paddingLeft + index.toFloat() / (data.size - 1).coerceAtLeast(1) * chartWidth
            val izquierda = (x - fecha.size.width / 2f).coerceIn(0f, (bordeDerecho - fecha.size.width).coerceAtLeast(0f))
            drawText(fecha, topLeft = Offset(izquierda, paddingTop + chartHeight + separacion))
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
        "GIFT_CARD" -> Icons.Filled.CardGiftcard
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
                text = textoDelPorcentaje(method.percentage),
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
    // 8 horas rotuladas (12a, 3a … 9p): que quepan en la caché del medidor.
    val medidor = rememberTextMeasurer(cacheSize = 16)
    val estiloDelEje = MaterialTheme.typography.labelSmall.copy(color = onSurfaceVariantColor)
    val conHora = remember(data.size) { indicesDeHoraConEtiqueta(data.size).toSet() }

    val maxValue = data.maxOfOrNull { it.grossSales } ?: 1.0

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        // D6 (2-nov): las horas se reservaban abajo pero nunca se dibujaban. Como iOS, una de cada tres barras lleva su hora.
        val horas = conHora.filter { it < data.size }
            .associateWith { medidor.measure(etiquetaCortaDeHora(data[it].hour), estiloDelEje) }
        val separacion = 4.dp.toPx()
        val altoDeHora = horas.values.maxOfOrNull { it.size.height } ?: 0

        val paddingLeft = 8.dp.toPx()
        val paddingRight = 8.dp.toPx()
        val paddingTop = 8.dp.toPx()
        val paddingBottom = maxOf(28.dp.toPx(), altoDeHora + separacion)

        val chartWidth = size.width - paddingLeft - paddingRight
        val chartHeight = size.height - paddingTop - paddingBottom
        if (chartWidth <= 0f || chartHeight <= 0f) return@Canvas

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

        // La hora va centrada debajo de su barra, sin salirse de la gráfica.
        horas.forEach { (index, hora) ->
            val centro = paddingLeft + index * (barWidth + 2.dp.toPx()) + barWidth / 2f
            val izquierda = (centro - hora.size.width / 2f).coerceIn(0f, (size.width - hora.size.width).coerceAtLeast(0f))
            drawText(hora, topLeft = Offset(izquierda, paddingTop + chartHeight + separacion))
        }
    }
}

/**
 * «(0%)» junto a «$35.00» decía que no hubo nada (iPad, 31-oct): con dinero, lo que no llega a 1 % es «<1%», y lo que no
 * llega a 100 % es «>99%». Lo demás se redondea. Espejo de `PaymentMethodsSection.textoDelPorcentaje` (iOS).
 */
internal fun textoDelPorcentaje(porcentaje: Double): String = when {
    porcentaje > 0 && porcentaje < 1 -> "<1%"
    porcentaje > 99 && porcentaje < 100 -> ">99%"
    else -> "${Math.round(porcentaje)}%"
}
