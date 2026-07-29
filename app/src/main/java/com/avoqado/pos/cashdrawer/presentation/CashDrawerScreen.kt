package com.avoqado.pos.cashdrawer.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Success
import java.util.Locale

// MARK: - Entry Point

@Composable
fun CashDrawerScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    viewModel: CashDrawerViewModel = hiltViewModel(),
) {
    // Reload on every entry: the Hilt VM can outlive the overlay, so sales
    // recorded while the screen was closed (recordCashSale from the register)
    // were showing stale ("Ventas $0"). Fresh load fixes it.
    LaunchedEffect(Unit) { viewModel.loadCurrentSession() }

    if (isTablet) {
        TabletCashDrawerLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    } else {
        PhoneCashDrawerLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    }
}

// MARK: - Tablet Layout

@Composable
private fun TabletCashDrawerLayout(
    viewModel: CashDrawerViewModel,
    onDismiss: () -> Unit,
) {
    val selectedSection by viewModel.selectedSection.collectAsState()

    var showOpenSheet by remember { mutableStateOf(false) }
    var showPayInSheet by remember { mutableStateOf(false) }
    var showPayOutSheet by remember { mutableStateOf(false) }
    var showCloseSheet by remember { mutableStateOf(false) }
    var showDailyReport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val drawerError by viewModel.errorMessage.collectAsState()
    var reportSession by remember { mutableStateOf<CashDrawerSessionEntity?>(null) }
    var reportEvents by remember { mutableStateOf<List<CashDrawerEventEntity>>(emptyList()) }

    // Show DailyReportView if active
    if (showDailyReport && reportSession != null) {
        val tenderBreakdown by viewModel.tenderBreakdown.collectAsState()
        LaunchedEffect(reportSession!!.id) {
            viewModel.loadTenderBreakdown(
                reportSession!!.openedAt,
                reportSession!!.closedAt ?: System.currentTimeMillis(),
            )
        }
        val isPrintingCorte by viewModel.isPrintingCorte.collectAsState()
        val printResult by viewModel.printCorteResult.collectAsState()
        // El resultado va en diálogo, NO en Toast: en la Sunmi el Toast sale detrás
        // de la pantalla del cliente y el cajero se queda sin saber si imprimió.
        printResult?.let { r ->
            val (titulo, texto) = when (r) {
                is CashDrawerViewModel.PrintCorteResult.Success ->
                    "Corte impreso" to "El corte salió en la impresora de recibos."
                is CashDrawerViewModel.PrintCorteResult.Failure ->
                    "No se imprimió el corte" to r.reason
            }
            AvoqadoDialog(
                title = titulo,
                description = texto,
                onDismiss = { viewModel.clearPrintCorteResult() },
                actionButton = {
                    PrimaryButton(text = "Entendido", onClick = { viewModel.clearPrintCorteResult() })
                },
                content = {},
            )
        }
        DailyReportView(
            session = reportSession!!,
            events = reportEvents,
            tenderBreakdown = tenderBreakdown,
            isPrinting = isPrintingCorte,
            onPrint = {
                viewModel.printCorte(
                    session = reportSession!!,
                    events = reportEvents,
                    tenders = tenderBreakdown,
                    venueName = viewModel.venueName,
                )
            },
            onDismiss = {
                showDailyReport = false
                reportSession = null
                reportEvents = emptyList()
            },
        )
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar (280dp)
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Header: back button on its own line, title below
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Caja",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Section rows
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CashDrawerSection.entries.forEach { section ->
                    SectionRow(
                        section = section,
                        isSelected = section == selectedSection,
                        onClick = { viewModel.selectSection(section) },
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
            SectionContent(
                section = selectedSection,
                viewModel = viewModel,
                onOpenDrawer = { showOpenSheet = true },
                onPayIn = { showPayInSheet = true },
                onPayOut = { showPayOutSheet = true },
                onCloseDrawer = { showCloseSheet = true },
                onSessionTap = { session ->
                    viewModel.loadEventsForSession(session.id) { events ->
                        reportSession = session
                        reportEvents = events
                        showDailyReport = true
                    }
                },
            )
        }
    }

    // Sheets
    if (showOpenSheet) {
        OpenDrawerSheet(
            onConfirm = { amountCents ->
                viewModel.openSession(amountCents)
                showOpenSheet = false
            },
            onDismiss = { showOpenSheet = false },
        )
    }

    if (showPayInSheet) {
        PayInOutSheet(
            isPayIn = true,
            onConfirm = { amountCents, note ->
                viewModel.addPayIn(amountCents, note)
                showPayInSheet = false
            },
            onDismiss = { showPayInSheet = false },
        )
    }

    if (showPayOutSheet) {
        PayInOutSheet(
            isPayIn = false,
            onConfirm = { amountCents, note ->
                viewModel.addPayOut(amountCents, note)
                showPayOutSheet = false
            },
            onDismiss = { showPayOutSheet = false },
        )
    }

    if (drawerError != null) {
        AvoqadoDialog(
            title = "Caja",
            description = drawerError ?: "",
            onDismiss = { viewModel.errorMessage.value = null },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { viewModel.errorMessage.value = null })
            },
            content = {},
        )
    }

    if (showCloseSheet) {
        val expectedCents by viewModel.expectedAmountCents.collectAsState()
        val sessionForReport = viewModel.currentSession.collectAsState().value
        val eventsForReport = viewModel.events.collectAsState().value
        CloseDrawerSheet(
            expectedAmountCents = expectedCents,
            onConfirm = { actualCents, note ->
                // Capture session and events before closing
                val closedSession = sessionForReport?.copy(
                    actualAmountCents = actualCents,
                    closedAt = System.currentTimeMillis(),
                    overShortCents = actualCents - expectedCents,
                    closingNote = note,
                )
                val closedEvents = eventsForReport.toList()
                scope.launch {
                    // Report ONLY on confirmed success: before, a fabricated
                    // client-side "cierre" report was shown even when the
                    // close failed and the drawer stayed open.
                    val ok = viewModel.closeSession(actualCents, note)
                    if (ok) {
                        showCloseSheet = false
                        if (closedSession != null) {
                            reportSession = closedSession
                            reportEvents = closedEvents
                            showDailyReport = true
                        }
                    }
                }
            },
            onDismiss = { showCloseSheet = false },
        )
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneCashDrawerLayout(
    viewModel: CashDrawerViewModel,
    onDismiss: () -> Unit,
) {
    var showingSection by remember { mutableStateOf<CashDrawerSection?>(null) }
    var showOpenSheet by remember { mutableStateOf(false) }
    var showPayInSheet by remember { mutableStateOf(false) }
    var showPayOutSheet by remember { mutableStateOf(false) }
    var showCloseSheet by remember { mutableStateOf(false) }
    var showDailyReport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val drawerError by viewModel.errorMessage.collectAsState()
    var reportSession by remember { mutableStateOf<CashDrawerSessionEntity?>(null) }
    var reportEvents by remember { mutableStateOf<List<CashDrawerEventEntity>>(emptyList()) }

    // Show DailyReportView if active
    if (showDailyReport && reportSession != null) {
        val tenderBreakdown by viewModel.tenderBreakdown.collectAsState()
        LaunchedEffect(reportSession!!.id) {
            viewModel.loadTenderBreakdown(
                reportSession!!.openedAt,
                reportSession!!.closedAt ?: System.currentTimeMillis(),
            )
        }
        val isPrintingCorte by viewModel.isPrintingCorte.collectAsState()
        val printResult by viewModel.printCorteResult.collectAsState()
        // El resultado va en diálogo, NO en Toast: en la Sunmi el Toast sale detrás
        // de la pantalla del cliente y el cajero se queda sin saber si imprimió.
        printResult?.let { r ->
            val (titulo, texto) = when (r) {
                is CashDrawerViewModel.PrintCorteResult.Success ->
                    "Corte impreso" to "El corte salió en la impresora de recibos."
                is CashDrawerViewModel.PrintCorteResult.Failure ->
                    "No se imprimió el corte" to r.reason
            }
            AvoqadoDialog(
                title = titulo,
                description = texto,
                onDismiss = { viewModel.clearPrintCorteResult() },
                actionButton = {
                    PrimaryButton(text = "Entendido", onClick = { viewModel.clearPrintCorteResult() })
                },
                content = {},
            )
        }
        DailyReportView(
            session = reportSession!!,
            events = reportEvents,
            tenderBreakdown = tenderBreakdown,
            isPrinting = isPrintingCorte,
            onPrint = {
                viewModel.printCorte(
                    session = reportSession!!,
                    events = reportEvents,
                    tenders = tenderBreakdown,
                    venueName = viewModel.venueName,
                )
            },
            onDismiss = {
                showDailyReport = false
                reportSession = null
                reportEvents = emptyList()
            },
        )
        return
    }

    if (showingSection != null) {
        val section = showingSection!!
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = { showingSection = null })
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(modifier = Modifier.weight(1f)) {
                SectionContent(
                    section = section,
                    viewModel = viewModel,
                    onOpenDrawer = { showOpenSheet = true },
                    onPayIn = { showPayInSheet = true },
                    onPayOut = { showPayOutSheet = true },
                    onCloseDrawer = { showCloseSheet = true },
                    onSessionTap = { session ->
                        viewModel.loadEventsForSession(session.id) { events ->
                            reportSession = session
                            reportEvents = events
                            showDailyReport = true
                        }
                    },
                )
            }
        }
    } else {
        // Section list
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = onDismiss)
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Text(
                    text = "Caja",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                CashDrawerSection.entries.forEach { section ->
                    SectionRow(
                        section = section,
                        isSelected = false,
                        onClick = {
                            viewModel.selectSection(section)
                            showingSection = section
                        },
                    )
                }
            }
        }
    }

    // Sheets
    if (showOpenSheet) {
        OpenDrawerSheet(
            onConfirm = { amountCents ->
                viewModel.openSession(amountCents)
                showOpenSheet = false
            },
            onDismiss = { showOpenSheet = false },
        )
    }

    if (showPayInSheet) {
        PayInOutSheet(
            isPayIn = true,
            onConfirm = { amountCents, note ->
                viewModel.addPayIn(amountCents, note)
                showPayInSheet = false
            },
            onDismiss = { showPayInSheet = false },
        )
    }

    if (showPayOutSheet) {
        PayInOutSheet(
            isPayIn = false,
            onConfirm = { amountCents, note ->
                viewModel.addPayOut(amountCents, note)
                showPayOutSheet = false
            },
            onDismiss = { showPayOutSheet = false },
        )
    }

    if (drawerError != null) {
        AvoqadoDialog(
            title = "Caja",
            description = drawerError ?: "",
            onDismiss = { viewModel.errorMessage.value = null },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { viewModel.errorMessage.value = null })
            },
            content = {},
        )
    }

    if (showCloseSheet) {
        val expectedCents by viewModel.expectedAmountCents.collectAsState()
        val sessionForReport = viewModel.currentSession.collectAsState().value
        val eventsForReport = viewModel.events.collectAsState().value
        CloseDrawerSheet(
            expectedAmountCents = expectedCents,
            onConfirm = { actualCents, note ->
                val closedSession = sessionForReport?.copy(
                    actualAmountCents = actualCents,
                    closedAt = System.currentTimeMillis(),
                    overShortCents = actualCents - expectedCents,
                    closingNote = note,
                )
                val closedEvents = eventsForReport.toList()
                scope.launch {
                    val ok = viewModel.closeSession(actualCents, note)
                    if (ok) {
                        showCloseSheet = false
                        if (closedSession != null) {
                            reportSession = closedSession
                            reportEvents = closedEvents
                            showDailyReport = true
                        }
                    }
                }
            },
            onDismiss = { showCloseSheet = false },
        )
    }
}

