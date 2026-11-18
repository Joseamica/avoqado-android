package com.avoqado.pos.sync.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import java.text.NumberFormat
import java.util.Locale
import com.avoqado.pos.designsystem.components.AvoqadoFullScreenModal
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.reservations.domain.UndoNoShowTexts
import com.avoqado.pos.reservations.presentation.undo.NoticeToast
import com.avoqado.pos.reservations.presentation.undo.UndoNoShowOverlays

/**
 * Cuarentena de sincronización — bottom sheet que lista las operaciones offline
 * que el server RECHAZÓ al reconectar, con guía de resolución y "Descartar".
 * Es la superficie que hace VISIBLES los rechazos (antes eran silenciosos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarantineSheet(
    onDismissSheet: () -> Unit,
    onReviewCourtesy: ((String)->Unit)? = null,
    courtesyReviewOnline:Boolean = false,
    courtesyCanPreview:Boolean = false,
    viewModel: QuarantineViewModel = hiltViewModel(),
    historial:HistorialDelCobroViewModel = hiltViewModel(),
) {
    val revisiones by historial.filas.collectAsState()
    // D68 #5: el nombre de cada tipo de pago del negocio, del catálogo guardado en el aparato (sirve sin red).
    val tiposDePago by viewModel.tiposDePago.collectAsState()
    val totalRevisiones by historial.total.collectAsState()
    val revisadas by historial.revisadas.collectAsState()
    val busqueda by historial.busqueda.collectAsState()
    val leyendoHistorial by historial.cargando.collectAsState()
    val nombres by historial.nombres.collectAsState() // se LEE en «Revisado por»: así repinta al llegar los nombres
    val mensajeHistorial by historial.mensaje.collectAsState()
    val resumenSinPermiso by historial.resumenSinPermiso.collectAsState()
    val items by viewModel.items.collectAsState()
    val failedPayments by viewModel.failedPayments.collectAsState()
    val failedReservations by viewModel.failedReservationActions.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val retryingPaymentId by viewModel.retryingPaymentId.collectAsState()
    val revisionCancelada by viewModel.revisionCancelada.collectAsState()
    val cobrosInterrumpidos by viewModel.cobrosInterrumpidos.collectAsState()
    val deOtrasSucursales by viewModel.deOtrasSucursales.collectAsState()
    val cuentasPagarDespues by viewModel.cuentasPagarDespues.collectAsState()
    val paquetesPorRevisar by viewModel.paquetesPorRevisar.collectAsState()
    val entregandoPaquete by viewModel.entregandoPaquete.collectAsState()
    var paquetePorDescartar by androidx.compose.runtime.remember { mutableStateOf<com.avoqado.pos.customers.data.PendingGrant?>(null) }
    val reintentandoCuenta by viewModel.reintentandoCuenta.collectAsState()
    val undoUi by viewModel.undo.ui.collectAsState()
    val resolviendoAviso by viewModel.resolviendoAviso.collectAsState()
    val resolveNotice by viewModel.resolveNotice.collectAsState()
    // Con red Y servidor (el mismo dato que ya decide «Revisar cortesía»): «Sí llegó» y resolver un aviso son en línea.
    val enLinea = courtesyReviewOnline
    var cuentaPorDescartar by androidx.compose.runtime.remember { mutableStateOf<com.avoqado.pos.payment.data.PayLaterRow?>(null) }
    // D63: «Dejar para soporte» pregunta antes de guardar (folio e importe del cobro elegido).
    val notasSoporte by viewModel.notasParaSoporte.collectAsState()
    val dejandoParaSoporte by viewModel.dejandoParaSoporte.collectAsState()
    var cobroParaSoporte by androidx.compose.runtime.remember { mutableStateOf<Pair<String, Int?>?>(null) }
    // Cancelaciones de cobro que no se pudieron confirmar: viven fuera del flujo de pago, así que
    // sin esta sección el cajero salía de la venta y nadie volvía a enterarse.
    val cancelacionesViewModel: com.avoqado.pos.payment.presentation.CancelacionesPendientesViewModel = hiltViewModel()
    val cancelaciones by cancelacionesViewModel.pendientes.collectAsState()
    val cancelacionesEnCurso by cancelacionesViewModel.enCurso.collectAsState()
    // 🔴 F1 (tarea 6): la revisión del gerente. Lo ven todos; firmar pide SU PIN primero y va sobre la fila exacta.
    val avisosDeCancelacion by cancelacionesViewModel.avisos.collectAsState()
    val cobrosPorConfirmar by cancelacionesViewModel.cobrosPorConfirmar.collectAsState()
    val pasoDelGerente by cancelacionesViewModel.paso.collectAsState()

    LaunchedEffect(Unit) { viewModel.load();historial.cargar() }
    LaunchedEffect(cancelacionesViewModel) {
        while (true) {
            cancelacionesViewModel.refrescar(recuperarImportes = true)
            kotlinx.coroutines.delay(5_000)
        }
    }
    LaunchedEffect(successMessage) { if (successMessage != null) historial.cargar() }
    // D63 (Codex r4): «Conservar para revisión» avisa por el canal de error y le pone motivo Q15: el cobro pasa a «Cobros por
    // revisar», donde está «Dejar para soporte». Sin recargar, había que cerrar y abrir la hoja para verlo.
    LaunchedEffect(errorMessage) { if (errorMessage != null) historial.cargar() }

    AvoqadoFullScreenModal(
        title = "Operaciones por revisar",
        onDismiss = onDismissSheet,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().imePadding()) {
                Column(
                    Modifier.padding(horizontal = AvoqadoTheme.spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        "Revisa operaciones rechazadas y cobros sin confirmar. Sus datos se conservan hasta que un gerente los revise.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (historial.canRead) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("Por revisar", "Historial").forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = revisadas == (index == 1),
                                    onClick = { historial.cambiarFiltro(index == 1, busqueda) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(label) }
                            }
                        }
                        SearchPillField(
                            query = busqueda,
                            onQueryChange = { historial.cambiarFiltro(revisadas, it) },
                            placeholder = "Buscar en historial",
                            modifier = Modifier.fillMaxWidth(),
                            height = 48.dp,
                        )
                        Text(
                            "Historial: ${revisiones.size} de $totalRevisiones cobros",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // D1/A4 (ensayo 16-nov): sin permiso no se ve el historial, pero sí qué espera a un gerente y cuánto se cobró
                        // de más. Mientras ese resumen carga (o si no hay nada que resumir), queda el encabezado de D7: aquí caen
                        // rondas, mesas y cobros, así que no dice «pago» (ensayo A5, 16-nov).
                        val resumen = resumenSinPermiso
                        if (resumen != null) {
                            Text(resumen, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        } else {
                            Text("Un gerente debe revisar estas operaciones.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (historial.canRead) mensajeHistorial?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    HorizontalDivider()
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AvoqadoTheme.spacing.xxl, vertical = AvoqadoTheme.spacing.lg),
                ) {
                    if (historial.canRead && revisiones.isEmpty() && (revisadas || (items.isEmpty() && failedPayments.isEmpty() && failedReservations.isEmpty() && cancelaciones.isEmpty() && cobrosPorConfirmar.isEmpty() && cobrosInterrumpidos.isEmpty() && cuentasPagarDespues.isEmpty() && paquetesPorRevisar.isEmpty() && deOtrasSucursales.isEmpty()))) {
                        item {
                            Text(
                                if (revisadas) "No hay cobros revisados." else "No hay operaciones pendientes de revisión.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xxl),
                            )
                        }
                    }
                    if (historial.canRead && revisiones.isNotEmpty()) {
                        item { SeccionDeRevision(if (revisadas) "Cobros revisados" else "Cobros por revisar") }
                    }
                    items(if (historial.canRead) revisiones else emptyList(), key = { "revision-${it.origen}-${it.id}" }) { row ->
                        var abierto by rememberSaveable("revision-${row.origen}-${row.id}") { mutableStateOf(false) }
                        val detalles = historial.detalle(row)
                        val captura = ConciliacionDelCobro.leerCaptura(row.q15CaptureJson, row.venueId, row.id)
                        val consultarRetenidoCero = row.estado == "EN_VUELO" &&
                            row.q15Reason == ConciliacionDelCobro.RESULTADO_NO_COMPROBADO && captura != null &&
                            captura.amountCents == 0 && captura.tipCents == 0 && captura.method == "CASH" &&
                            captura.tenderTypeId == null && captura.tenderRevision == null && captura.orderRequestJson != null
                        Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                            Text(if (row.q15ReviewedAt == null) "Cobro por revisar" else "Cobro revisado", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            captura?.let {
                                Text("Captura original · ${formatMoney(it.amountCents)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            }
                            ReferenciaDeRevision(historial.referencia(row), "Detectado ${formatTime(row.q15DetectedAt)}", abierto, { abierto = !abierto })
                            Text(historial.motivo(row), style = MaterialTheme.typography.bodyMedium)
                            row.q15ReviewedAt?.let { Text("Revisado por ${historial.revisor(row, nombres)} · ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
                            detalles.filter { it.startsWith("Se cobró de más:") }.forEach {
                                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (historial.puedeMarcar(row)) {
                                AccionDeRevision(text = "Marcar revisado", onClick = { historial.marcarRevisado(row) }, principal = true)
                            } else if (row.q15ReviewedAt == null) {
                                Text("El pago se conserva hasta comprobar su resultado. No vuelvas a cobrar.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (viewModel.canResolve && row.origen == "PAYMENT" && (row.estado == "FAILED" || consultarRetenidoCero)) {
                                    AccionDeRevision(text = "Consultar intento", onClick = { viewModel.retryPayment(row.id) }, enabled = retryingPaymentId == null, isLoading = retryingPaymentId == row.id)
                                }
                                // D63: la salida de gerente, con rastro; el cobro se queda aquí.
                                val nota = if (row.origen == "PAYMENT") viewModel.notaParaSoporte(row.id, notasSoporte) else null
                                if (nota != null) NotaDeSoporte(nota)
                                else if (viewModel.puedeDejarParaSoporte(row)) {
                                    AccionDeRevision(text = "Dejar para soporte", onClick = { cobroParaSoporte = row.id to captura?.amountCents },
                                        enabled = dejandoParaSoporte == null, isLoading = dejandoParaSoporte == row.id)
                                }
                            }
                            if (abierto) Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
                                Text("Folio ${row.id}", style = MaterialTheme.typography.bodySmall)
                                row.capturedOrderRef?.let { Text("Cuenta $it", style = MaterialTheme.typography.bodySmall) }
                                detalles.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (revisiones.size < totalRevisiones) {
                        item { AccionDeRevision(text = "Cargar más", onClick = historial::cargarMas, enabled = !leyendoHistorial, isLoading = leyendoHistorial) }
                    }
                    if (!revisadas) {
                        if (cobrosInterrumpidos.isNotEmpty()) item { SeccionDeRevision("Cobros interrumpidos") }
                        items(cobrosInterrumpidos, key = { "interrumpido-${it.id}" }) { cobro ->
                            var abierto by rememberSaveable("interrumpido-${cobro.id}") { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(tituloDelCobroInterrumpido(cobro.method, cobro.tenderTypeId, tiposDePago.firstOrNull { it.id == cobro.tenderTypeId }?.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatMoney(cobro.amountCents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                ReferenciaDeRevision("Folio ${cobro.id.take(8)}", formatTime(cobro.createdAt), abierto, { abierto = !abierto })
                                Text("La app se cerró mientras registraba este cobro.", style = MaterialTheme.typography.bodyMedium)
                                Text("¿El cliente pagó?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (viewModel.canResolve) {
                                    DecisionesDelCobro(
                                        onConservar = { viewModel.descartarCobroInterrumpido(cobro.id) },
                                        onConfirmar = { viewModel.confirmarCobroInterrumpido(cobro.id) },
                                    )
                                }
                                if (abierto) Text("Folio ${cobro.id}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                        // 27-oct: «Pagar después» que el servidor rechazó o creó por otro importe. Nunca se descartan solas.
                        if (cuentasPagarDespues.isNotEmpty()) item { SeccionDeRevision("Pagar después") }
                        items(cuentasPagarDespues, key = { "pagar-despues-${it.id}" }) { cuenta ->
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(com.avoqado.pos.payment.data.tituloDePagarDespues(cuenta), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatTime(cuenta.creadaEn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                com.avoqado.pos.payment.data.detalleDePagarDespues(cuenta)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                if (viewModel.canResolve) {
                                    if (cuenta.estado == com.avoqado.pos.payment.data.PayLaterRow.Estado.RECHAZADA) {
                                        AccionDeRevision(text = "Reintentar", onClick = { viewModel.reintentarCuenta(cuenta.id) },
                                            enabled = reintentandoCuenta == null, isLoading = reintentandoCuenta == cuenta.id, principal = true)
                                    }
                                    // Nunca «Reintentar» aquí: como venta sin red se saltaría el rechazo de precio.
                                    if (cuenta.estado == com.avoqado.pos.payment.data.PayLaterRow.Estado.RECUPERAR) {
                                        AccionDeRevision(text = "Pasar a carritos guardados", onClick = { viewModel.pasarACarritosGuardados(cuenta.id) }, principal = true)
                                    }
                                    AccionDeRevision(text = "Descartar", onClick = { cuentaPorDescartar = cuenta }, enabled = reintentandoCuenta != cuenta.id)
                                }
                            }
                            HorizontalDivider()
                        }
                        // D4 (5-nov): paquetes que el servidor rechazó o de cuyo cobro no se sabe. Nunca se van solos: el dinero pudo entrar.
                        if (paquetesPorRevisar.isNotEmpty()) item { SeccionDeRevision("Paquetes por entregar") }
                        items(paquetesPorRevisar, key = { "paquete-${it.id}" }) { paquete ->
                            val enDuda = paquete.estado == com.avoqado.pos.customers.data.PendingGrant.Estado.EN_DUDA
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(com.avoqado.pos.customers.data.tituloDePaquete(paquete), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatTime(paquete.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val deOtra = viewModel.esDeOtraSucursal(paquete)
                                (if (deOtra) com.avoqado.pos.customers.data.PAQUETE_DE_OTRA_SUCURSAL else paquete.motivo)?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (viewModel.canResolve && !deOtra) {
                                    AccionDeRevision(
                                        text = if (enDuda) "Sí se cobró: entregar" else "Reintentar",
                                        onClick = { if (enDuda) viewModel.paqueteSiSeCobro(paquete.id) else viewModel.reintentarPaquete(paquete.id) },
                                        enabled = entregandoPaquete == null,
                                        isLoading = entregandoPaquete == paquete.id,
                                        principal = true,
                                    )
                                    AccionDeRevision(
                                        text = if (enDuda) "No se cobró: descartar" else "Descartar",
                                        onClick = { paquetePorDescartar = paquete },
                                        enabled = entregandoPaquete != paquete.id,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        // F1 (tarea 6): las tarjetas en duda de este aparato. El piso sólo las ve; «Revisar» pide el PIN del gerente.
                        if (cobrosPorConfirmar.isNotEmpty()) item { SeccionDeRevision(com.avoqado.pos.payment.domain.CancelacionDeCobro.SECCION_COBROS_POR_CONFIRMAR) }
                        items(cobrosPorConfirmar, key = { "por-confirmar-${it.requestId}" }) { cobro ->
                            var abierto by rememberSaveable("por-confirmar-${cobro.requestId}") { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(com.avoqado.pos.payment.domain.CancelacionDeCobro.TITULO_TARJETA_POR_CONFIRMAR, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(importeDeLaFila(cobro.montoCents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                ReferenciaDeRevision("Folio ${cobro.requestId.take(8)}", cobro.desdeMillis?.let { formatTime(it) }.orEmpty(), abierto, { abierto = !abierto })
                                avisosDeCancelacion[cobro.requestId]?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Warning) }
                                AccionDeRevision(text = com.avoqado.pos.payment.domain.CancelacionDeCobro.BOTON_REVISAR,
                                    onClick = { cancelacionesViewModel.revisar(cobro) }, principal = true)
                                if (abierto) Text("Folio ${cobro.requestId}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                        if (cancelaciones.isNotEmpty()) item { SeccionDeRevision("Cancelaciones pendientes") }
                        items(cancelaciones, key = { "cancelacion-${it.id}" }) { cancelacion ->
                            var abierto by rememberSaveable("cancelacion-${cancelacion.id}") { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                val esAvisoDeCobro = cancelacionesViewModel.esAvisoDeCobro(cancelacion)
                                Text(if (esAvisoDeCobro) com.avoqado.pos.payment.domain.CancelacionDeCobro.SE_COBRO_AL_FINAL else com.avoqado.pos.payment.domain.CancelacionDeCobro.TITULO_PENDIENTE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                cancelacion.montoCents?.let { Text(formatMoney(it), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                                ReferenciaDeRevision("Folio ${cancelacion.id.take(8)}", formatTime(cancelacion.creadaEn), abierto, { abierto = !abierto })
                                // D19 (4-nov): con la terminal ya suelta ya no «se cancela sola»; se dice y se deja declarar.
                                if (!esAvisoDeCobro) Text(com.avoqado.pos.payment.domain.CancelacionDeCobro.cuerpoDeLaFila(cancelacion.sinRed, cancelacion.terminalLibre), style = MaterialTheme.typography.bodyMedium)
                                cancelacion.motivo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                val consultando = cancelacion.id in cancelacionesEnCurso
                                cancelacion.requestId?.let { avisosDeCancelacion[it] }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Warning) }
                                // F1 (tarea 6): «Revisar» = la revisión del gerente (PIN primero), incluso si la solicitud nunca llegó a la TPV.
                                val fila = cancelacionesViewModel.filaDe(cancelacion)
                                if (fila != null && com.avoqado.pos.payment.domain.CancelacionDeCobro.filaPuedeDeclarar(cancelacion.terminalLibre, cancelacion.sinRed, cancelacion.requestId, esAvisoDeCobro)) {
                                    AccionDeRevision(text = com.avoqado.pos.payment.domain.CancelacionDeCobro.BOTON_REVISAR,
                                        onClick = { cancelacionesViewModel.revisar(fila) }, enabled = !consultando, principal = true)
                                }
                                AccionDeRevision(text = if (consultando) "Consultando…" else com.avoqado.pos.payment.domain.CancelacionDeCobro.BOTON_VOLVER_A_CONSULTAR, onClick = { cancelacionesViewModel.volverAConsultar(cancelacion.id) }, enabled = !consultando)
                                if (abierto) Text("Folio ${cancelacion.id}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                        // D52: lo de otras sucursales, sin acciones: se resuelve con su sucursal abierta.
                        if (deOtrasSucursales.isNotEmpty()) item { SeccionDeRevision("De otras sucursales") }
                        items(deOtrasSucursales, key = { "otra-sucursal-${it.cobro.id}" }) { pendiente ->
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(tituloDelCobroInterrumpido(pendiente.cobro.method, pendiente.cobro.tenderTypeId, tiposDePago.firstOrNull { it.id == pendiente.cobro.tenderTypeId }?.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatMoney(pendiente.cobro.amountCents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Folio ${pendiente.cobro.id.take(8)} · ${formatTime(pendiente.cobro.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(textoDeCobroDeOtraSucursal(pendiente.sucursal), style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                        if (failedPayments.isNotEmpty()) item { SeccionDeRevision("Cobros sin confirmar") }
                        items(failedPayments, key = { "payment-${it.id}" }) { payment ->
                            var abierto by rememberSaveable("payment-${payment.id}") { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text("Cobro sin confirmar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatMoney(payment.amountCents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                payment.orderNumber?.let { Text("Orden $it", style = MaterialTheme.typography.bodyMedium) }
                                ReferenciaDeRevision("Folio ${payment.id.take(8)}", formatTime(payment.createdAt), abierto, { abierto = !abierto })
                                viewModel.loVendido(payment)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                                Text(viewModel.paymentResolutionHint(payment), style = MaterialTheme.typography.bodyMedium)
                                if (viewModel.canResolve) {
                                    Text("El pago sigue conservado. No vuelvas a cobrar.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    val soloConsulta = payment.paymentType == "ORDER" && payment.amountCents == 0 && payment.tipCents == 0
                                    // D63: una fila vieja sin sucursal retenida (no FAILED) no se reintenta desde aquí.
                                    if (payment.syncStatus == "FAILED") AccionDeRevision(text = if (soloConsulta) {
                                        if (retryingPaymentId == payment.id) "Consultando…" else "Consultar intento"
                                    } else if (retryingPaymentId == payment.id) "Reintentando…" else "Reintentar",
                                        onClick = { viewModel.retryPayment(payment.id) }, enabled = retryingPaymentId == null)
                                    val courtesy=soloConsulta && payment.syncStatus=="FAILED" && payment.q15Reason==null && payment.method=="CASH" &&
                                        payment.tenderTypeId==null && payment.tenderRevision==null
                                    if(courtesy) {
                                        TextButton(onClick={ onReviewCourtesy?.invoke(payment.id) },
                                            enabled=courtesyCanPreview && courtesyReviewOnline && onReviewCourtesy!=null && retryingPaymentId==null,
                                            modifier=Modifier.heightIn(min=48.dp)) { Text("Revisar cortesía") }
                                        val unavailable=when {
                                            !courtesyCanPreview -> "No tienes permiso para revisar esta cuenta. El intento sigue guardado."
                                            !courtesyReviewOnline -> "Necesitas conexión para corregir esta cuenta. El intento está guardado"
                                            onReviewCourtesy==null -> "La revisión no está disponible aquí. El intento sigue guardado."
                                            else -> null
                                        }
                                        unavailable?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                                // D63: si el servidor la rechaza siempre, «Reintentar» no basta: la salida de gerente, con rastro.
                                val nota = viewModel.notaParaSoporte(payment.id, notasSoporte)
                                if (nota != null) NotaDeSoporte(nota)
                                else if (viewModel.puedeDejarParaSoporte(payment)) {
                                    AccionDeRevision(text = "Dejar para soporte", onClick = { cobroParaSoporte = payment.id to payment.amountCents },
                                        enabled = dejandoParaSoporte == null && retryingPaymentId != payment.id, isLoading = dejandoParaSoporte == payment.id)
                                }
                                if (abierto) Text("Folio ${payment.id}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                        if (failedReservations.isNotEmpty()) item { SeccionDeRevision("Reservas sin sincronizar") }
                        items(failedReservations, key = { "reservation-${it.rowId}" }) { entry ->
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(viewModel.describeReservationAction(entry.action), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatTime(entry.createdAt, viewModel.zonaDe(KioskTap.venueOf(entry.payloadJson))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // Asistencia verificada (Codex r3 #8): de un teléfono tecleado sin red, el número, para saber a quién marcar.
                                com.avoqado.pos.kiosk.data.KioskPhone.parse(entry.payloadJson)?.takeIf { entry.action == com.avoqado.pos.kiosk.data.KioskPhone.ACTION }?.let {
                                    Text("Teléfono que tecleó: ${it.phone}", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (viewModel.isCorrectionNotice(entry)) {
                                    // D4: el SERVIDOR corrigió solo la llegada y dejó algo pendiente; se resuelve allá.
                                    viewModel.correctionNoticeLines(entry).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    if (viewModel.canFixArrival) {
                                        AccionDeRevision(
                                            text = UndoNoShowTexts.RESOLVE, // canónico de D4; los demás renglones dicen «Marcar resuelta»
                                            onClick = { viewModel.resolveCorrectionNotice(entry) },
                                            enabled = enLinea && resolviendoAviso == null,
                                            isLoading = resolviendoAviso == entry.rowId,
                                        )
                                        if (!enLinea) Text(UndoNoShowTexts.OFFLINE_RESOLVE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    viewModel.reservationRejectionReason(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    Text(viewModel.reservationResolutionHint(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (viewModel.canUndoNoShow(entry)) {
                                        AccionDeRevision(
                                            text = UndoNoShowTexts.BUTTON,
                                            onClick = { viewModel.undoNoShow(entry) },
                                            principal = true,
                                            enabled = enLinea && undoUi.busyId == null,
                                            isLoading = undoUi.busyId == entry.reservationId,
                                        )
                                        if (!enLinea) Text(UndoNoShowTexts.OFFLINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (viewModel.canResolve) {
                                        AccionDeRevision(text = "Marcar resuelta", onClick = { viewModel.dismissReservationAction(entry.rowId) })
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        if (items.isNotEmpty()) item { SeccionDeRevision("Operaciones rechazadas") }
                        items(items, key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(viewModel.describeType(item.type), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatTime(item.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                viewModel.describeMessage(item.type, item.message)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(viewModel.resolutionHint(item.errorCode, item.type, item.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (viewModel.canResolve && item.type != "PAY_CASH") {
                                    AccionDeRevision(text = "Marcar resuelta", onClick = { viewModel.dismiss(item.id) })
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (!viewModel.canResolve && (items.isNotEmpty() || failedPayments.isNotEmpty() || cobrosInterrumpidos.isNotEmpty() || cuentasPagarDespues.isNotEmpty() || paquetesPorRevisar.isNotEmpty())) {
                        item { Text("Pide a un gerente que revise y resuelva estas operaciones.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.lg)) }
                    }
                }
            }
        }
    }

    revisionCancelada?.let {
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = "Revisar intento cancelado",
            onDismiss = viewModel::cerrarRevisionCancelada,
            description = com.avoqado.pos.core.data.local.database.RevisionCanceladoCero.AVISO,
            dismissOnClickOutside = retryingPaymentId == null,
            actionButton = {
                com.avoqado.pos.designsystem.components.PrimaryButton(text="Marcar revisado",
                    onClick=viewModel::confirmarRevisionCancelada,enabled=retryingPaymentId==null,
                    isLoading=retryingPaymentId!=null)
            },
        ) { Text("El intento permanecerá en el historial con su folio y la comprobación del servidor.",style=MaterialTheme.typography.bodyMedium) }
    }

    // D63: dos toques, nunca uno: abrir y responder. La respuesta queda en la nota; no sube ni borra el cobro.
    cobroParaSoporte?.let { (id, importe) ->
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = "Dejar para soporte",
            onDismiss = { cobroParaSoporte = null },
            description = TEXTO_DEJAR_PARA_SOPORTE,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Text(listOfNotNull(importe?.let { formatMoney(it) }, "Folio ${id.take(8)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("¿El cliente pagó?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                com.avoqado.pos.payment.data.CobrosParaSoporte.Declaracion.entries.forEach { declaracion ->
                    AccionDeRevision(declaracion.texto, {
                        viewModel.dejarParaSoporte(id, declaracion)
                        cobroParaSoporte = null
                    }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    // Descartar pide confirmación: una RECHAZADA no existe en el servidor y se pierde; una REVISAR sí existe.
    cuentaPorDescartar?.let { cuenta ->
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = "Descartar",
            onDismiss = { cuentaPorDescartar = null },
            description = if (cuenta.estado == com.avoqado.pos.payment.data.PayLaterRow.Estado.REVISAR) {
                "La cuenta ya está en el servidor: descartarla sólo la quita de este aparato."
            } else {
                "Esta cuenta no llegó al servidor: si la descartas, se borra de este aparato."
            },
            actionButton = {
                com.avoqado.pos.designsystem.components.PrimaryButton(text = "Descartar", onClick = {
                    viewModel.descartarCuenta(cuenta.id)
                    cuentaPorDescartar = null
                })
            },
        ) {}
    }

    paquetePorDescartar?.let { paquete ->
        val enDuda = paquete.estado == com.avoqado.pos.customers.data.PendingGrant.Estado.EN_DUDA
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = if (enDuda) "¿No se cobró este paquete?" else "¿Descartar este paquete?",
            onDismiss = { paquetePorDescartar = null },
            description = if (enDuda) {
                "Se borra de este aparato y el cliente no lo recibe. Hazlo sólo si comprobaste que no se cobró."
            } else {
                "El cobro ya se hizo: si lo descartas, el cliente no recibe el paquete y hay que resolverlo con él (devolverle o darle sus clases a mano)."
            },
            actionButton = {
                com.avoqado.pos.designsystem.components.PrimaryButton(text = "Descartar", onClick = {
                    viewModel.descartarPaquete(paquete.id)
                    paquetePorDescartar = null
                })
            },
        ) {}
    }

    RevisionDelGerente(
        paso = pasoDelGerente,
        onIdentificar = cancelacionesViewModel::identificar,
        onFirmar = cancelacionesViewModel::firmar,
        onCerrar = cancelacionesViewModel::cerrar,
    )

    successMessage?.let {
        AvoqadoSuccessToast(message = it, onDismiss = viewModel::consumeSuccess)
    }

    // Un reintento rechazado se ve como rechazo, no como palomita verde.
    errorMessage?.let {
        AvoqadoErrorToast(message = it, onDismiss = viewModel::consumeError)
    }

    resolveNotice?.let { NoticeToast(it, viewModel::consumeResolveNotice) }
    UndoNoShowOverlays(viewModel.undo)
}

/**
 * Hora del NEGOCIO, no la del aparato.
 *
 * 🔴 Con `SimpleDateFormat` pelón se usa la zona del dispositivo: un cobro de
 * las 12:28 salía como "10 ago, 02:28". Justo en la pantalla donde un gerente
 * decide si un cobro entró, la hora estaba a 14 horas de la realidad — y con
 * eso no se puede cruzar nada contra el corte. Las otras 49 pantallas de la app
 * ya usan `VenueTimeZone`; ésta se había quedado fuera.
 */
