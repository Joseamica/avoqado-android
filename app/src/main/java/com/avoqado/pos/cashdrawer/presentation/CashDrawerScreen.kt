package com.avoqado.pos.cashdrawer.presentation

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.ConteoSospechoso
import com.avoqado.pos.cashdrawer.data.EstadoDeLaApertura
import com.avoqado.pos.cashdrawer.data.textoDeAdopcion
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
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
    // Egreso que dejaría la caja en negativo, a la espera de confirmación.
    var pendingPayOut by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    val expectedAmountCents by viewModel.expectedAmountCents.collectAsState()
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
        DailyReportView(
            session = reportSession!!,
            events = reportEvents,
            // Sin esto la PANTALLA del corte decía "Avoqado" mientras el ticket
            // impreso llevaba el nombre real del negocio: el mismo corte, dos
            // encabezados distintos según dónde lo mires.
            venueName = viewModel.venueName,
            tenderBreakdown = tenderBreakdown,
            isPrinting = isPrintingCorte,
            onRetryBreakdown = {
                viewModel.loadTenderBreakdown(
                    reportSession!!.openedAt,
                    reportSession!!.closedAt ?: System.currentTimeMillis(),
                )
            },
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
                    text = "Turno de caja",
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
                // 🔴 Sacar más efectivo del que hay deja la caja en NEGATIVO.
                //
                // Medido en la tablet: con $600 en caja se registró un egreso de
                // $10,000 y el efectivo esperado quedó en -$9,400, sin un solo
                // aviso. Un cero de más al teclear descuadra el corte del día y
                // nadie se entera hasta contar el dinero.
                //
                // Se AVISA en vez de bloquear: el esperado puede ir corto si hay
                // ventas en efectivo sin sincronizar, y un guard duro dejaría al
                // cajero sin poder registrar un egreso legítimo.
                if (viewModel.puedeVerEsperado && amountCents > expectedAmountCents) { // sin permiso no hay aviso: sería un oráculo del esperado (Codex 3ª)
                    pendingPayOut = amountCents to note
                } else {
                    viewModel.addPayOut(amountCents, note)
                }
                showPayOutSheet = false
            },
            onDismiss = { showPayOutSheet = false },
        )
    }

    pendingPayOut?.let { (amountCents, note) ->
        AvoqadoDialog(
            title = "Más de lo que hay en caja",
            description = (if (viewModel.puedeVerEsperado)
                "En caja hay ${formatCurrency(expectedAmountCents)} y vas a sacar " +
                    "${formatCurrency(amountCents)}. La caja quedaría en " +
                    "${formatCurrency(expectedAmountCents - amountCents)}.\n\n"
            else
                "Vas a sacar ${formatCurrency(amountCents)}, más de lo que debería haber en caja.\n\n") +
                "Si es un error de captura, corrígelo. Si el efectivo real no coincide " +
                "con lo que muestra la caja, revisa los movimientos antes de continuar.",
            onDismiss = { pendingPayOut = null },
            actionButton = {
                PrimaryButton(
                    text = "Sacar de todos modos",
                    onClick = {
                        viewModel.addPayOut(amountCents, note)
                        pendingPayOut = null
                    },
                )
            },
            content = {},
        )
    }

    // Resultado de imprimir el corte. Va aquí, al nivel de la pantalla, porque el
    // corte parcial se lanza desde la caja en curso — dentro del reporte no se
    // vería. Y va en diálogo, nunca en Toast: en la Sunmi el Toast queda detrás de
    // la pantalla del cliente y el cajero no sabría si imprimió.
    PrintCorteResultDialog(viewModel)

    if (drawerError != null) {
        AvoqadoDialog(
            title = "Turno de caja",
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
            retiros = ConteoSospechoso.retirosDelDia(eventsForReport),
            efectivoCobradoCents = ConteoSospechoso.efectivoCobradoCents(eventsForReport),
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
    // Egreso que dejaría la caja en negativo, a la espera de confirmación.
    var pendingPayOut by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    val expectedAmountCents by viewModel.expectedAmountCents.collectAsState()
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
        DailyReportView(
            session = reportSession!!,
            events = reportEvents,
            // Sin esto la PANTALLA del corte decía "Avoqado" mientras el ticket
            // impreso llevaba el nombre real del negocio: el mismo corte, dos
            // encabezados distintos según dónde lo mires.
            venueName = viewModel.venueName,
            tenderBreakdown = tenderBreakdown,
            isPrinting = isPrintingCorte,
            onRetryBreakdown = {
                viewModel.loadTenderBreakdown(
                    reportSession!!.openedAt,
                    reportSession!!.closedAt ?: System.currentTimeMillis(),
                )
            },
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
                    text = "Turno de caja",
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
                // Mismo aviso que en el layout de tablet: sacar más de lo que hay
                // deja la caja en negativo, y casi siempre es un cero de más.
                if (viewModel.puedeVerEsperado && amountCents > expectedAmountCents) { // sin permiso no hay aviso: sería un oráculo del esperado (Codex 3ª)
                    pendingPayOut = amountCents to note
                } else {
                    viewModel.addPayOut(amountCents, note)
                }
                showPayOutSheet = false
            },
            onDismiss = { showPayOutSheet = false },
        )
    }

    pendingPayOut?.let { (amountCents, note) ->
        AvoqadoDialog(
            title = "Más de lo que hay en caja",
            description = (if (viewModel.puedeVerEsperado)
                "En caja hay ${formatCurrency(expectedAmountCents)} y vas a sacar " +
                    "${formatCurrency(amountCents)}. La caja quedaría en " +
                    "${formatCurrency(expectedAmountCents - amountCents)}.\n\n"
            else
                "Vas a sacar ${formatCurrency(amountCents)}, más de lo que debería haber en caja.\n\n") +
                "Si es un error de captura, corrígelo. Si el efectivo real no coincide " +
                "con lo que muestra la caja, revisa los movimientos antes de continuar.",
            onDismiss = { pendingPayOut = null },
            actionButton = {
                PrimaryButton(
                    text = "Sacar de todos modos",
                    onClick = {
                        viewModel.addPayOut(amountCents, note)
                        pendingPayOut = null
                    },
                )
            },
            content = {},
        )
    }

    // Resultado de imprimir el corte. Va aquí, al nivel de la pantalla, porque el
    // corte parcial se lanza desde la caja en curso — dentro del reporte no se
    // vería. Y va en diálogo, nunca en Toast: en la Sunmi el Toast queda detrás de
    // la pantalla del cliente y el cajero no sabría si imprimió.
    PrintCorteResultDialog(viewModel)

    if (drawerError != null) {
        AvoqadoDialog(
            title = "Turno de caja",
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
            retiros = ConteoSospechoso.retirosDelDia(eventsForReport),
            efectivoCobradoCents = ConteoSospechoso.efectivoCobradoCents(eventsForReport),
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
    val isPrintingCorte by viewModel.isPrintingCorte.collectAsState()
    val rechazadas by viewModel.rechazadas.collectAsState()
    val cajasAdoptadas by viewModel.cajasAdoptadas.collectAsState()
    val estadoDeLaApertura by viewModel.estadoDeLaApertura.collectAsState()
    // Corte PARCIAL en pantalla. Antes el botón sólo mandaba a imprimir: sin
    // impresora configurada —o con ella caída— no había forma de ver cómo iba la
    // caja a media jornada, que es justo para lo que sirve.
    var showPartialReport by remember { mutableStateOf(false) }

    val partialSession = session
    if (showPartialReport && partialSession != null) {
        val partialTenders by viewModel.tenderBreakdown.collectAsState()
        LaunchedEffect(partialSession.id) {
            viewModel.loadTenderBreakdown(partialSession.openedAt, System.currentTimeMillis())
        }
        DailyReportView(
            session = partialSession,
            events = events,
            venueName = viewModel.venueName,
            tenderBreakdown = partialTenders,
            isPartial = true,
            showExpected = viewModel.puedeVerEsperado,
            isPrinting = isPrintingCorte,
            onRetryBreakdown = {
                viewModel.loadTenderBreakdown(partialSession.openedAt, System.currentTimeMillis())
            },
            onPrint = { viewModel.printPartialCorte(partialSession, events) },
            onDismiss = { showPartialReport = false },
        )
        return
    }

    // 🔴 EN COLUMNA, no sueltos. El padre de esta pantalla es un `Box`, así que dos composables
    // hermanos se DIBUJAN ENCIMA uno del otro: el aviso salía tapando el encabezado "Caja abierta"
    // y su propio texto quedaba ilegible. Sólo se ve corriéndolo en el aparato — el compilador y
    // las pruebas dan por bueno un layout que se pisa a sí mismo.
    Column(modifier = Modifier.fillMaxSize()) {
    // 🔴 Antes que nada: si hay dinero que el servidor RECHAZÓ, se dice. Va arriba de todo y
    // no se puede ignorar sin tocarlo — es la diferencia entre un faltante explicado y uno que
    // nadie sabe de dónde salió.
    if (rechazadas.isNotEmpty()) {
        AvisoDeMovimientosRechazados(
            rechazadas = rechazadas,
            onDescartar = { viewModel.descartarRechazada(it) },
            onReintentar = { viewModel.reintentarApertura(it) },
        )
    }

    // 🔴 Y si esta caja se ADOPTÓ del servidor en vez de abrirse, también se dice: el fondo que el
    // cajero contó no quedó registrado en ninguna parte (hallazgo I1).
    if (cajasAdoptadas.isNotEmpty()) {
        AvisoDeCajaAdoptada(
            cajas = cajasAdoptadas,
            onDescartar = { viewModel.descartarAvisoDeAdopcion(it) },
        )
    }

    // 🔴 Y si la caja SÓLO existe en este aparato, también se dice (P2 #4). Ámbar, no rojo:
    // offline es un estado NORMAL de operación —el cajero puede seguir cobrando— y lo único que
    // hay que decirle es que todavía no llegó. Cuando el servidor la RECHAZA, esto se calla: de
    // eso ya habla el aviso rojo de arriba, que además ofrece «Reintentar».
    if (estadoDeLaApertura == EstadoDeLaApertura.PENDIENTE) {
        AvisoDeCajaSinConexion()
    }

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
            isPrintingCorte = isPrintingCorte,
            onPrintPartial = { showPartialReport = true },
            showExpected = viewModel.puedeVerEsperado,
        )
    }
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
            text = "No hay turno de caja abierto",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = "Abre un turno de caja para comenzar a registrar movimientos de efectivo.",
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
    isPrintingCorte: Boolean = false,
    onPrintPartial: () -> Unit = {},
    showExpected: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        // Expected amount header — sólo para quien tiene el permiso de back-office de turnos.
        // Conteo CIEGO (P1 Codex 27-ago): un cajero que ve el esperado todo el día no cuenta a ciegas.
        // Apagado se VE y se EXPLICA, nunca desaparece en silencio.
        if (showExpected) {
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
        } else {
            Text(
                text = "Turno de caja abierto",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Conteo ciego: el efectivo esperado se revela al cerrar la caja.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
            text = lineaDeApertura(openedDisplay, session.openedByName, session.deviceName),
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
            // Conteo ciego: con las ventas a la vista se reconstruye el esperado (fondo + ventas + ingresos − egresos).
            if (showExpected) {
                SummaryCard(
                    label = "Ventas",
                    amountCents = cashSalesCents,
                    modifier = Modifier.weight(1f),
                )
            }
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

            // Corte PARCIAL: revisar el turno a media jornada sin cerrar la caja.
            // Antes la única forma de ver un corte era CERRANDO, o sea que para
            // saber cómo iba el turno había que terminarlo.
            Button(
                onClick = onPrintPartial,
                enabled = !isPrintingCorte,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .weight(1.4f)
                    .height(44.dp),
            ) {
                Text(
                    text = if (isPrintingCorte) "Imprimiendo…" else "Corte parcial",
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

            if (displayEvents.isEmpty()) {
                // Sin esto quedaba el título "Movimientos" flotando sobre un hueco
                // enorme, que se lee como si la pantalla estuviera a medio cargar.
                Text(
                    text = "Todavía no hay movimientos. Los cobros en efectivo, " +
                        "ingresos y egresos aparecerán aquí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.md),
                )
            }

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
                text = "Aquí aparecerán los turnos de caja cerrados.",
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

/**
 * "Quién lo abrió y desde qué aparato" (Task 6, plan turno-de-caja fase 2-3). `openedByName` y
 * `deviceName` ya viven en [CashDrawerSessionEntity] — la sesión local los llena de fábrica al
 * abrir la caja, ANTES de tocar la red, así que el dato es correcto incluso en la fila provisional
 * que nace sin conexión. `deviceName` puede llegar `null` o en blanco desde un server viejo: se
 * degrada mostrando sólo el nombre, nunca "· null" ni un punto medio suelto.
 */
/**
 * 🔴 La línea completa, para que «por» no quede colgando (P3 #11 de la auditoría de apps).
 *
 * `formatOpenedBy` ya degradaba bien, pero el renglón que la envolvía imprimía «Abierta {fecha}
 * por {resultado}» pasara lo que pasara: con el nombre vacío quedaba «Abierta … por SM-X133» (el
 * aparato haciéndose pasar por persona) y con los dos vacíos, «Abierta … por » a secas. Armar la
 * línea entera aquí es lo que permite omitir el «por» cuando no hay a quién nombrar.
 */
internal fun lineaDeApertura(fecha: String, openedByName: String, deviceName: String?): String {
    val quien = formatOpenedBy(openedByName, deviceName)
    return when {
        quien.isEmpty() -> "Abierta $fecha"
        openedByName.isBlank() -> "Abierta $fecha · $quien"
        else -> "Abierta $fecha por $quien"
    }
}

internal fun formatOpenedBy(openedByName: String, deviceName: String?): String =
    listOfNotNull(
        openedByName.trim().takeIf { it.isNotEmpty() },
        deviceName?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

@Composable
private fun PrintCorteResultDialog(viewModel: CashDrawerViewModel) {
    val printResult by viewModel.printCorteResult.collectAsState()
    printResult?.let { r ->
        val (titulo, texto) = when (r) {
            is CashDrawerViewModel.PrintCorteResult.Success ->
                if (r.wasPartial) {
                    "Corte parcial impreso" to
                        "Salió en la impresora de recibos. La caja sigue abierta: " +
                        "el corte definitivo se genera al cerrarla."
                } else {
                    "Corte impreso" to "El corte salió en la impresora de recibos."
                }
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
}


/**
 * 🔴 El aviso que evita un faltante inexplicable.
 *
 * Un ingreso o retiro que el servidor rechazó de plano (monto inválido, caja de otro negocio,
 * permiso revocado) YA ocurrió en el cajón físico: el dinero se movió. Descartarlo en silencio
 * —que es lo que se hacía— dejaba al cajero cerrando con una diferencia que no podía explicar,
 * y al dueño con un arqueo que no cuadra sin causa visible.
 *
 * Dice CUÁNTO, de QUÉ tipo, y POR QUÉ lo rechazó el servidor. El botón no "arregla" nada: sólo
 * reconoce que ya se vio, porque corregirlo es un movimiento nuevo que alguien tiene que hacer
 * a conciencia.
 *
 * 🔴 **Una APERTURA es el caso contrario y por eso ofrece «Reintentar», no «Ya lo vi»** (hallazgo
 * I4): ahí no se movió dinero en el cajón —«anótalo antes de cerrar» sería falso— y descartarla
 * dejaba la caja en un limbo sin salida: sus movimientos darían 404 para siempre y su cierre queda
 * bloqueado, sin ninguna forma de volver a intentar la apertura desde la pantalla.
 */
@Composable
private fun AvisoDeMovimientosRechazados(
    rechazadas: List<CashDrawerRepository.OperacionRechazada>,
    onDescartar: (CashDrawerRepository.OperacionRechazada) -> Unit,
    onReintentar: (CashDrawerRepository.OperacionRechazada) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AvoqadoTheme.spacing.lg)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        val (titulo, explicacion) = encabezadoDeRechazos(rechazadas.map { it.kind })
        Text(
            text = titulo,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = explicacion,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        rechazadas.forEach { op ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${etiquetaDeOperacion(op.kind)} · ${formatCurrency(op.amountCents)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = op.motivo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (op.kind == "OPEN") {
                    TextButton(onClick = { onReintentar(op) }) {
                        Text(text = "Reintentar", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                } else {
                    TextButton(onClick = { onDescartar(op) }) {
                        Text(text = "Ya lo vi", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

/**
 * 🔴 EL ENCABEZADO HABLA DEL TIPO DEL PRIMER RECHAZO PENDIENTE, Y CUENTA SÓLO LOS SUYOS (N5).
 *
 * Con una APERTURA y un retiro rechazados a la vez, `soloAperturas` era `false` y el aviso volvía
 * a decir «El dinero ya se movió en el cajón» — falso para el renglón de la apertura, que es justo
 * el copy que I4 vino a corregir. Y contar los dos habría producido «2 cajas no se registraron»,
 * una mentira nueva en la otra dirección: se cuentan sólo los del tipo del que manda.
 *
 * Con una sola clase de rechazo el resultado es idéntico al de antes (regresión fijada en prueba).
 */
internal fun encabezadoDeRechazos(kinds: List<String>): Pair<String, String> {
    val primeroEsApertura = kinds.firstOrNull() == "OPEN"
    val cuantos = kinds.count { (it == "OPEN") == primeroEsApertura }
    return tituloDeRechazos(cuantos, primeroEsApertura) to explicacionDeRechazos(primeroEsApertura)
}

/**
 * 🔴 El encabezado del aviso no puede hablar de «movimientos» cuando lo único rechazado es una
 * APERTURA: ahí no se movió dinero en el cajón. Puro texto, para poder probarlo sin pantalla.
 */
internal fun tituloDeRechazos(cuantos: Int, soloAperturas: Boolean): String = when {
    soloAperturas && cuantos == 1 -> "La caja no se registró en el servidor"
    soloAperturas -> "$cuantos cajas no se registraron en el servidor"
    cuantos == 1 -> "Un movimiento no se registró"
    else -> "$cuantos movimientos no se registraron"
}

internal fun explicacionDeRechazos(soloAperturas: Boolean): String = if (soloAperturas) {
    "El servidor no aceptó la apertura de esta caja. Sus cobros van a quedar fuera del turno: " +
        "toca Reintentar, y si sigue fallando avísale a tu administrador antes de seguir cobrando."
} else {
    "El dinero ya se movió en el cajón, pero el servidor no lo aceptó. Anótalo antes de cerrar la caja."
}

/**
 * 🔴 «ESTA CAJA TODAVÍA NO LLEGÓ AL SERVIDOR» (P2 #4 de la auditoría de apps).
 *
 * Una apertura cuyo `OPEN` sigue en la cola —sin red, 5xx, reintentos— se veía IDÉNTICA a una
 * caja sana. La regla del workspace es explícita: offline es un estado NORMAL y se DICE con todas
 * sus letras, nunca en rojo de error. Mismo tono ámbar y misma forma que el aviso de la caja
 * adoptada; el texto es el de la banda que ya usa el resto de la app para lo encolado.
 */
@Composable
private fun AvisoDeCajaSinConexion() {
    Text(
        text = "Caja abierta sin conexión: se sincronizará al recuperar la red",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AvoqadoTheme.spacing.lg,
                end = AvoqadoTheme.spacing.lg,
                top = AvoqadoTheme.spacing.lg,
            )
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(AvoqadoTheme.spacing.lg),
    )
}

/**
 * 🔴 EL AVISO DE QUE ESTA CAJA ES DE OTRO (hallazgo I1).
 *
 * El servidor liga en vez de rebotar cuando ya hay un turno abierto, y **nunca pisa el fondo de lo
 * que ya estaba**: el cajero que contó $2,000 se queda operando sobre una caja de $500 y sólo se
 * entera al cerrar, como un sobrante de $1,500 sin explicación. Es ámbar, no rojo: no es un error
 * —el dinero está donde debe— pero tiene que decirse, y persiste hasta que él lo cierre.
 *
 * No se crea ningún movimiento automáticamente: meter el fondo local como un ingreso sería
 * inventar dinero que nadie autorizó.
 */
@Composable
private fun AvisoDeCajaAdoptada(
    cajas: List<CashDrawerRepository.CajaAdoptada>,
    onDescartar: (CashDrawerRepository.CajaAdoptada) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AvoqadoTheme.spacing.lg)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(AvoqadoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Text(
            text = "Esta caja ya estaba abierta",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        cajas.forEach { caja ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = textoDeAdopcion(
                        quien = caja.openedByName,
                        hora = horaDelVenue(caja.openedAtMillis),
                        fondoServidorCents = caja.fondoServidorCents,
                        fondoLocalCents = caja.fondoLocalCents,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                TextButton(onClick = { onDescartar(caja) }) {
                    Text(text = "Entendido", color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

/** La hora en el reloj del NEGOCIO, no en el del aparato (regla del workspace). */
private fun horaDelVenue(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale("es", "MX")))

private fun etiquetaDeOperacion(kind: String): String = when (kind) {
    "OPEN" -> "Apertura de caja"
    "PAY_IN" -> "Ingreso"
    "PAY_OUT" -> "Retiro"
    "CLOSE" -> "Cierre de caja"
    else -> kind
}