// MARK: - Section Row

@Composable
private fun SectionRow(
    section: CashDrawerSection,
    isSelected: Boolean,
    onClick: () -> Unit,
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
    }
}

// MARK: - Section Content Dispatcher

@Composable
private fun SectionContent(
    section: CashDrawerSection,
    viewModel: CashDrawerViewModel,
    onOpenDrawer: () -> Unit,
    onPayIn: () -> Unit,
    onPayOut: () -> Unit,
    onCloseDrawer: () -> Unit,
    onSessionTap: (CashDrawerSessionEntity) -> Unit = {},
) {
    when (section) {
        CashDrawerSection.CURRENT -> CurrentDrawerContent(
            viewModel = viewModel,
            onOpenDrawer = onOpenDrawer,
            onPayIn = onPayIn,
            onPayOut = onPayOut,
            onCloseDrawer = onCloseDrawer,
        )
        CashDrawerSection.HISTORY -> HistoryContent(
            viewModel = viewModel,
            onSessionTap = onSessionTap,
        )
    }
}

// MARK: - Current Drawer Content

@Composable
private fun CurrentDrawerContent(
    viewModel: CashDrawerViewModel,
    onOpenDrawer: () -> Unit,
    onPayIn: () -> Unit,
    onPayOut: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val session by viewModel.currentSession.collectAsState()
    val events by viewModel.events.collectAsState()
    val expectedCents by viewModel.expectedAmountCents.collectAsState()

    if (session == null) {
        // Empty state
        EmptyDrawerState(onOpenDrawer = onOpenDrawer)
    } else {
        // Open drawer view
        OpenDrawerContent(
            session = session!!,
            events = events,
            expectedCents = expectedCents,
            cashSalesCents = viewModel.cashSalesTotal(),
            payInsCents = viewModel.payInsTotal(),
            payOutsCents = viewModel.payOutsTotal(),
            onPayIn = onPayIn,
            onPayOut = onPayOut,
            onCloseDrawer = onCloseDrawer,
        )
    }
}

