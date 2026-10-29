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
    val totalRevisiones by historial.total.collectAsState()
    val revisadas by historial.revisadas.collectAsState()
    val busqueda by historial.busqueda.collectAsState()
    val leyendoHistorial by historial.cargando.collectAsState()
    val nombres by historial.nombres.collectAsState() // se LEE en «Revisado por»: así repinta al llegar los nombres
    val mensajeHistorial by historial.mensaje.collectAsState()
    val items by viewModel.items.collectAsState()
    val failedPayments by viewModel.failedPayments.collectAsState()
    val failedReservations by viewModel.failedReservationActions.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val retryingPaymentId by viewModel.retryingPaymentId.collectAsState()
    val revisionCancelada by viewModel.revisionCancelada.collectAsState()
    val cobrosInterrumpidos by viewModel.cobrosInterrumpidos.collectAsState()
    val cuentasPagarDespues by viewModel.cuentasPagarDespues.collectAsState()
    val reintentandoCuenta by viewModel.reintentandoCuenta.collectAsState()
    var cuentaPorDescartar by androidx.compose.runtime.remember { mutableStateOf<com.avoqado.pos.payment.data.PayLaterRow?>(null) }
    // Cancelaciones de cobro que no se pudieron confirmar: viven fuera del flujo de pago, así que
    // sin esta sección el cajero salía de la venta y nadie volvía a enterarse.
    val cancelacionesViewModel: com.avoqado.pos.payment.presentation.CancelacionesPendientesViewModel = hiltViewModel()
    val cancelaciones by cancelacionesViewModel.pendientes.collectAsState()
    val cancelacionesEnCurso by cancelacionesViewModel.enCurso.collectAsState()

    LaunchedEffect(Unit) { viewModel.load();historial.cargar() }
    LaunchedEffect(successMessage) { if (successMessage != null) historial.cargar() }

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
                        Text("Un gerente debe revisar este pago.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (historial.canRead) mensajeHistorial?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    HorizontalDivider()
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AvoqadoTheme.spacing.xxl, vertical = AvoqadoTheme.spacing.lg),
                ) {
                    if (historial.canRead && revisiones.isEmpty() && (revisadas || (items.isEmpty() && failedPayments.isEmpty() && failedReservations.isEmpty() && cancelaciones.isEmpty() && cobrosInterrumpidos.isEmpty() && cuentasPagarDespues.isEmpty()))) {
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
                                Text("Cobro en efectivo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                        if (cancelaciones.isNotEmpty()) item { SeccionDeRevision("Cancelaciones pendientes") }
                        items(cancelaciones, key = { "cancelacion-${it.id}" }) { cancelacion ->
                            var abierto by rememberSaveable("cancelacion-${cancelacion.id}") { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                val esAvisoDeCobro = cancelacionesViewModel.esAvisoDeCobro(cancelacion)
                                Text(if (esAvisoDeCobro) com.avoqado.pos.payment.domain.CancelacionDeCobro.SE_COBRO_AL_FINAL else com.avoqado.pos.payment.domain.CancelacionDeCobro.TITULO_PENDIENTE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                cancelacion.montoCents?.let { Text(formatMoney(it), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                                ReferenciaDeRevision("Folio ${cancelacion.id.take(8)}", formatTime(cancelacion.creadaEn), abierto, { abierto = !abierto })
                                if (!esAvisoDeCobro) Text(if (cancelacion.sinRed) com.avoqado.pos.payment.domain.CancelacionDeCobro.CUERPO_SIN_RED else com.avoqado.pos.payment.domain.CancelacionDeCobro.CUERPO_PENDIENTE, style = MaterialTheme.typography.bodyMedium)
                                cancelacion.motivo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                val consultando = cancelacion.id in cancelacionesEnCurso
                                AccionDeRevision(text = if (consultando) "Consultando…" else com.avoqado.pos.payment.domain.CancelacionDeCobro.BOTON_VOLVER_A_CONSULTAR, onClick = { cancelacionesViewModel.volverAConsultar(cancelacion.id) }, enabled = !consultando)
                                if (abierto) Text("Folio ${cancelacion.id}", style = MaterialTheme.typography.bodySmall)
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
                                Text(viewModel.paymentResolutionHint(payment), style = MaterialTheme.typography.bodyMedium)
                                if (viewModel.canResolve) {
                                    Text("El pago sigue conservado. No vuelvas a cobrar.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    val soloConsulta = payment.paymentType == "ORDER" && payment.amountCents == 0 && payment.tipCents == 0
                                    AccionDeRevision(text = if (soloConsulta) {
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
                                if (abierto) Text("Folio ${payment.id}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                        if (failedReservations.isNotEmpty()) item { SeccionDeRevision("Reservas sin sincronizar") }
                        items(failedReservations, key = { "reservation-${it.rowId}" }) { entry ->
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(viewModel.describeReservationAction(entry.action), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatTime(entry.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                viewModel.reservationRejectionReason(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(viewModel.reservationResolutionHint(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (viewModel.canResolve) {
                                    AccionDeRevision(text = "Marcar resuelta", onClick = { viewModel.dismissReservationAction(entry.rowId) })
                                }
                            }
                            HorizontalDivider()
                        }
                        if (items.isNotEmpty()) item { SeccionDeRevision("Operaciones rechazadas") }
                        items(items, key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                                Text(viewModel.describeType(item.type), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(formatTime(item.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                item.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(viewModel.resolutionHint(item.errorCode, item.type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (viewModel.canResolve && item.type != "PAY_CASH") {
                                    AccionDeRevision(text = "Marcar resuelta", onClick = { viewModel.dismiss(item.id) })
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (!viewModel.canResolve && (items.isNotEmpty() || failedPayments.isNotEmpty() || cobrosInterrumpidos.isNotEmpty() || cuentasPagarDespues.isNotEmpty())) {
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

    successMessage?.let {
        AvoqadoSuccessToast(message = it, onDismiss = viewModel::consumeSuccess)
    }

    // Un reintento rechazado se ve como rechazo, no como palomita verde.
    errorMessage?.let {
        AvoqadoErrorToast(message = it, onDismiss = viewModel::consumeError)
    }
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
private fun formatTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .let { com.avoqado.pos.core.util.VenueTimeZone.at(it) }
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("es", "MX")))

private fun formatMoney(cents: Int): String =
    NumberFormat.getCurrencyInstance(Locale("es", "MX")).format(cents / 100.0)

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
    if (pendientes.isEmpty()) return
    BannerDeRevision(
        texto = com.avoqado.pos.payment.domain.CancelacionDeCobro.bannerPendientes(pendientes.size),
        necesitaAtencion = true,
        onClick = onClick,
    )
}

@Composable
fun QuarantineBanner(count: Int, historyAvailable: Boolean = false, historyUnavailable: Boolean = false, actionLabel: String = "Revisar", onClick: () -> Unit) {
    if (count <= 0 && !historyAvailable && !historyUnavailable) return
    BannerDeRevision(
        texto = if (historyUnavailable) "No se pudo leer el historial de cobros" else if (count <= 0) "Historial de cobros" else if (count == 1) "1 operación necesita revisión" else "$count operaciones necesitan revisión",
        necesitaAtencion = count > 0 || historyUnavailable,
        onClick = onClick,
        actionLabel = actionLabel,
    )
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