private fun formatTime(epochMs: Long, timezone: String? = null): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .let { com.avoqado.pos.core.util.VenueTimeZone.at(it, timezone) }
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("es", "MX")))

private fun formatMoney(cents: Int): String =
    NumberFormat.getCurrencyInstance(Locale("es", "MX")).format(cents / 100.0)

/** F1: el importe de una fila; sin importe guardado (fila antigua, se está preguntando), «Importe por confirmar». Nunca otro número. */
private fun importeDeLaFila(cents: Int?): String = cents?.let(::formatMoney) ?: com.avoqado.pos.payment.domain.CancelacionDeCobro.IMPORTE_POR_CONFIRMAR

/** F1: el renglón que identifica la fila exacta para el gerente: importe · hora del negocio · folio corto. */
private fun renglonParaElGerente(c: com.avoqado.pos.payment.presentation.CobroPorConfirmar): String =
    listOfNotNull(importeDeLaFila(c.montoCents), c.desdeMillis?.let { formatTime(it) }, "Folio ${c.requestId.take(8)}").joinToString(" · ")

/**
 * 🔴 F1 (tarea 6): la revisión del gerente, sobre la fila EXACTA. Primero «Gerente: escribe tu PIN» (el mismo teclado de siempre); sólo
 * con el gerente identificado sale la decisión, «Firma del gerente» → «Firmar». El PIN viaja en el cuerpo de la firma y nunca se guarda;
 * quién puede firmar lo decide el SERVIDOR. La lógica vive en [com.avoqado.pos.payment.presentation.CancelacionesPendientesViewModel].
 */
