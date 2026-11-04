package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import com.avoqado.pos.designsystem.theme.Warning
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.FullscreenHeaderNav
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.AssociatedRefundItem
import com.avoqado.pos.transactions.data.RefundApiException
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.model.OpcionDeDevolucion
import com.avoqado.pos.transactions.data.model.refundChainRules
import com.avoqado.pos.transactions.data.model.refundMethodForSubmit
import com.avoqado.pos.transactions.data.model.RefundAmountCalculator
import com.avoqado.pos.transactions.data.model.avisoDeSoloRegistro
import com.avoqado.pos.transactions.data.model.centavosDelImporte
import com.avoqado.pos.transactions.data.model.importeAjustadoAlTope
import com.avoqado.pos.transactions.data.model.importeParaDevolver
import com.avoqado.pos.transactions.data.model.puedeEnviarDevolucion
import com.avoqado.pos.transactions.data.model.leyendaDeDevolucion
import com.avoqado.pos.transactions.data.model.opcionesParaDevolver
import com.avoqado.pos.transactions.data.model.propinaMarcadaPorDefecto
import com.avoqado.pos.transactions.data.model.seDevuelveEnTerminal
import com.avoqado.pos.transactions.data.model.textoDeAutorizacionParaEfectivo
import com.avoqado.pos.transactions.data.model.textoDeSinPermisoParaReembolsar
import com.avoqado.pos.transactions.data.model.tipRefundCentsParaEnvio
import com.avoqado.pos.transactions.data.model.tipRefundCentsPorArticulos
import com.avoqado.pos.transactions.data.model.topeReembolsable
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionItem
import com.avoqado.pos.transactions.data.DesenlaceDeSaldo
import com.avoqado.pos.transactions.data.model.DEVOLVER_CON_SALDO
import com.avoqado.pos.transactions.data.model.bloqueoDeLaCadena
import com.avoqado.pos.transactions.data.model.defectoParaEnvio
import com.avoqado.pos.transactions.data.model.devolucionEscogida
import com.avoqado.pos.transactions.data.model.excepcionDeSoloSaldo
import com.avoqado.pos.transactions.data.model.faltaParaSaldo
import com.avoqado.pos.transactions.data.model.ofrecerEsPorDefecto
import com.avoqado.pos.transactions.data.model.restringe
import com.avoqado.pos.transactions.data.model.opcionesConSaldo
import com.avoqado.pos.transactions.data.model.pastillaDeSaldo
import com.avoqado.pos.transactions.data.model.soloSaldoEfectivo
import com.avoqado.pos.transactions.data.model.textoDelBotonDeReembolso
import com.avoqado.pos.transactions.presentation.exchange.ExchangeSettlement
import com.avoqado.pos.transactions.presentation.exchange.ExchangeStoreCreditCustomer
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet

private enum class RefundTab { ITEMS, AMOUNT }

@dagger.hilt.android.lifecycle.HiltViewModel
class ConexionDeDevolucionViewModel @javax.inject.Inject constructor(
    red: com.avoqado.pos.core.util.ConnectivityMonitor,
    val almacen: com.avoqado.pos.core.data.local.SecureStorage,
) : androidx.lifecycle.ViewModel() {
    val conectado = red.isConnected
    val servidor = red.isServerReachable

    internal suspend fun <T> iniciar(necesitaRed: Boolean, vigente: () -> Boolean, peticion: suspend () -> T): T? {
        if (!vigente() || necesitaRed && (!conectado.value || !servidor.value)) return null
        return peticion()
    }
}

private data class ReasonOption(val code: String, val label: String)