// MARK: - Empty State

@Composable
private fun EmptyDrawerState(onOpenDrawer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = "No hay caja abierta",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = "Abre una caja para comenzar a registrar movimientos de efectivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
        Button(
            onClick = onOpenDrawer,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.height(48.dp),
        ) {
            Text(
                text = "Abrir caja",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MARK: - Open Drawer Content

@Composable
private fun OpenDrawerContent(
    session: CashDrawerSessionEntity,
    events: List<CashDrawerEventEntity>,
    expectedCents: Int,
    cashSalesCents: Int,
    payInsCents: Int,
    payOutsCents: Int,
    onPayIn: () -> Unit,
    onPayOut: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        // Expected amount header
        Text(
            text = "Efectivo esperado",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatCurrency(expectedCents),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

        // Opened info
        val openedDisplay = remember(session.openedAt) {
            com.avoqado.pos.core.util.VenueDateTimeFormatter.SPANISH_MX
                .let { locale ->
                    java.time.Instant.ofEpochMilli(session.openedAt)
                        .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm", locale))
                }
        }
        Text(
            text = "Abierta $openedDisplay por ${session.openedByName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Summary cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            SummaryCard(
                label = "Monto inicial",
                amountCents = session.startingAmountCents,
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                label = "Ventas",
                amountCents = cashSalesCents,
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                label = "Ingresos",
                amountCents = payInsCents,
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                label = "Egresos",
                amountCents = payOutsCents,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Button(
                onClick = onPayIn,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                Text(
                    text = "Ingreso",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Button(
                onClick = onPayOut,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                Text(
                    text = "Egreso",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Button(
                onClick = onCloseDrawer,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Error.copy(alpha = 0.1f),
                    contentColor = Error,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Text(
                    text = "Cerrar caja",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Events list
        if (events.isNotEmpty()) {
            Text(
                text = "Movimientos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Show events in reverse chronological order (excluding OPEN)
            val displayEvents = events
                .filter { it.type != CashDrawerEventType.OPEN.name }
                .reversed()

            displayEvents.forEach { event ->
                EventRow(event = event)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// MARK: - Summary Card

@Composable
private fun SummaryCard(
    label: String,
    amountCents: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(
            modifier = Modifier.padding(AvoqadoTheme.spacing.md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
            Text(
                text = formatCurrency(amountCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Event Row

@Composable
private fun EventRow(event: CashDrawerEventEntity) {
    val timeText = remember(event.createdAt) {
        java.time.Instant.ofEpochMilli(event.createdAt)
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale("es", "MX")))
    }

    val (label, color, sign) = when (event.type) {
        CashDrawerEventType.CASH_SALE.name -> Triple("Venta en efectivo", Success, "+")
        CashDrawerEventType.PAY_IN.name -> Triple("Ingreso", Success, "+")
        CashDrawerEventType.PAY_OUT.name -> Triple("Egreso", Error, "-")
        CashDrawerEventType.CLOSE.name -> Triple("Cierre de caja", MaterialTheme.colorScheme.onSurface, "")
        else -> Triple("Apertura", MaterialTheme.colorScheme.onSurface, "")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!event.note.isNullOrBlank()) {
                Text(
                    text = event.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "$timeText - ${event.staffName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "$sign${formatCurrency(event.amountCents)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

// MARK: - History Content

@Composable
private fun HistoryContent(
    viewModel: CashDrawerViewModel,
    onSessionTap: (CashDrawerSessionEntity) -> Unit = {},
) {
    val closedSessions by viewModel.closedSessions.collectAsState()

    if (closedSessions.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AvoqadoTheme.spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "Sin historial",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "Aquí aparecerán las cajas cerradas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(AvoqadoTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            items(closedSessions) { session ->
                ClosedSessionCard(
                    session = session,
                    onClick = { onSessionTap(session) },
                )
            }
        }
    }
}

// MARK: - Closed Session Card

@Composable
private fun ClosedSessionCard(
    session: CashDrawerSessionEntity,
    onClick: () -> Unit = {},
) {
    val closedAtMillis = session.closedAt ?: session.openedAt
    val sessionDateText = remember(closedAtMillis) {
        java.time.Instant.ofEpochMilli(closedAtMillis)
            .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale("es", "MX")))
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(
            modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
        ) {
            // Date and who closed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = sessionDateText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = session.closedByName ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Amounts row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Monto inicial",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatCurrency(session.startingAmountCents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Conteo real",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatCurrency(session.actualAmountCents ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Over/short
            val overShort = session.overShortCents ?: 0
            if (overShort != 0) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                val (overShortLabel, overShortColor) = if (overShort > 0) {
                    "Sobrante" to Success
                } else {
                    "Faltante" to Error
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "$overShortLabel: ${formatCurrency(kotlin.math.abs(overShort))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = overShortColor,
                    )
                }
            }

            // Note
            if (!session.closingNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = session.closingNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: - Helpers

fun formatCurrency(cents: Int): String {
    val pesos = cents / 100.0
    return "$${String.format(Locale.US, "%,.2f", pesos)}"
}