@Composable
private fun RevisionDelGerente(
    paso: com.avoqado.pos.payment.presentation.PasoDelGerente,
    onIdentificar: (String) -> Unit,
    onFirmar: () -> Unit,
    onCerrar: () -> Unit,
) {
    val textos = com.avoqado.pos.payment.domain.CancelacionDeCobro
    when (paso) {
        com.avoqado.pos.payment.presentation.PasoDelGerente.Cerrado -> Unit
        is com.avoqado.pos.payment.presentation.PasoDelGerente.PedirPin -> {
            val renglon = renglonParaElGerente(paso.cobro)
            com.avoqado.pos.designsystem.components.ManagerOverrideSheet(
                prompt = com.avoqado.pos.core.data.network.ManagerOverrideCoordinator.Prompt(
                    id = paso.id,
                    permission = textos.PERMISO_DECLARAR_NO_COBRADO,
                    actionLabel = renglon,
                ),
                // Identificar no manda nada: el PIN se firma en el paso siguiente. `Granted` es lo que limpia el teclado.
                onSubmit = { pin -> onIdentificar(pin); com.avoqado.pos.core.data.network.OverrideResult.Granted(token = "", authorizedByName = "") },
                onDismiss = onCerrar,
                title = textos.PIN_DEL_GERENTE,
                description = if (paso.pinRechazado) "${textos.PIN_NO_PUEDE_FIRMAR} $renglon" else renglon,
            )
        }
        is com.avoqado.pos.payment.presentation.PasoDelGerente.Firmar -> com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = textos.FIRMA_DEL_GERENTE,
            description = textos.textoDeLaFirma(renglonParaElGerente(paso.cobro)),
            onDismiss = onCerrar,
            dismissOnClickOutside = false,
            actionButton = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    com.avoqado.pos.designsystem.components.PrimaryButton(
                        text = textos.BOTON_FIRMAR,
                        onClick = onFirmar,
                        enabled = !paso.enviando,
                        isLoading = paso.enviando,
                        fullWidth = true,
                    )
                    TextButton(onClick = onCerrar, enabled = !paso.enviando) { Text(textos.BOTON_CANCELAR_FIRMA) }
                }
            },
        ) {}
    }
}