private val REASONS = listOf(
    ReasonOption("RETURNED_GOODS", "Productos devueltos"),
    ReasonOption("ACCIDENTAL_CHARGE", "Cargo accidental"),
    ReasonOption("CANCELLED_ORDER", "Pedido cancelado"),
    ReasonOption("FRAUDULENT_CHARGE", "Cargo fraudulento"),
    ReasonOption("OTHER", "Otro"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueRefundSheet(
    transaction: Transaction,
    maxRefundable: Double,
    refundRepository: RefundRepository,
    // 🔴 NO recibe el CashDrawerRepository, y es a propósito: esta pantalla no
    // tiene por qué saber que el cajón existe. El egreso del reembolso lo escribe
    // el SERVIDOR (ver el comentario en `onSuccess`); volver a pasar el repositorio
    // aquí es el primer paso para que alguien reinstale el doble descuento.
    terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService,
    /** `payments:refund-to-cash` de quien inició sesión: sin él, escoger efectivo avisa que lo hace un encargado. */
    puedeDevolverEnEfectivo: Boolean,
    /** Switch del local `managerPinOverrideEnabled`: decide si el aviso promete el código del encargado. */
    codigoDeEncargadoActivo: Boolean,
    onDismiss: () -> Unit,
    onRefunded: () -> Unit,
    soloCompleto: Boolean = false,
    /**
     * El envío a saldo a favor lo lleva quien abre la hoja (el detalle, `TransactionsViewModel.enviarReembolsoASaldo`) en un ámbito que
     * sobrevive a la hoja (I2): si la cierran con el POST en vuelo, el envío sigue y el detalle se entera. Sin él no se ofrece saldo.
     */
    enviarASaldo: ((cuerpo: String, idempotencyKey: String) -> kotlinx.coroutines.Deferred<DesenlaceDeSaldo>)? = null,
    /**
     * 🔴 La guarda de TODA devolución de la hoja (efectivo, transferencia, tarjeta, terminal y saldo): con un saldo a favor de esta venta
     * sin confirmar o en vuelo, devuelve el motivo y no se manda (`TransactionsViewModel.motivoParaNoReembolsar`). `null` = adelante.
     */
    antesDeReembolsar: suspend () -> String? = { null },
    conexion: ConexionDeDevolucionViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    saldo: SaldoAFavorDelReembolsoViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val spacing = AvoqadoTheme.spacing
    val corners = AvoqadoTheme.cornerRadius
    val scope = rememberCoroutineScope()
    val conectado by conexion.conectado.collectAsState()
    val servidor by conexion.servidor.collectAsState()
    val aSaldo = transaction.refundToGiftCardBalance == true

    // --- Saldo a favor (spec 2026-10-30 §4, §5.5; A3) ---
    LaunchedEffect(transaction.id) { saldo.abrir(transaction) }
    val storeCreditOnly by saldo.storeCreditOnly.collectAsState()
    val storeCreditAvailable by saldo.storeCreditAvailable.collectAsState()
    val clienteDelSaldo by saldo.cliente.collectAsState()
    val guardandoTelefono by saldo.guardando.collectAsState()
    val mensajeDelSaldo by saldo.mensaje.collectAsState()
    var escogiendoCliente by remember(transaction.id) { mutableStateOf(false) }
    var altaDesde by remember(transaction.id) { mutableStateOf<String?>(null) }
    // El alta que pidió el formulario abierto: misma persistencia que su bandera, y se borra al abrir otro (Codex apps r5).
    var altaDelFormulario by remember(transaction.id) { mutableStateOf<Long?>(null) }
    var reason by remember(transaction.id) { mutableStateOf<ReasonOption?>(null) }
    var esPorDefecto by remember(transaction.id) { mutableStateOf(false) }
    val soloSaldo = soloSaldoEfectivo(storeCreditOnly, storeCreditAvailable)

    // Sólo la tarjeta que cobró NUESTRA terminal se devuelve allá; lo demás se reembolsa como el efectivo.
    val chain = refundChainRules(transaction.tenderSystemKind, transaction.chainRefundMethod,
        transaction.chainRefundLabel, transaction.chainRefundBlockedCode)
    // «Devolver con»: null = por el mismo medio. Sólo hay selector si el servidor lo dice (`canChooseRefundMethod`), o si se puede
    // devolver a saldo a favor (entonces también una tarjeta de la terminal gana su pastilla del mismo medio). La venta de una
    // tarjeta de regalo (`soloCompleto`) no se devuelve a saldo.
    var devolverConAMano by remember(transaction.id) { mutableStateOf<String?>(null) }
    val pastillaDelSaldo = if (soloCompleto || enviarASaldo == null) null else pastillaDeSaldo(transaction.storeCreditOption, storeCreditAvailable,
        ExchangeSettlement.storeCreditApplies(transaction), conectado && servidor)
    val opcionesDeDevolucion = opcionesConSaldo(
        base = if (chain.isCredit) chain.options else if (aSaldo || (!soloCompleto && transaction.canChooseRefundMethod == true)) {
            opcionesParaDevolver(transaction.method, transaction.tenderLabel, transaction.refundToGiftCardBalance)
        } else {
            emptyList()
        },
        original = opcionesParaDevolver(transaction.method, transaction.tenderLabel).first(),
        saldo = pastillaDelSaldo,
        restringe = restringe(soloSaldo, reason?.code, esPorDefecto),
    )
    val devolverCon = devolucionEscogida(opcionesDeDevolucion, devolverConAMano)
    // O-1 (A5): lo que se ve elegido se queda mientras se siga ofreciendo (marcar «Es por defecto» ya no pasa solo a «Efectivo»).
    SideEffect { if (devolverConAMano != devolverCon) devolverConAMano = devolverCon }
    val aSaldoAFavor = devolverCon == DEVOLVER_CON_SALDO
    // El teléfono a medio escribir vive aquí y no en su fila: la fila cambia de lugar al cruzar el corte de las dos columnas en WINDOWS, y
    // con ella se perdería. Sus llaves le dan la vida que tenía allá: nace vacío cada vez que aparece el campo.
    val pideTelefono = aSaldoAFavor && clienteDelSaldo?.let { it.phoneChecked && it.phone.isNullOrBlank() } == true
    var telefonoDelSaldo by rememberSaveable(clienteDelSaldo?.id, pideTelefono) { mutableStateOf("") }
    val bloqueo = bloqueoDeLaCadena(chain, devolverCon)
    val enTerminal = !chain.isCredit && !aSaldo && !aSaldoAFavor && seDevuelveEnTerminal(transaction.refundOnTerminal, transaction.method)
    val necesitaRed = aSaldo || soloCompleto || aSaldoAFavor
    val contexto = remember(transaction.id) { conexion.almacen.venueId to conexion.almacen.userId }
    val montada = remember(transaction.id) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(montada) { onDispose { montada.set(false) } }
    fun vigente() = montada.get() && (!necesitaRed || contexto == (conexion.almacen.venueId to conexion.almacen.userId))

    var tab by remember(transaction.id) {
        mutableStateOf(if (!soloCompleto && transaction.items.isNotEmpty()) RefundTab.ITEMS else RefundTab.AMOUNT)
    }
    var selectedItemIds by remember(transaction.id) { mutableStateOf(emptySet<String>()) }
    var refundQtyByItem by remember(transaction.id) { mutableStateOf(emptyMap<String, Int>()) }
    var restockItemIds by remember(transaction.id) { mutableStateOf(emptySet<String>()) }
    var amountStr by remember(transaction.id) { mutableStateOf("") }
    var reasonMenuOpen by remember(transaction.id) { mutableStateOf(false) }
    var submitting by remember(transaction.id) { mutableStateOf(false) }
    var errorMsg by remember(transaction.id) { mutableStateOf<String?>(null) }

    // --- Abrir la devolución en una terminal física ---
    //
    // 🔴 Esto NO registra un reembolso en Avoqado: la TPV lo registra ella sola
    // cuando el dinero se mueve. Registrarlo también aquí lo contaría dos veces.
    var enviandoATerminal by remember(transaction.id) { mutableStateOf(false) }
    var terminalMsg by remember(transaction.id) { mutableStateOf<String?>(null) }
    var terminalAbierta by remember(transaction.id) { mutableStateOf(false) }
    var terminalesParaElegir by remember(transaction.id) {
        mutableStateOf<List<com.avoqado.pos.payment.data.OnlineTerminal>>(emptyList())
    }
    fun puedeEnviar() = vigente() &&
        puedeEnviarDevolucion(aSaldo || aSaldoAFavor, soloCompleto, conectado && servidor, submitting || enviandoATerminal || guardandoTelefono)

    fun mandarATerminal(terminalId: String) {
        if (!puedeEnviar() || terminalAbierta) return
        enviandoATerminal = true
        scope.launch {
            terminalMsg = null
            val motivo = antesDeReembolsar()
            if (motivo != null) { enviandoATerminal = false; terminalMsg = motivo; return@launch }
            val resultado = conexion.iniciar(necesitaRed, ::vigente) { terminalPaymentService.requestRefundOnTerminal(
                terminalId = terminalId,
                paymentId = transaction.id,
                reason = reason?.label,
                // «Sólo saldo a favor»: la terminal exige la excepción que invoca la cajera; sin «sólo saldo» no viaja.
                storeCreditOnlyException = excepcionDeSoloSaldo(soloSaldo, reason?.code, esPorDefecto),
            ) }
            enviandoATerminal = false
            if (!vigente()) return@launch
            if (resultado == null) { terminalMsg = com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED; return@launch }
            resultado.fold(
                onSuccess = {
                    // 🔴 NO se cierra como "reembolsado": todavía no se devolvió
                    // nada. Se le dice al cajero exactamente qué pasó y qué falta,
                    // porque si esto se pintara como éxito daría por cerrada una
                    // devolución que sigue esperando a alguien en el aparato.
                    terminalAbierta = true
                },
                onFailure = { e ->
                    terminalMsg = textoDeFalloAlAbrirEnTerminal(e)
                },
            )
        }
    }

    fun abrirEnTerminal() {
        if (!puedeEnviar() || terminalAbierta) return
        enviandoATerminal = true
        scope.launch {
            terminalMsg = null
            val lista = conexion.iniciar(necesitaRed, ::vigente) { terminalPaymentService.fetchOnlineTerminals() }
            enviandoATerminal = false
            if (!vigente()) return@launch
            if (lista == null) { terminalMsg = com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED; return@launch }
            when (lista) {
                is com.avoqado.pos.payment.data.TerminalListResult.Error -> {
                    enviandoATerminal = false
                    terminalMsg = lista.message
                }
                is com.avoqado.pos.payment.data.TerminalListResult.Success -> {
                    enviandoATerminal = false
                    when (lista.terminals.size) {
                        0 -> terminalMsg = "No hay terminales conectadas. Abre la app en la terminal e inténtalo de nuevo."
                        1 -> mandarATerminal(lista.terminals.first().terminalId)
                        // Con varias, la elige una persona: mandar la devolución a
                        // la terminal equivocada la abre lejos de quien la espera.
                        else -> terminalesParaElegir = lista.terminals
                    }
                }
            }
        }
    }
    // Include-tip toggle for amount refunds on payments that had tip. Defaults
    // ON → backend uses proportional split. OFF → send tipRefundCents=0 so the
    // refund pulls 100% from the sale, leaving the staff tip intact.
    val paymentTipAmount = transaction.tipAmount
    var includeTip by remember(transaction.id) { mutableStateOf(true) }

    val parsedAmount = importeParaDevolver(amountStr.replace(',', '.').toDoubleOrNull() ?: 0.0, maxRefundable, soloCompleto)
    val selectedItems = transaction.items.filter { item ->
        item.id != null && selectedItemIds.contains(item.id)
    }
    val itemsAmount = RefundAmountCalculator.calculateSelectedAmount(
        items = transaction.items,
        selectedIds = selectedItemIds,
        refundQtyByItem = refundQtyByItem,
    )
    val restockableItems = selectedItems.filter { item ->
        item.id != null && item.trackInventory
    }

    // Reembolso POR ARTÍCULOS: la casilla arranca marcada si se devuelve toda la venta que queda;
    // si el cajero la toca, manda su elección hasta cerrar la hoja.
    val propinaRestante = transaction.remainingRefundableTip ?: 0.0
    var propinaArticulosAMano by remember(transaction.id) { mutableStateOf<Boolean?>(null) }
    val propinaArticulosMarcada = propinaArticulosAMano
        ?: propinaMarcadaPorDefecto(itemsAmount, transaction.remainingRefundableSale)
    // Sólo con artículos elegidos: marcar la casilla ANTES de elegir no puede armar un total de puro propina.
    // La propina se topa con lo que queda del TOTAL tras los artículos (`maxRefundable`): con un acumulado
    // histórico sin filas, venta + propina puede sumar más de lo que el servidor deja salir. UN solo cálculo
    // (`tipRefundCentsPorArticulos`) alimenta el monto de la casilla, el resumen, el total y la petición.
    val propinaQueCabe = (
        tipRefundCentsPorArticulos(true, transaction.remainingRefundableTip, maxRefundable, itemsAmount) ?: 0
    ) / 100.0
    val propinaArticulosCents = if (tab == RefundTab.ITEMS && itemsAmount > 0) {
        tipRefundCentsPorArticulos(
            propinaArticulosMarcada,
            transaction.remainingRefundableTip,
            maxRefundable,
            itemsAmount,
        )
    } else {
        null
    }
    val propinaArticulos = (propinaArticulosCents ?: 0) / 100.0

    val amountToRefund = if (tab == RefundTab.ITEMS) itemsAmount else parsedAmount
    // 🔴 El tope depende de si la propina viaja. Con la casilla desmarcada la app manda
    // `tipRefundCents = 0` y el servidor lee el importe ENTERO como venta: ofrecer el
    // disponible con propina ($220 sobre una venta de $200) terminaba en 400 (Testarudo,
    // 17-sep-2026). Los artículos siempre son venta, nunca propina.
    val topeActual = if (tab == RefundTab.ITEMS) {
        transaction.remainingRefundableSale ?: maxRefundable
    } else {
        topeReembolsable(
            remainingRefundable = maxRefundable,
            remainingRefundableSale = transaction.remainingRefundableSale,
            paymentTipAmount = paymentTipAmount,
            includeTip = includeTip,
        )
    }
    // Lo que le falta al saldo a favor: red, a quién abonar y su teléfono (los textos del Cambio).
    val faltaSaldo = if (aSaldoAFavor) pastillaDelSaldo?.disabledReason ?: faltaParaSaldo(conectado && servidor, clienteDelSaldo) else null
    val itemsParaEnvio = selectedItems.mapNotNull { item ->
        item.id?.let { id ->
            AssociatedRefundItem(
                orderItemId = id,
                // Lo que QUEDA de la línea: con la original, una línea ya devuelta en parte rebotaba con 400.
                quantity = refundQtyByItem[id] ?: item.refundableQty,
            )
        }
    }
    val restockParaEnvio = restockableItems.mapNotNull { it.id }.filter { restockItemIds.contains(it) }
    val canSubmit = reason != null &&
        bloqueo == null &&
        faltaSaldo == null &&
        amountToRefund > 0 &&
        amountToRefund <= topeActual + 0.001 &&
        puedeEnviar() && !(soloCompleto && enTerminal)

    if (terminalesParaElegir.isNotEmpty()) {
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = "¿En cuál terminal?",
            description = "Ahí se abrirá la devolución para que la confirmen con la tarjeta.",
            onDismiss = { terminalesParaElegir = emptyList() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (necesitaRed && (!conectado || !servidor)) Text(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED)
                terminalesParaElegir.forEach { terminal ->
                    PrimaryButton(
                        text = terminal.name.ifBlank { terminal.terminalId },
                        enabled = controlDeTerminalDeDevolucion(necesitaRed, conectado, servidor),
                        onClick = {
                            if (controlDeTerminalDeDevolucion(necesitaRed, conexion.conectado.value, conexion.servidor.value)) {
                                terminalesParaElegir = emptyList()
                                mandarATerminal(terminal.terminalId)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        conXPropia = true,
        aPantallaCompleta = true,
    ) {
        val bandaScroll = rememberScrollState()
        val pieScroll = rememberScrollState()
        AvoqadoFullscreenHeader(
            title = "Emitir reembolso",
            onNav = onDismiss,
            navStyle = FullscreenHeaderNav.CLOSE,
            showDivider = true,
        )
        // One column: three-band layout so footer (amount summary + Reembolsar) stays
        // visible on small tablets (≈9"): tabs + scrollable middle (weight=1f) + fixed
        // footer. Two columns (`enDosColumnas`): what on the left, how on the right.
        //
        // 🔴 EL `BoxWithConstraints` NO ES DECORATIVO: es lo ÚNICO que sabe cuánto alto
        // queda DE VERDAD, porque lleva el `imePadding` encima y por tanto su `maxHeight`
        // ya viene con el teclado descontado. Sin ese dato la hoja no puede reaccionar al
        // teclado, y lo que pasaba es esto (medido en aparato el 2026-09-12, forzando
        // 360×640 dp en el OrderPAD 3): al tocar el campo de importe, el selector de
        // motivo, el resumen, la casilla «Incluir propina» y **los botones Cancelar y
        // Reembolsar** quedaban debajo del teclado, y como el pie NO se desliza, no había
        // forma de alcanzarlos sin cerrar el teclado — sin una sola señal de que existieran.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
                // a su ventana, asi que el ajuste va en el contenido.
                .imePadding(),
        ) {
            val dosColumnas = enDosColumnas(maxWidth.value, soloCompleto)
            val pieFijo = pieFijoEnDosColumnas(maxHeight.value)
            // 🔴 Lo que le quedaría a la lista sale de medir las pestañas y el pie, nunca la banda: al soltar su peso, la banda crecería y regresaría
            // al modo anterior, en bucle. Ninguna de estas medidas depende del modo. El pie de «Por importe» trae otros renglones, y lo medido allá no
            // decide aquí. Pero `onSizeChanged` calla si el alto no cambió (sin propina, escoger el saldo allá y volver): pasado el primer cuadro de
            // regreso, lo último medido ya es de Artículos.
            var altoDePestanas by remember { mutableIntStateOf(0) }
            var altoDelPie by remember { mutableIntStateOf(0) }
            var altoDelPieEnArticulos by remember { mutableIntStateOf(0) }
            LaunchedEffect(tab) {
                if (tab == RefundTab.ITEMS) {
                    withFrameNanos { }
                    altoDelPieEnArticulos = altoDelPie
                }
            }
            val loDemasEnArticulos = if (tab == RefundTab.ITEMS) {
                with(LocalDensity.current) { (altoDePestanas + altoDelPieEnArticulos).toDp() + spacing.md }.value
            } else {
                null
            }
            val todoSeDesliza = !dosColumnas && !pieFijoEnUnaColumna(maxHeight.value, loDemasEnArticulos)
            // Con pie fijo, en «Por importe» de una columna la banda sólo trae el aviso de la terminal: va de su tamaño y no se desliza sola.
            val bandaSeDesliza = !todoSeDesliza && (dosColumnas || soloCompleto || tab == RefundTab.ITEMS)
            val anchoDeColumna = if (dosColumnas) maxWidth / 2 else maxWidth
            // En un celular, el aviso de la terminal en un renglón dejaría el texto en ~100 dp: ahí se apila.
            val avisoEnRenglon = anchoDeColumna >= 480.dp

            // Lo que se acomoda distinto según el ancho, sobre el mismo estado. Cambiar de rama recrea lo de adentro, y por eso ninguna
            // pieza guarda nada: lo capturado vive arriba del cuerpo y sobrevive a cruzar el corte en WINDOWS.
            val errorDelEnvio: @Composable () -> Unit = {
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }

            // 🔴 En una columna, el campo y la casilla NO van en la banda que se desliza: la casilla decide si la propina del mesero se
            // devuelve, o sea que es DINERO. Con el teclado abierto la banda se encogía tanto que la casilla salía de la pantalla y el
            // cajero confirmaba «incluir propina» sin poder verla (Sunmi, 2026-09-11). En dos columnas van a la izquierda, con todo el
            // alto de esa columna, y el reparto se sigue leyendo junto al botón, en `cierre`.
            val importeYSuPropina: @Composable () -> Unit = {
                // En la banda de dos columnas ya los separa su `spacedBy`.
                val separacion = if (dosColumnas) Modifier else Modifier.padding(top = spacing.md)
                if (tab == RefundTab.AMOUNT && !soloCompleto) {
                    AmountBody(
                        amountStr = amountStr,
                        onAmountChange = { amountStr = it },
                        modifier = separacion,
                    )
                }

                if (tab == RefundTab.AMOUNT && paymentTipAmount > 0 && !soloCompleto) {
                    FilaIncluirPropina(
                        paymentTipAmount = paymentTipAmount,
                        includeTip = includeTip,
                        onIncludeTipChange = { marcada ->
                            includeTip = marcada
                            // Al bajar el tope, lo escrito que ya no cabe se recorta; lo que cabe no se toca.
                            amountStr = importeAjustadoAlTope(
                                amountStr = amountStr,
                                tope = topeReembolsable(
                                    remainingRefundable = maxRefundable,
                                    remainingRefundableSale = transaction.remainingRefundableSale,
                                    paymentTipAmount = paymentTipAmount,
                                    includeTip = marcada,
                                ),
                            )
                        },
                        modifier = separacion,
                    )
                }
            }

            // Qué se devuelve: las pestañas fijas y la banda con la lista.
            val queSeDevuelve: @Composable ColumnScope.() -> Unit = {
                if (!soloCompleto) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { altoDePestanas = it.height }
                        .padding(bottom = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    TabPill(
                        label = "Reembolsar artículos",
                        active = tab == RefundTab.ITEMS,
                        modifier = Modifier.weight(1f),
                        enabled = transaction.items.isNotEmpty(),
                        onClick = { tab = RefundTab.ITEMS },
                    )
                    TabPill(
                        label = "Reembolsos por importe",
                        active = tab == RefundTab.AMOUNT,
                        modifier = Modifier.weight(1f),
                        onClick = { tab = RefundTab.AMOUNT },
                    )
                }

                // 🔴 `verticalScroll` NO es cosmético. Sin él, lo que no cabía se
                // recortaba en silencio: en una tablet el selector de motivo quedaba
                // dibujado fuera de la pantalla y, como `canSubmit` exige motivo, el
                // botón "Reembolsar" jamás se encendía. La devolución era imposible y
                // nada en pantalla lo explicaba.
                // 🔴 En Artículos con pie fijo, la banda toma el alto sobrante y el pie toca el fondo: lo que sobra queda entre la lista y el pie. En
                // una hoja que medía según su contenido eso abría un hueco (Testarudo, 11-sep); a pantalla completa, sin esto, con 2 artículos,
                // Cancelar y Reembolsar quedan a media pantalla (OrderPAD 3, 800×1280, 3-nov). La tarjeta de regalo sigue como iba, con `fill = false`.
                Box(
                    modifier = when {
                        !bandaSeDesliza -> Modifier
                        soloCompleto -> Modifier.weight(1f, fill = false)
                        else -> Modifier.weight(1f)
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Sin un alto que repartir, esto sería un `verticalScroll` con alto infinito, y truena.
                            .then(if (bandaSeDesliza) Modifier.verticalScroll(bandaScroll) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        // 🔴 Un cobro con TARJETA de la terminal no se devuelve desde aquí.
                        //
                        // La devolución a la tarjeta la hace la TERMINAL, con su propia función; no hay API para ello.
                        // Aquí sólo se registra, con el método del cobro original. Sin este aviso, un gerente devuelve
                        // un cobro con tarjeta desde el POS, el sistema lo da por hecho, y el cliente no recibe nada.
                        //
                        // Todo lo demás —transferencia, tipos de pago del negocio, tarjeta de otra terminal— se reembolsa
                        // como el efectivo, sin aviso (regla del founder, 30-sep-2026; antes bastaba «no es CASH» y una
                        // transferencia pedía la terminal).
                        //
                        // Compacto a propósito: título y botón en un renglón cuando cabe. Apilado medía ~180 dp y en la
                        // D3 horizontal la banda sólo tenía ~90: se veía medio botón (Testarudo, 30-sep).
                        if (enTerminal) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = spacing.md)
                                    .clip(RoundedCornerShape(spacing.md))
                                    .background(Warning.copy(alpha = 0.12f))
                                    .padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                            ) {
                                val textoDelAviso: @Composable (Modifier) -> Unit = { mod ->
                                    Column(modifier = mod, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                                        Text(
                                            text = "Este cobro fue con tarjeta",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        // Lo que el cajero necesita saber: qué pasó, qué falta, y que
                                        // todavía NO se ha devuelto el dinero.
                                        Text(
                                            text = if (terminalAbierta) {
                                                "Abierta en la terminal. Falta que la confirmen ahí con la tarjeta — hasta " +
                                                    "entonces no se ha devuelto nada. Cuando se haga, la venta se actualiza sola."
                                            } else {
                                                "La devolución a la tarjeta se hace en la terminal. Ábrela desde aquí y que la " +
                                                    "confirmen con la tarjeta."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                val botonDeTerminal: @Composable (Modifier) -> Unit = { mod ->
                                    PrimaryButton(
                                        text = if (enviandoATerminal) "Abriendo…" else "Abrir en la terminal",
                                        onClick = { abrirEnTerminal() },
                                        enabled = puedeEnviar() && !terminalAbierta,
                                        modifier = mod,
                                    )
                                }
                                when {
                                    terminalAbierta -> textoDelAviso(Modifier.fillMaxWidth())
                                    avisoEnRenglon -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                                    ) {
                                        textoDelAviso(Modifier.weight(1f))
                                        botonDeTerminal(Modifier)
                                    }
                                    else -> {
                                        textoDelAviso(Modifier.fillMaxWidth())
                                        botonDeTerminal(Modifier.fillMaxWidth())
                                    }
                                }
                                terminalMsg?.let { msg ->
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        when (tab) {
                            RefundTab.ITEMS -> ItemsBody(
                                transaction = transaction,
                                selectedIds = selectedItemIds,
                                refundQty = refundQtyByItem,
                                restockIds = restockItemIds,
                                maxRefundable = topeActual,
                                onToggle = { id ->
                                    selectedItemIds = if (selectedItemIds.contains(id)) {
                                        restockItemIds = restockItemIds - id
                                        selectedItemIds - id
                                    } else {
                                        selectedItemIds + id
                                    }
                                },
                                onQtyChange = { id, delta ->
                                    val item = transaction.items.firstOrNull { it.id == id } ?: return@ItemsBody
                                    val remaining = if (item.remainingQty > 0) item.remainingQty else (item.quantity - item.refundedQty).coerceAtLeast(1)
                                    val current = refundQtyByItem[id] ?: remaining
                                    val next = (current + delta).coerceIn(1, remaining)
                                    refundQtyByItem = refundQtyByItem + (id to next)
                                },
                                onToggleRestock = { id ->
                                    restockItemIds = if (restockItemIds.contains(id)) {
                                        restockItemIds - id
                                    } else {
                                        restockItemIds + id
                                    }
                                },
                            )

                            // 🔴 En una columna, la pestaña de IMPORTE no vive aquí: abrir el teclado cruza `pieFijoEnUnaColumna`, y el campo no
                            // debe cambiar de padre. Aquí sólo queda el aviso del cobro con tarjeta. En dos columnas esta banda tiene todo el alto.
                            RefundTab.AMOUNT -> if (dosColumnas) importeYSuPropina()
                        }
                    }
                    // 🔴 Sin esta señal la banda MIENTE: una venta de 3 artículos enseñaba 2 y
                    // nada decía que hubiera más, así que el cajero reembolsa lo que alcanza a
                    // ver. Medido en la Sunmi el 2026-09-11.
                    // Si la banda no se desliza, su estado se queda con lo último que midió.
                    if (bandaSeDesliza && bandaScroll.canScrollForward) SenalDeHayMas(Modifier.align(Alignment.BottomCenter))
                }
            }

            // Cómo se devuelve: la propina por artículos, con qué, a quién y por qué.
            val comoSeDevuelve: @Composable () -> Unit = {
                // Por ARTÍCULOS: la propina que queda del cobro. Antes nunca se devolvía y el cajero
                // no tenía forma de hacerlo; ahora es una decisión visible junto al total.
                if (tab == RefundTab.ITEMS && propinaRestante > 0) {
                    FilaIncluirPropina(
                        paymentTipAmount = propinaQueCabe,
                        includeTip = propinaArticulosMarcada,
                        onIncludeTipChange = { propinaArticulosAMano = it },
                        porArticulos = true,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }

                if (opcionesDeDevolucion.isNotEmpty()) {
                    FilaDevolverCon(
                        opciones = opcionesDeDevolucion,
                        escogido = devolverCon,
                        leyenda = if (aSaldo && !aSaldoAFavor) "Se devuelve por el mismo medio con que se pagó." else leyendaDeDevolucion(transaction.method, devolverCon),
                        avisoDeAutorizacion = textoDeAutorizacionParaEfectivo(devolverCon, puedeDevolverEnEfectivo, codigoDeEncargadoActivo),
                        onEscoger = {
                            devolverConAMano = it
                            errorMsg = null // el rechazo era de la opción anterior
                        },
                        // Con la cadena bloqueada, el saldo a favor sigue siendo una salida (no necesita la terminal).
                        enabled = !submitting,
                        modifier = Modifier.padding(top = spacing.md),
                        anchoEnDosColumnas = if (dosColumnas) anchoDeColumna - spacing.xl * 2 else null,
                    )
                }

                // «Es por defecto» (spec §4): sólo con «Sólo saldo a favor» y un motivo que restringe; marcado, salen todas.
                if (ofrecerEsPorDefecto(soloSaldo, reason?.code)) {
                    FilaEsPorDefecto(
                        marcada = esPorDefecto,
                        onCambiar = { esPorDefecto = it; errorMsg = null },
                        enabled = !submitting,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }

                // «Saldo a favor para: Ana López · 55 1234 5678 [Cambiar]» (spec §4), con el teléfono obligatorio, como en el Cambio.
                if (aSaldoAFavor) {
                    FilaClienteDelSaldo(
                        cliente = clienteDelSaldo,
                        pideTelefono = pideTelefono,
                        guardando = guardandoTelefono,
                        mensaje = mensajeDelSaldo,
                        enabled = !submitting,
                        onCambiar = { escogiendoCliente = true },
                        todoSeDesliza = todoSeDesliza,
                        telefono = telefonoDelSaldo,
                        onTelefono = { telefonoDelSaldo = it },
                        onGuardarTelefono = saldo::guardarTelefono,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }

                // En una columna, el motivo y el importe con el que abre `cierre` se ven como un solo recuadro: van pegados, con el mismo
                // fondo y la raya entre los dos. En dos columnas, cada uno en el suyo.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.md)
                        .clip(if (dosColumnas) RoundedCornerShape(corners.lg) else RoundedCornerShape(topStart = corners.lg, topEnd = corners.lg))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(start = spacing.md, end = spacing.md, top = spacing.md, bottom = if (dosColumnas) spacing.md else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = "Motivo del reembolso",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        AvoqadoPillTextField(
                            // El campo se pinta sobre `surface`, igual que la hoja:
                            // dentro de este panel más oscuro se distingue solo, y el
                            // borde lo remata para que se lea como algo que se toca.
                            modifier = Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(50),
                            ),
                            value = reason?.label ?: "",
                            onValueChange = {},
                            readOnly = true,
                            placeholder = "Selecciona un motivo",
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(enabled = !submitting) { reasonMenuOpen = true },
                        )
                        DropdownMenu(
                            expanded = reasonMenuOpen,
                            onDismissRequest = { reasonMenuOpen = false },
                        ) {
                            REASONS.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        reason = option
                                        reasonMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Lo que cierra: el importe y su reparto, lo que falta, los avisos y los botones.
            val cierre: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (dosColumnas) Modifier.padding(top = spacing.md) else Modifier)
                        .clip(if (dosColumnas) RoundedCornerShape(corners.lg) else RoundedCornerShape(bottomStart = corners.lg, bottomEnd = corners.lg))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(start = spacing.md, end = spacing.md, top = if (dosColumnas) spacing.md else spacing.xs, bottom = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    if (!dosColumnas) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = spacing.xs),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Importe a reembolsar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatMoney(amountToRefund + propinaArticulos),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            // 🔴 EL REPARTO SE DICE JUNTO AL BOTÓN, no sólo en la casilla.
                            //
                            // La casilla «Incluir propina» vive más arriba y, con el teclado
                            // abierto en una pantalla corta, puede quedar fuera de vista en el
                            // momento de confirmar — lo señaló una auditoría adversarial (Codex
                            // gpt-6-astra, xhigh): que el cajero haya pasado por ella una vez no
                            // garantiza que la esté viendo al pulsar. Esta línea viaja pegada al
                            // importe y al botón, así que la decisión sobre el dinero del mesero
                            // se lee siempre, sin depender de dónde quedó el scroll.
                            if (tab == RefundTab.AMOUNT && paymentTipAmount > 0) {
                                Text(
                                    text = if (includeTip) {
                                        "Incluye la propina del mesero"
                                    } else {
                                        "Sin tocar la propina del mesero"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Lo mismo por ARTÍCULOS: el total ya incluye (o no) la propina, y se dice.
                            if (tab == RefundTab.ITEMS && propinaRestante > 0 && itemsAmount > 0) {
                                Text(
                                    text = if (propinaArticulosCents != null) {
                                        "Incluye ${formatMoney(propinaArticulos)} de propina"
                                    } else {
                                        "Sin tocar la propina del mesero"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            // Por artículos el tope es la VENTA restante (la propina va aparte, en la casilla).
                            text = if (tab == RefundTab.ITEMS) {
                                "Venta disponible ${formatMoney(topeActual)}"
                            } else {
                                "Disponible ${formatMoney(topeActual)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 🔴 Un botón apagado sin explicación se lee como una pantalla rota:
                // fue justo lo que pasó cuando el motivo quedaba fuera de cuadro.
                // Aquí se dice qué falta, en el orden en que hay que resolverlo.
                val faltaParaReembolsar = when {
                    bloqueo != null -> bloqueo
                    submitting -> null
                    faltaSaldo != null -> faltaSaldo
                    necesitaRed && (!conectado || !servidor) -> com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED
                    soloCompleto && enTerminal -> "Abre la devolución en la terminal."
                    amountToRefund <= 0.0 -> if (tab == RefundTab.ITEMS) {
                        "Selecciona al menos un artículo."
                    } else {
                        "Escribe el importe a reembolsar."
                    }
                    amountToRefund > topeActual + 0.001 ->
                        "El importe supera lo disponible (${formatMoney(topeActual)})."
                    reason == null -> "Elige el motivo del reembolso."
                    else -> null
                }
                faltaParaReembolsar?.let { falta ->
                    Text(
                        text = falta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                }

                // 🔴 El aviso completo del cobro con tarjeta ahora se desplaza, así
                // que puede quedar fuera de vista justo cuando se toca el botón. Este
                // renglón repite lo único que no se puede malentender: esto NO le
                // devuelve el dinero a la tarjeta.
                if (enTerminal) {
                    Text(
                        text = "Esto no devuelve el dinero a la tarjeta: eso se hace en la terminal.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = spacing.xxs),
                    )
                }

                if (avisoDeSoloRegistro(enTerminal, opcionesDeDevolucion.isNotEmpty(), transaction.method, transaction.refundToGiftCardBalance)) {
                    Text(
                        text = "Esto sólo lo registra: devuélvele el dinero por el mismo medio con que pagó.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = spacing.xxs),
                    )
                }

                // Pegado al botón, en las dos ramas. Arriba del pie, con todo deslizado o con el teclado abierto, nadie lo veía: el
                // botón sólo brincaba y la cajera volvía a tocar, justo cuando el aviso le pide no repetirlo a ciegas (SAMSUNG, 3-nov).
                errorDelEnvio()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm, bottom = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !submitting,
                    ) {
                        Text("Cancelar")
                    }

                    PrimaryButton(
                        text = textoDelBotonDeReembolso(amountToRefund + propinaArticulos),
                        onClick = {
                            if (!puedeEnviar() || soloCompleto && enTerminal) return@PrimaryButton
                            val chosenReason = reason ?: return@PrimaryButton
                            submitting = true
                            errorMsg = null

                            scope.launch {
                                // Primero la guarda: con un saldo a favor de esta venta sin confirmar o en vuelo, no sale ningún medio.
                                val motivo = antesDeReembolsar()
                                if (motivo != null) { submitting = false; errorMsg = motivo; return@launch }
                                // 🔴 Saldo a favor: se GUARDA en el aparato antes del POST y, si la respuesta no consta, «Confirmar estado»
                                // la reenvía con la misma llave (spec §7, Opción A). Nunca se encola sin red.
                                val enviar = enviarASaldo
                                if (aSaldoAFavor && enviar != null) {
                                    val llave = java.util.UUID.randomUUID().toString()
                                    val cuerpo = refundRepository.cuerpoDeReembolso(
                                        reason = chosenReason.code,
                                        amountCents = centavosDelImporte(parsedAmount).takeIf { tab == RefundTab.AMOUNT },
                                        items = itemsParaEnvio.takeIf { tab == RefundTab.ITEMS },
                                        restockItemIds = restockParaEnvio.takeIf { tab == RefundTab.ITEMS },
                                        tipRefundCents = if (tab == RefundTab.ITEMS) propinaArticulosCents else tipRefundCentsParaEnvio(paymentTipAmount, includeTip),
                                        refundMethod = refundMethodForSubmit(transaction.tenderSystemKind, DEVOLVER_CON_SALDO),
                                        customerId = clienteDelSaldo?.id,
                                        idempotencyKey = llave,
                                        defective = defectoParaEnvio(soloSaldo, chosenReason.code, esPorDefecto),
                                    )
                                    // Aquí sólo se ESPERA: si la hoja se cierra, se cancela la espera, no el envío.
                                    val envio = conexion.iniciar(true, ::vigente) { enviar(cuerpo, llave) }
                                    val desenlace = envio?.await()
                                    submitting = false
                                    if (!vigente()) return@launch
                                    when (desenlace) {
                                        null -> errorMsg = TextosCambio.SALDO_SIN_RED
                                        // El detalle ya muestra el abono y lo pendiente (I1): sólo se cierra la hoja.
                                        is DesenlaceDeSaldo.Hecho, is DesenlaceDeSaldo.Pendiente -> onDismiss()
                                        is DesenlaceDeSaldo.Rechazado -> {
                                            errorMsg = desenlace.mensaje
                                            if (desenlace.code == "STORE_CREDIT_CUSTOMER_PHONE_REQUIRED") saldo.pedirTelefono()
                                        }
                                        DesenlaceDeSaldo.OtroPendiente -> errorMsg = TextosCambio.REEMBOLSO_SIN_CONFIRMAR
                                        DesenlaceDeSaldo.GuardadoFallido -> errorMsg = TextosCambio.REEMBOLSO_GUARDADO_FALLIDO
                                    }
                                    return@launch
                                }
                                val result = conexion.iniciar(necesitaRed, ::vigente) { if (tab == RefundTab.ITEMS) {
                                    refundRepository.issueAssociatedRefund(
                                        paymentId = transaction.id,
                                        reason = chosenReason.code,
                                        items = itemsParaEnvio,
                                        restockItemIds = restockParaEnvio,
                                        // null ⇒ el campo no viaja (casilla desmarcada): sólo artículos.
                                        tipRefundCents = propinaArticulosCents,
                                        refundMethod = refundMethodForSubmit(transaction.tenderSystemKind, devolverCon.takeIf { opcionesDeDevolucion.isNotEmpty() }),
                                        defective = defectoParaEnvio(soloSaldo, chosenReason.code, esPorDefecto),
                                    )
                                } else {
                                    // When the user unchecks "Incluir propina" and
                                    // the payment had tip, force tipRefundCents=0
                                    // so the refund pulls 100% from the sale.
                                    // Otherwise (checkbox ON or no tip) omit the
                                    // override and let the backend apply its
                                    // default proportional split.
                                    refundRepository.issueAssociatedRefund(
                                        paymentId = transaction.id,
                                        reason = chosenReason.code,
                                        amountCents = centavosDelImporte(parsedAmount),
                                        tipRefundCents = tipRefundCentsParaEnvio(paymentTipAmount, includeTip),
                                        refundMethod = refundMethodForSubmit(transaction.tenderSystemKind, devolverCon.takeIf { opcionesDeDevolucion.isNotEmpty() }),
                                        defective = defectoParaEnvio(soloSaldo, chosenReason.code, esPorDefecto),
                                    )
                                } }

                                submitting = false
                                if (!vigente()) return@launch
                                if (result == null) { errorMsg = com.avoqado.pos.giftcards.domain.TextosDeTarjetas.SIN_RED; return@launch }
                                result.fold(
                                    onSuccess = {
                                        Log.d("💸", "Refund OK: $it")
                                        // 🔴 AQUÍ NO SE ESCRIBE EN EL CAJÓN. El servidor ya lo hace.
                                        //
                                        // Hasta el 2026-08-16 esta pantalla mandaba su propio PAY_OUT
                                        // después de cada reembolso, porque la ruta que usa
                                        // (`refund.dashboard.service.issueRefund`) no tocaba la caja y el
                                        // arqueo inventaba un SOBRANTE del tamaño de lo devuelto — medido
                                        // en hardware: $50,380 en pantalla contra $50,230 físicos.
                                        //
                                        // Ese lado se arregló en el SERVIDOR
                                        // (`shared/cashDrawerPosting.postCashRefundToDrawer`, `08a3fe6f`).
                                        // Volver a escribir desde aquí restaría DOS VECES y le cobraría al
                                        // cajero un faltante que nadie se robó: la llave del servidor
                                        // (`srv-refund:<refundId>`) no puede deduplicar contra un UUID
                                        // local, y un PAY_OUT que llega por `/cash-drawer/sync` es
                                        // indistinguible de un retiro a mano.
                                        //
                                        // El movimiento aparece en la tablet en cuanto se entra a Caja:
                                        // CADA entrada (`CashDrawerViewModel.alEntrar` → `syncFromApi()`)
                                        // baja los eventos que el servidor confirmó, y el cierre adopta la
                                        // caja que devuelve el servidor; de ahí sale el corte. La copia
                                        // local se ALIMENTA de lo confirmado; no se adelanta a ciegas.
                                        //
                                        // Vigilado por `RefundCashDrawerOwnershipTest`.
                                        onRefunded()
                                    },
                                    onFailure = { throwable ->
                                        errorMsg = formatRefundError(throwable, devolverCon.takeIf { opcionesDeDevolucion.isNotEmpty() })
                                    },
                                )
                            }
                        },
                        enabled = canSubmit,
                        isLoading = submitting,
                        modifier = Modifier.weight(1f),
                        fullWidth = true,
                    )
                }
            }

            if (dosColumnas) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = spacing.xl)
                            .padding(vertical = spacing.md),
                    ) {
                        queSeDevuelve()
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = spacing.xl),
                    ) {
                        // `comoSeDevuelve()` no cambia de lugar cuando `pieFijo` cambia: abrir el teclado desde el teléfono del saldo no debe
                        // recrear el campo, o perdería el foco y el teclado se cerraría. `cierre` no guarda nada: moverlo no cuesta.
                        Box(Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().verticalScroll(pieScroll)) {
                                comoSeDevuelve()
                                if (!pieFijo) cierre()
                            }
                            if (pieScroll.canScrollForward) SenalDeHayMas(Modifier.align(Alignment.BottomCenter))
                        }
                        if (pieFijo) cierre()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        // La tarjeta de regalo no trae pestañas, lista ni importe: va centrada y con tope, sin importar el ancho.
                        // `widthIn` ANTES de `fillMaxWidth`: al revés no topa nada, porque `fillMaxWidth` ya fijó el ancho.
                        .align(Alignment.TopCenter)
                        .then(if (soloCompleto) Modifier.widthIn(max = 640.dp) else Modifier)
                        .fillMaxWidth()
                        .padding(horizontal = spacing.xl)
                        // 🔴 Son los mismos nodos con pie fijo y sin él: abrir el teclado cruza `pieFijoEnUnaColumna`, y si el campo cambiara de
                        // padre perdería el foco, el teclado se cerraría, el alto subiría y se repetiría en bucle.
                        .verticalScroll(pieScroll)
                        // Con pie fijo, este mínimo es el alto que se reparte y aquí no hay recorrido. Es un mínimo y no un tope: si «Por importe» no
                        // cabe (4 medios, saldo a favor y teléfono en un celular), se desliza. Con un tope, lo que no cabía se recortaba sin forma de
                        // alcanzarlo. En Artículos, antes de que eso le quite su sitio a la lista, ya se desliza todo: ver `todoSeDesliza`.
                        .then(if (todoSeDesliza) Modifier else Modifier.heightIn(min = maxHeight))
                        .padding(top = spacing.md),
                ) {
                    queSeDevuelve()

                    // El pie. Con alto de sobra queda fijo abajo y la banda es la que cede. Con poco se desliza todo junto: a 360×640, el pie
                    // de artículos le dejaba 0 dp a la lista y 5 a Reembolsar.
                    //
                    // La casilla «Incluir propina» sigue AQUÍ y no en la banda de artículos: para
                    // llegar al botón hay que pasar por ella, porque el motivo —que `canSubmit`
                    // exige— vive debajo. Deslizable no es lo mismo que escondido.
                    // Este contenedor existe siempre: sólo mide, para `loDemasEnArticulos`. El `Spacer` no entra en él, porque necesita que el
                    // alto se reparta entre él y lo demás; un contenedor con peso recortaría lo que no cabe.
                    importeYSuPropina()
                    // En «Por importe» lo que sobra va aquí: lo que se escribe arriba y lo demás abajo, como en Artículos. Existe siempre, para que
                    // nada de abajo cambie de lugar al cruzar el corte. La tarjeta de regalo sigue arriba, con el blanco abajo.
                    Spacer(if (tab == RefundTab.AMOUNT && !todoSeDesliza && !soloCompleto) Modifier.weight(1f) else Modifier)
                    Column(
                        Modifier.fillMaxWidth().onSizeChanged {
                            altoDelPie = it.height
                            if (tab == RefundTab.ITEMS) altoDelPieEnArticulos = it.height
                        },
                    ) {
                        comoSeDevuelve()
                        cierre()
                    }
                }
                if (pieScroll.canScrollForward) SenalDeHayMas(Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (escogiendoCliente) {
        // «Cambiar» reusa la búsqueda y el alta de clientes de siempre (alta con teléfono obligatorio), como el Cambio.
        val clientes: com.avoqado.pos.customers.presentation.CustomersViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        AvoqadoModalBottomSheet(onDismissRequest = { altaDesde = null; escogiendoCliente = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), conXPropia = true, dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                val busqueda = altaDesde
                if (busqueda != null) com.avoqado.pos.customers.presentation.CreateCustomerView(viewModel = clientes,
                    initialPhone = busqueda.takeIf { it.any(Char::isDigit) }, initialName = busqueda.takeUnless { it.any(Char::isDigit) },
                    onCustomerCreated = { saldo.escoger(it); altaDesde = null; escogiendoCliente = false },
                    onBack = { altaDesde = null }, alta = altaDelFormulario, onAlta = { altaDelFormulario = it },
                    requirePhone = true)
                else com.avoqado.pos.customers.presentation.CustomersView(viewModel = clientes,
                    onCustomerSelected = { saldo.escoger(it); escogiendoCliente = false },
                    onDismiss = { escogiendoCliente = false }, onCreateCustomer = { altaDelFormulario = null; altaDesde = it },
                    canCreateCustomer = saldo.puedeCrearCliente)
            }
        }
    }
}

@Composable
private fun ItemsBody(
    transaction: Transaction,
    selectedIds: Set<String>,
    refundQty: Map<String, Int>,
    restockIds: Set<String>,
    maxRefundable: Double,
    onToggle: (String) -> Unit,
    onQtyChange: (String, Int) -> Unit,
    onToggleRestock: (String) -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    val corners = AvoqadoTheme.cornerRadius

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = "Selecciona los artículos a reembolsar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // 🔴 El conteo es la única señal HONESTA de que hay más abajo: el degradado
            // del borde es transparente→blanco sobre una lista que ya es blanca, así que
            // no se ve. Sin esto, una venta de 3 artículos enseña 2 y el cajero reembolsa
            // lo que alcanza a ver (medido en la Sunmi, 2026-09-11).
            Text(
                text = "${transaction.items.size} artículo${if (transaction.items.size == 1) "" else "s"} · " +
                    "Venta máxima reembolsable: ${formatMoney(maxRefundable)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corners.md))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corners.md)),
        ) {
            transaction.items.forEachIndexed { index, item ->
                val id = item.id ?: return@forEachIndexed
                // Fully-refunded lines are shown as "Reembolsado" and can't be
                // selected again. Partially-refunded lines allow the stepper to
                // go up to `remainingQty`, not the original `quantity`.
                val remaining = if (item.remainingQty > 0) {
                    item.remainingQty
                } else {
                    // Backward compat for older backends that don't send remainingQty.
                    (item.quantity - item.refundedQty).coerceAtLeast(0)
                }
                val fullyRefunded = item.fullyRefunded || remaining == 0
                val partiallyRefunded = item.partiallyRefunded

                val selected = selectedIds.contains(id)
                val rawQty = refundQty[id] ?: remaining
                val qty = rawQty.coerceIn(1, maxOf(1, remaining))
                val effectiveAmount = RefundAmountCalculator.calculateSelectedAmount(
                    items = listOf(item),
                    selectedIds = setOf(id),
                    refundQtyByItem = mapOf(id to qty),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (fullyRefunded) {
                                Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            } else {
                                Modifier.clickable { onToggle(id) }
                            },
                        )
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { if (!fullyRefunded) onToggle(id) },
                        enabled = !fullyRefunded,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.quantity > 1) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(horizontal = spacing.sm, vertical = spacing.xxs),
                                ) {
                                    Text(
                                        text = "${item.quantity}x",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(modifier = Modifier.width(spacing.sm))
                            }
                            Text(
                                text = item.productName.ifBlank { "Importe personalizado" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            if (fullyRefunded) {
                                Spacer(modifier = Modifier.width(spacing.sm))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = spacing.sm, vertical = spacing.xxs),
                                ) {
                                    Text(
                                        text = "Reembolsado",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        if (partiallyRefunded) {
                            Text(
                                text = "${item.refundedQty} de ${item.quantity} ya se reembolsó",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (item.trackInventory && !fullyRefunded) {
                            Text(
                                text = "Con seguimiento de inventario",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (selected && remaining > 1 && !fullyRefunded) {
                        QtyStepper(
                            value = qty,
                            max = remaining,
                            onChange = { delta -> onQtyChange(id, delta) },
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                    }
                    val displayAmount = if (fullyRefunded) item.refundedAmount.takeIf { it > 0 } ?: item.chargedLineTotal else effectiveAmount
                    Text(
                        text = formatMoney(displayAmount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (fullyRefunded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
                // Sin separador tras el último: pegado al borde del recuadro se
                // veía como una raya suelta.
                if (index < transaction.items.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }

        val restockableItems = transaction.items.filter { item ->
            item.id != null && selectedIds.contains(item.id) && item.trackInventory
        }
        if (restockableItems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(corners.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corners.md))
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "Reabastecer existencias",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Marca solo los artículos que sí regresaron al inventario.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                restockableItems.forEach { item ->
                    val id = item.id ?: return@forEach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleRestock(id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = restockIds.contains(id),
                            onCheckedChange = { onToggleRestock(id) },
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.productName.ifBlank { "Artículo" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Cantidad seleccionada: ${refundQty[id] ?: item.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QtyStepper(
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        StepperButton(
            icon = Icons.Filled.Remove,
            enabled = value > 1,
            onClick = { onChange(-1) },
            contentDescription = "Menos",
        )
        Text(
            text = "$value/$max",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        StepperButton(
            icon = Icons.Filled.Add,
            enabled = value < max,
            onClick = { onChange(+1) },
            contentDescription = "Más",
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            },
        )
    }
}

@Composable
private fun AmountBody(
    amountStr: String,
    onAmountChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AvoqadoTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = "Selecciona el importe a reembolsar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // 🔴 Componente de la casa, no `OutlinedTextField` crudo: su etiqueta
        // flotante "Importe" se montaba sobre el borde y se veía CORTADA, con
        // dos contornos encimados (medido en la D3, 2026-08-17). La etiqueta es
        // el título de arriba; el tope ya lo dice «Disponible» junto al total, así
        // que no se repite aquí (cada renglón de más le quitaba alto al aviso).
        AvoqadoPillTextField(
            value = amountStr,
            onValueChange = onAmountChange,
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            leading = {
                Text(
                    text = "$",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (active) 1.dp else 0.dp,
                color = if (active) MaterialTheme.colorScheme.outline else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = spacing.md, horizontal = spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(spacing.xxs))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
        }
    }
}

internal fun formatRefundError(throwable: Throwable, refundMethod: String?): String {
    val apiError = throwable as? RefundApiException
    return when (apiError?.statusCode) {
        403 -> textoDeSinPermisoParaReembolsar(refundMethod)
        400, 422 -> apiError.message.ifBlank { "Revisa los datos del reembolso" }
        in 500..599 -> "No se pudo emitir el reembolso. Intenta de nuevo."
        else -> textoDeFalloDeRed(throwable) ?: throwable.message ?: "Error al emitir reembolso"
    }
}

/**
 * D7: el texto crudo de la excepción llegaba a la pantalla («Failed to connect to…»). Un reembolso común no lleva llave, y
 * ningún fallo de red prueba que no se aplicó: OkHttp reintenta, así que el primer POST pudo aplicarse y perder su respuesta,
 * y lo último que falla es la reconexión (Codex r3). Por eso nunca se dice «no se hizo» ni se invita a repetirlo a ciegas.
 */
private fun textoDeFalloDeRed(error: Throwable): String? = when {
    error is java.net.UnknownHostException || error is java.net.ConnectException || error is java.net.NoRouteToHostException ->
        TextosCambio.REEMBOLSO_SIN_RED
    // `servidor:` quiere decir que sí hay red y que contestó (ver D3): ahí no se dice «Sin conexión».
    error is com.avoqado.pos.core.data.network.SesionSinRenovar && !error.motivo.startsWith("servidor:") -> TextosCambio.REEMBOLSO_SIN_RED
    error is java.io.IOException -> TextosCambio.REEMBOLSO_SIN_RESPUESTA
    else -> null
}

/**
 * D7, el mismo síntoma al abrir la devolución en la terminal (Codex r4): el rechazo del servidor se enseña tal cual, y un
 * fallo de red nunca se enseña crudo. La solicitud pudo haber llegado, así que tampoco se invita a reenviarla a ciegas.
 */
internal fun textoDeFalloAlAbrirEnTerminal(error: Throwable): String =
    if (error is java.io.IOException) {
        TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RESPUESTA
    } else {
        error.message ?: "No se pudo abrir la devolución en la terminal."
    }

/** «Devolver con»: una pastilla por medio posible y la leyenda de lo que pasa con el dinero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilaDevolverCon(
    opciones: List<OpcionDeDevolucion>,
    escogido: String?,
    leyenda: String,
    avisoDeAutorizacion: String?,
    onEscoger: (String?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** Lo que mide la fila en dos columnas, de ~380 dp para arriba; `null` en una. */
    anchoEnDosColumnas: Dp? = null,
) {
    val spacing = AvoqadoTheme.spacing
    // 🔴 En dos columnas, repartir el ancho a ciegas partía «Transferencia» en dos: ahí van por renglón las pastillas que quepan con su
    // etiqueta entera, medidas como escogidas (negritas y palomita) para que escoger una no reacomode la fila. En una columna, como
    // siempre: todas juntas, porque en un celular cada renglón de más le quita alto a la lista.
    val medidor = rememberTextMeasurer()
    val estiloEscogida = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val densidad = LocalDensity.current
    val porRenglon = if (anchoEnDosColumnas == null) {
        opciones.size
    } else {
        val etiqueta = with(densidad) { opciones.maxOf { medidor.measure(it.label, estiloEscogida).size.width }.toDp() }
        val pastilla = etiqueta + 16.dp + spacing.xxs + spacing.sm * 2 // la palomita y el relleno de `TabPill`
        ((anchoEnDosColumnas + spacing.sm) / (pastilla + spacing.sm)).toInt().coerceIn(1, opciones.size)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = "Devolver con",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = porRenglon,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            opciones.forEach { op ->
                TabPill(
                    label = op.label,
                    active = op.refundMethod == escogido,
                    modifier = Modifier.weight(1f).semantics { selected = op.refundMethod == escogido },
                    // En gris con su razón (el saldo a favor sin red o sin las llaves): apagado se ve y se explica.
                    enabled = enabled && op.disabledReason == null,
                    onClick = { onEscoger(op.refundMethod) },
                )
            }
        }
        opciones.mapNotNull { it.disabledReason }.distinct().forEach { razon ->
            Text(
                text = razon,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = leyenda,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (avisoDeAutorizacion != null) {
            Text(
                text = avisoDeAutorizacion,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * La casilla «Incluir propina», extraída para poder vivir en el pie (una columna) o a la izquierda (dos).
 * Marcada ⇒ el servidor reparte proporcional; desmarcada ⇒ sólo se devuelve la venta.
 */
@Composable
private fun FilaIncluirPropina(
    paymentTipAmount: Double,
    includeTip: Boolean,
    onIncludeTipChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Reembolso por ARTÍCULOS: otros subtítulos (el importe de la derecha es la propina que queda). */
    porArticulos: Boolean = false,
) {
    val spacing = AvoqadoTheme.spacing
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { onIncludeTipChange(!includeTip) }
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Checkbox(
                checked = includeTip,
                onCheckedChange = { onIncludeTipChange(it) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Incluir propina en el reembolso",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when {
                        porArticulos && includeTip -> "Se devuelve también la propina del cobro."
                        porArticulos -> "Solo se reembolsan los artículos; la propina del mesero queda intacta."
                        includeTip -> "Se reparte proporcional entre venta y propina del pago original."
                        else -> "Solo se reembolsa el producto; la propina del mesero queda intacta."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatMoney(paymentTipAmount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «Es por defecto» con «Sólo saldo a favor» (spec §4, §6): marcada, se devuelve como se pagó. */
@Composable
private fun FilaEsPorDefecto(marcada: Boolean, onCambiar: (Boolean) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val spacing = AvoqadoTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .clickable(enabled = enabled) { onCambiar(!marcada) }
            .padding(horizontal = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Checkbox(checked = marcada, onCheckedChange = onCambiar, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = TextosCambio.ES_POR_DEFECTO, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = TextosCambio.SOLO_SALDO_EXCEPCIONES,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «Saldo a favor para: {nombre · teléfono}» con «Cambiar» y, si no tiene teléfono, el campo para guardarlo (como el Cambio). */
@Composable
private fun FilaClienteDelSaldo(
    cliente: ExchangeStoreCreditCustomer?,
    guardando: Boolean,
    pideTelefono: Boolean,
    mensaje: String?,
    todoSeDesliza: Boolean,
    telefono: String,
    enabled: Boolean,
    onCambiar: () -> Unit,
    onTelefono: (String) -> Unit,
    onGuardarTelefono: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AvoqadoTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ExchangeSettlement.storeCreditCustomerLabel(cliente), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCambiar, enabled = enabled && !guardando) { Text(TextosCambio.CAMBIAR) }
        }
        if (pideTelefono) {
            AvoqadoPillTextField(value = telefono, onValueChange = onTelefono, placeholder = TextosCambio.TELEFONO,
                modifier = campoQueNoSePierde(todoSeDesliza), enabled = enabled && !guardando, keyboardType = KeyboardType.Phone)
            PrimaryButton(TextosCambio.GUARDAR_TELEFONO, { onGuardarTelefono(telefono) },
                enabled = enabled && !guardando && telefono.isNotBlank(), fullWidth = true)
        }
        mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * El teléfono se queda a la vista al abrir el teclado. Al pasar a deslizarse todo junto, la lista toma todo su alto y empuja el campo
 * hacia abajo. El deslizamiento sólo sigue al campo con foco si al encogerse ya lo ve recortado, y con un solo salto de alto todavía lo ve
 * donde estaba.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun campoQueNoSePierde(todoSeDesliza: Boolean): Modifier {
    val aLaVista = remember { BringIntoViewRequester() }
    var conFoco by remember { mutableStateOf(false) }
    LaunchedEffect(todoSeDesliza) {
        if (conFoco) {
            withFrameNanos { } // Hasta que la lista ya esté en su lugar nuevo: antes, el campo todavía se ve donde estaba.
            aLaVista.bringIntoView()
        }
    }
    return Modifier.bringIntoViewRequester(aLaVista).onFocusChanged { conFoco = it.isFocused }
}

/** «Hay más abajo»: el degradado al borde de lo que se desliza. No recibe toques: el dedo pasa a lo de atrás. */
@Composable
private fun SenalDeHayMas(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AvoqadoTheme.spacing.xl)
            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
            .testTag("reembolso-hay-mas"),
    )
}

internal fun controlDeTerminalDeDevolucion(necesitaRed: Boolean, conectado: Boolean, servidor: Boolean): Boolean = !necesitaRed || conectado && servidor