/**
 * 🔴 Lo que queda por cancelar se VE fuera del flujo de cobro.
 *
 * Una cancelación que la terminal no confirmó sólo existía dentro de la pantalla de pago: el cajero
 * salía, la venta seguía abierta y nadie volvía a enterarse. Ámbar, como la cuarentena: nada se
 * perdió — falta que conste.
 */
@Composable
fun CancelacionesPendientesBanner(onClick: () -> Unit) {
    val viewModel: com.avoqado.pos.payment.presentation.CancelacionesPendientesViewModel = hiltViewModel()
    val pendientes by viewModel.pendientes.collectAsState()
    val porConfirmar by viewModel.cobrosPorConfirmar.collectAsState()
    // F1 (tarea 6): las tarjetas en duda de este aparato también se VEN aquí: es la puerta del gerente a «Cobros por confirmar».
    // ponytail: la lista durable no avisa sus cambios; se relee cada 5 s mientras la banda está montada.
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.refrescar()
            kotlinx.coroutines.delay(5_000)
        }
    }
    val texto = textoDeLaBandaDePorConfirmar(porConfirmar.size, pendientes.size) ?: return
    BannerDeRevision(texto = texto, necesitaAtencion = true, onClick = onClick)
}

/** F1: la banda de lo que espera al gerente. null = nada que avisar. PURA. */
internal fun textoDeLaBandaDePorConfirmar(cobros: Int, cancelaciones: Int): String? {
    val textos = com.avoqado.pos.payment.domain.CancelacionDeCobro
    return when {
        cobros == 0 && cancelaciones == 0 -> null
        cobros == 0 -> textos.bannerPendientes(cancelaciones)
        cancelaciones == 0 -> textos.bannerPorConfirmar(cobros)
        else -> "${textos.bannerPorConfirmar(cobros)} · ${textos.bannerPendientes(cancelaciones)}"
    }
}

@Composable
fun QuarantineBanner(count: Int, historyAvailable: Boolean = false, historyUnavailable: Boolean = false, actionLabel: String = "Revisar",
    applied: Int = 0, onClick: () -> Unit) {
    if (count <= 0 && !historyAvailable && !historyUnavailable) return
    BannerDeRevision(
        texto = textoDeLaBandaDeRevision(count, applied, historyUnavailable),
        necesitaAtencion = count > 0 || historyUnavailable,
        onClick = onClick,
        actionLabel = actionLabel,
    )
}

/**
 * D68 #2 (prueba final del 13-nov): la banda decía «3 operaciones necesitan revisión» y el freno de «Cerrar sesión», «Hay 1 operación
 * por revisar»: dos eran avisos de pagos que el SERVIDOR ya registró («Pago registrado · revisa las diferencias»), que no frenan. Esos
 * [applied] se dicen aparte, con su nombre, para que la banda y el freno cuenten lo mismo. PURA.
 */
internal fun textoDeLaBandaDeRevision(count: Int, applied: Int, historyUnavailable: Boolean = false): String {
    if (historyUnavailable) return "No se pudo leer el historial de cobros"
    if (count <= 0) return "Historial de cobros"
    val avisos = applied.coerceIn(0, count)
    val porRevisar = count - avisos
    val deAvisos = if (avisos == 1) "1 aviso de un pago ya registrado" else "$avisos avisos de pagos ya registrados"
    val deRevision = if (porRevisar == 1) "1 operación necesita revisión" else "$porRevisar operaciones necesitan revisión"
    return when {
        avisos == 0 -> deRevision
        porRevisar == 0 -> deAvisos
        else -> "$deRevision · $deAvisos"
    }
}

@Composable
private fun BannerDeRevision(texto: String, necesitaAtencion: Boolean, onClick: () -> Unit, actionLabel: String = "Revisar") {
    Row(
        Modifier.fillMaxWidth().background(if (necesitaAtencion) Warning.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).heightIn(min = 48.dp)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(if (necesitaAtencion) Icons.Filled.WarningAmber else Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(actionLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** D63: lo que promete «Dejar para soporte», en llano. */
internal const val TEXTO_DEJAR_PARA_SOPORTE =
    "El cobro se queda guardado en este aparato: no se sube ni se borra, y ya no impide cerrar sesión. Soporte lo revisa con tu respuesta."

/** D63: «Dejado para soporte por <nombre> · <hora> · «<respuesta>»». PURA. */
internal fun textoDeNotaDeSoporte(nota: com.avoqado.pos.payment.data.CobrosParaSoporte.Nota, hora: String): String =
    "Dejado para soporte por ${nota.actorNombre ?: "un gerente"} · $hora · «${nota.declaracion.texto}»"

@Composable
private fun NotaDeSoporte(nota: com.avoqado.pos.payment.data.CobrosParaSoporte.Nota) {
    Text(textoDeNotaDeSoporte(nota, formatTime(nota.marcadoEn)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Text("Sigue guardado en este aparato y ya no impide cerrar sesión.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SeccionDeRevision(titulo: String) {
    Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = AvoqadoTheme.spacing.lg, bottom = AvoqadoTheme.spacing.xxs))
}

@Composable
private fun ReferenciaDeRevision(referencia: String, fecha: String, abierto: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs)) {
            Text(referencia, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fecha, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (abierto) "Ocultar detalle" else "Ver detalle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Icon(if (abierto) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AccionDeRevision(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.widthIn(min = 160.dp, max = 240.dp),
    principal: Boolean = false,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    val contenido: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    if (principal) Button(onClick = onClick, enabled = enabled && !isLoading, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50), content = contenido)
    else OutlinedButton(onClick = onClick, enabled = enabled && !isLoading, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50), content = contenido)
}

@Composable
private fun DecisionesDelCobro(onConservar: () -> Unit, onConfirmar: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 480.dp) {
            Row(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                AccionDeRevision("Conservar para revisión", onConservar, Modifier.weight(1f).fillMaxHeight())
                AccionDeRevision("Sí, se cobró", onConfirmar, Modifier.weight(1f).fillMaxHeight(), principal = true)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                AccionDeRevision("Conservar para revisión", onConservar, Modifier.fillMaxWidth())
                AccionDeRevision("Sí, se cobró", onConfirmar, Modifier.fillMaxWidth(), principal = true)
            }
        }
    }
}

/**
 * D53 (Codex r1 #3): el título de un cobro interrumpido dice CÓMO se cobró. Antes decía «Cobro en efectivo» siempre, también con una
 * transferencia o una tarjeta de otra terminal, y el gerente decidía «¿el cliente pagó?» con el dato equivocado. PURA.
 */
internal fun tituloDelCobroInterrumpido(metodo: String, tipoDelCatalogo: String?, nombreDelTipo: String? = null): String {
    // D68 #5 (prueba final del 13-nov): con un tipo del negocio decía «Cobro con otro tipo de pago» aunque el aparato sabe su nombre
    // («Transferencia»). Se dice con el nombre que le puso el negocio; sin él (otra sucursal, catálogo sin bajar), el genérico.
    if (tipoDelCatalogo != null) return nombreDelTipo?.trim()?.takeIf { it.isNotEmpty() }?.let { "Cobro con $it" } ?: "Cobro con otro tipo de pago"
    val manual = runCatching { com.avoqado.pos.payment.domain.ManualPaymentMethod.valueOf(metodo) }.getOrNull()
    return if (manual != null) "Cobro con ${manual.label.lowercase()}" else "Cobro en efectivo"
}
