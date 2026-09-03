package com.avoqado.pos.payment.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.NormalCheckoutItem
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.kds.data.KDSOrderItemRequest
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.PaymentContext
import com.avoqado.pos.payment.data.model.PaymentErrorSource
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentItem
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.model.totalACobrarCents
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.printing.NoStationsFallback
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.ComboPrintLines
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaymentCompletion(
    val splitType: String,
    val remainingBalanceCents: Int,
    val paidItemIds: Set<String> = emptySet(),
    /**
     * La orden que quedó cobrada, cuando la hubo (una venta rápida sin orden la
     * deja en null). El upsell la necesita para atar su métrica a la venta REAL:
     * el ingreso del reporte sale de las líneas cobradas, nunca de lo que reportó
     * el POS.
     */
    val orderId: String? = null,
)

const val LOCAL_PRINTER_UNAVAILABLE = "__LOCAL_PRINTER_UNAVAILABLE__"

@HiltViewModel
class PaymentFlowViewModel @Inject constructor(
    private val tableSession: com.avoqado.pos.tables.data.TableSession,
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val orderRepository: OrderRepository,
    private val cashPaymentRepository: CashPaymentRepository,
    private val tenderTypeRepository: com.avoqado.pos.payment.data.TenderTypeRepository,
    private val terminalPaymentService: TerminalPaymentService,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val paymentSyncService: PaymentSyncService,
    private val cashDrawerRepository: CashDrawerRepository,
    private val kdsRepository: KDSRepository,
    private val kdsOrderBus: KDSOrderBus,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    private val comandaDispatcher: ComandaDispatcher,
    private val customerDisplay: com.avoqado.pos.customerdisplay.CustomerDisplayState,
    private val areaTicketRepository: AreaTicketRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentFlowState>(PaymentFlowState.Loading)
    val state: StateFlow<PaymentFlowState> = _state.asStateFlow()
    private var completionConsumed = false

    /**
     * Propina y calificación las captura el CLIENTE en su pantalla: requiere
     * pantalla montada Y que el negocio lo haya activado en Ajustes (tener el
     * hardware no garantiza que el cliente esté enfrente).
     */
    val customerDisplayActive: StateFlow<Boolean> = customerDisplay.customerCapturesInput

    private val _onlineTerminals = MutableStateFlow<List<OnlineTerminal>>(emptyList())
    val onlineTerminals: StateFlow<List<OnlineTerminal>> = _onlineTerminals.asStateFlow()

    /// Terminal availability, probed UP-FRONT at flow start (the audit's
    /// flagship late-validation fix). Before, availability was only checked
    /// AFTER tip+rating when the user picked CARD — a venue with zero online
    /// terminals walked the whole flow only to dead-end at "No hay terminales".
    enum class TerminalAvailability { CHECKING, AVAILABLE, NONE, ERROR }
    private val _terminalAvailability = MutableStateFlow(TerminalAvailability.CHECKING)
    val terminalAvailability: StateFlow<TerminalAvailability> = _terminalAvailability.asStateFlow()

    private var cartState: CartState? = null
    private var selectedMethod: PaymentMethod? = null
    private var currentRating: Int? = null
    private var currentTipCents: Int = 0
    private var createdOrderId: String? = null
    private var createdOrderNumber: String? = null  // folio real del backend

    /// Re-entrancy guard: a fast double-tap on a cash preset or a terminal row
    /// used to enter processCashPayment/confirmPayment TWICE — both saw
    /// createdOrderId == null and created two orders + recorded two payments.
    /// Set synchronously at entry, cleared centrally when the flow reaches a
    /// terminal state (Success/Error) — see the collector in startFlowGuard().
    private var isProcessingPayment = false

    /// One idempotency key per payment SESSION (not per attempt): a retry after
    /// a network-error-but-server-recorded response reuses the key so the
    /// backend can dedupe instead of recording a SECOND payment. Cleared on
    /// Success (same collector as the processing flag) and on cancel/reset.
    private var paymentIdempotencyKey: String? = null

    private fun sessionIdempotencyKey(): String =
        paymentIdempotencyKey ?: java.util.UUID.randomUUID().toString().also { paymentIdempotencyKey = it }

    /**
     * 🔴 Un 4xx al crear la orden NO significa "no pasó nada".
     *
     * Con promociones el server crea la orden PRIMERO y aplica el combo después:
     * si el combo no se puede aplicar, anula la orden y libera el `externalId`
     * — pero esa limpieza es best-effort. Si falla, la llave queda tomada por
     * una orden CANCELADA y vacía, y el reintento con la MISMA llave entra por
     * el atajo de idempotencia: 201 con una orden de $0 y el combo regalado.
     *
     * Por eso un rechazo de negocio estrena llave. Es seguro: si la orden no se
     * creó, no hubo cobro que deduplicar.
     *
     * 🔴 Y SÓLO un rechazo de negocio (4xx de nuestra API). Un fallo de RED
     * —timeout, socket cerrado— conserva la llave a propósito: ahí el intento
     * lento SÍ pudo aterrizar, y estrenarla crearía una SEGUNDA orden en vez de
     * deduplicar contra la primera. Es la regla 2.4 de offline-first.
     */
    private fun estrenarLlaveTrasRechazoDeOrden(error: Throwable) {
        val esRechazoDeNegocio = error is OrderRepository.ServerException && error.code in 400..499
        if (esRechazoDeNegocio) paymentIdempotencyKey = null
    }

    /// Invalidates in-flight terminal sends: cancel() bumps it, and a late
    /// result from a cancelled send is ignored instead of overwriting the
    /// screen, marking Success and PRINTING a receipt for a cancelled payment.
    private var paymentGeneration = 0

    init {
        // Refresca el catálogo de tipos de pago al entrar al flujo de cobro. Es
        // cache-first: si falla, conserva la última lista buena — nunca deja al
        // cajero sin poder registrar la venta que acaba de entregar.
        viewModelScope.launch { tenderTypeRepository.refresh() }
        // La pantalla del cliente llama a los MISMOS métodos que el cajero
        // (submitRating/submitTip): la calificación y la propina no tienen dos
        // caminos, solo dos superficies de entrada.
        customerDisplay.onRatingPicked = { submitRating(it) }
        customerDisplay.onTipPicked = { submitTip(it) }
        // El cliente teclea su WhatsApp/correo en SU pantalla (teclado propio) y
        // dispara los MISMOS envíos que el cajero. Solo aplica en pantallas táctiles
        // (la detección de hardware ya apaga la interacción donde el dedo no llega).
        customerDisplay.onWhatsAppSubmit = { sendReceiptWhatsApp(it) }
        customerDisplay.onEmailSubmit = { sendReceiptEmail(it) }
        // OJO: el espejo del estado de envío (enviando/enviado/error) vive en un
        // init MÁS ABAJO, después de declarar los StateFlow que colecta. Aquí
        // arriba crashea: viewModelScope usa Main.immediate y correría el collect
        // de inmediato, cuando _whatsAppSending/etc aún son null.

        viewModelScope.launch {
            _state.collect { st ->
                // Undetermined también libera el guard: si no, la pantalla honesta se queda
                // sin poder re-consultar ni cobrar de nuevo (el cajero atrapado sin salida).
                if (st is PaymentFlowState.Success ||
                    st is PaymentFlowState.Error ||
                    st is PaymentFlowState.Undetermined
                ) {
                    isProcessingPayment = false
                }
                if (st is PaymentFlowState.Success) {
                    paymentIdempotencyKey = null
                }
                mirrorToCustomerDisplay(st)
            }
        }
    }

    /** Los mensajes de éxito de envío contienen "enviado"; los de error, no. */
    private fun receiptSendFrom(msg: String) =
        if (msg.contains("enviado", ignoreCase = true))
            com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Sent
        else
            com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Error

    /**
     * Espejo del flujo de pago a la pantalla del cliente. Traduce estado →
     * contenido; NO decide nada ni calcula montos (llegan ya resueltos).
     */
    private fun mirrorToCustomerDisplay(st: PaymentFlowState) {
        customerDisplay.show(
            when (st) {
                is PaymentFlowState.CollectingRating ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Rating(st.amount)

                is PaymentFlowState.CollectingTip ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Tip(
                        amountCents = st.amount,
                        suggestions = settings.tipSuggestions,
                        selectedTipCents = null,
                    )

                is PaymentFlowState.Confirming ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Charging(
                        totalCents = st.amount + st.tip,
                        message = "Confirmando tu pago…",
                    )

                is PaymentFlowState.SentToTerminal ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Charging(
                        totalCents = st.totalAmount,
                        message = "Sigue las instrucciones en la terminal",
                    )

                is PaymentFlowState.Processing ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Charging(
                        totalCents = st.totalAmount,
                        message = "Procesando tu pago…",
                    )

                is PaymentFlowState.Success ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Done(
                        totalCents = st.totalAmount,
                        // La URL del backend si vino; si no, se arma contra el DASHBOARD.
                        // Sin llave de recibo no se dibuja QR: mejor nada que un
                        // código que no lleva a ningún lado.
                        receiptUrl = com.avoqado.pos.core.data.network.resolveReceiptUrl(
                            st.receiptUrl,
                            st.receiptAccessKey,
                        ),
                    )

                // Le toca al cajero: el cliente ve su total, sin nada tocable.
                // Antes esto era `return` (no actualizar) y la pantalla del
                // cliente se quedaba con la propina puesta y sus botones VIVOS
                // después de que el cajero ya había avanzado.
                // Turno del cajero: el cliente ve el DESGLOSE tipo recibo
                // (productos + subtotal + descuento + propina + total), armado
                // desde el carrito + propina actuales, no un total pelón.
                is PaymentFlowState.SelectingPaymentMethod,
                is PaymentFlowState.CollectingCashAmount,
                is PaymentFlowState.SelectingTerminal,
                ->
                    checkoutBreakdown()

                // Sin venta que mostrar (cargando, error): de vuelta a la marca.
                // Undetermined es asunto del CAJERO —él revisa la terminal—: al cliente no se
                // le pone ni un éxito que no consta ni un error que quizá no ocurrió.
                is PaymentFlowState.Loading,
                is PaymentFlowState.Error,
                is PaymentFlowState.Undetermined,
                ->
                    com.avoqado.pos.customerdisplay.CustomerContent.Idle
            },
        )
    }

    /**
     * Desglose tipo recibo para la pantalla del cliente durante el cobro. Espeja
     * el carrito (productos, subtotal, descuento, impuestos) y le suma la propina
     * ya elegida. Sin productos (monto personalizado) se cae a "solo total".
     * Cero fuente de verdad: los montos vienen ya calculados de CartState.
     */
    private fun checkoutBreakdown(): com.avoqado.pos.customerdisplay.CustomerContent.Total {
        val cart = cartState
        val tip = currentTipCents
        return if (cart != null && cart.items.isNotEmpty()) {
            com.avoqado.pos.customerdisplay.CustomerContent.Total(
                totalCents = cart.totalCents + tip,
                items = cart.items,
                subtotalCents = cart.subtotalCents,
                discountCents = cart.discountCents,
                taxCents = cart.taxCents,
                tipCents = tip,
            )
        } else {
            com.avoqado.pos.customerdisplay.CustomerContent.Total(totalCents = currentBaseAmount() + tip)
        }
    }
    private var selectedTerminalId: String? = null
    /**
     * `requestId` del cobro con tarjeta cuyo desenlace no consta, **según esta pantalla**.
     * Mientras no sea null, `retry()` tiene prohibido cobrar: primero re-consulta.
     *
     * Vive en `SavedStateHandle` para sobrevivir a la muerte del proceso. La copia
     * autoritativa y durable es la del servicio (disco); ésta sirve para distinguir
     * "el cobro sin resolver es MÍO" de "es de una venta anterior" — sin esa distinción,
     * re-consultar un cobro viejo podría marcar como pagada una venta que nadie cobró.
     */
    private var undeterminedRequestId: String?
        get() = savedStateHandle[KEY_UNDETERMINED_REQUEST]
        set(value) { savedStateHandle[KEY_UNDETERMINED_REQUEST] = value }

    private var lastPaymentId: String? = null
    private var lastReceiptAccessKey: String? = null
    /** URL del recibo tal como la mandó el backend (dashboard). Ver `resolveReceiptUrl`. */
    private var lastReceiptUrl: String? = null
    private var lastAreaDeliveryCode: String? = null
    private var lastCashTenderedCents: Int? = null
    private var splitSelectedItemIds: Set<String> = emptySet()
    private var splitNumberOfParts: Int? = null
    private var splitCustomAmountCents: Int? = null
    private var splitBaseAmountOverride: Int? = null

    /**
     * 🔴 DINERO. Total (centavos, propina incluida) de la orden que el server
     * acaba de crear, cuando difiere del estimado del carrito. Manda sobre el
     * estimado: la orden es la deuda real y el server tolera 1 centavo antes de
     * dejarla PARTIAL. null = se cobra el carrito, como siempre.
     *
     * Se llena SÓLO en ventas con promoción y pago completo (ver
     * `totalACobrarCents`), y se limpia al arrancar cada venta.
     */
    private var serverTotalOverrideCents: Int? = null

    /**
     * 🔴 DINERO. Total (centavos, propina incluida) que el server le puso a la
     * orden de ESTA venta, cuando la venta llevaba promoción.
     *
     * Se guarda SIEMPRE que la orden se crea — también en pago DIVIDIDO, donde
     * no manda sobre lo que se cobra ahora (ese importe lo eligió el cajero)
     * pero sí sobre **lo que queda por cobrar**: ver `buildCompletion`.
     */
    private var serverOrderTotalCents: Int? = null

    /**
     * 🔴 DINERO. Saldo (centavos) que el SERVER dice que le queda a la orden
     * después del pago que acaba de entrar. Manda sobre la aritmética local del
     * carrito en [buildCompletion]: es el único que conoce los pagos que este
     * dispositivo no vio (otra caja, un link, un abono anterior).
     *
     * null = el server no lo mandó (versión vieja, camino de tarjeta, cobro
     * encolado sin red) y se usa el cálculo de siempre. Se limpia al arrancar
     * cada venta: arrastrarlo dejaría un saldo fantasma en la siguiente.
     */
    private var serverRemainingBalanceCents: Int? = null

    // WhatsApp receipt sending state
    private val _whatsAppSending = MutableStateFlow(false)
    val whatsAppSending: StateFlow<Boolean> = _whatsAppSending.asStateFlow()

    private val _whatsAppResult = MutableStateFlow<String?>(null)
    val whatsAppResult: StateFlow<String?> = _whatsAppResult.asStateFlow()

    fun clearWhatsAppResult() {
        _whatsAppResult.value = null
    }

    // Email receipt sending state
    private val _emailSending = MutableStateFlow(false)
    val emailSending: StateFlow<Boolean> = _emailSending.asStateFlow()

    private val _emailResult = MutableStateFlow<String?>(null)
    val emailResult: StateFlow<String?> = _emailResult.asStateFlow()

    fun clearEmailResult() {
        _emailResult.value = null
    }

    // Espejo del estado de envío del recibo hacia la pantalla del cliente
    // (enviando/enviado/error). En un init APARTE porque viewModelScope usa
    // Main.immediate y colecta de inmediato: debe correr DESPUÉS de declarar
    // los StateFlow de arriba, o sería null (crash de orden de init).
    init {
        viewModelScope.launch {
            _whatsAppSending.collect { if (it) customerDisplay.setReceiptSend(com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Sending) }
        }
        viewModelScope.launch {
            _emailSending.collect { if (it) customerDisplay.setReceiptSend(com.avoqado.pos.customerdisplay.CustomerDisplayState.ReceiptSend.Sending) }
        }
        viewModelScope.launch {
            _whatsAppResult.collect { r -> r?.let { customerDisplay.setReceiptSend(receiptSendFrom(it)) } }
        }
        viewModelScope.launch {
            _emailResult.collect { r -> r?.let { customerDisplay.setReceiptSend(receiptSendFrom(it)) } }
        }
    }

    // Manual receipt reprint state
    private var lastReceipt: ReceiptData? = null

    private val _printSending = MutableStateFlow(false)
    val printSending: StateFlow<Boolean> = _printSending.asStateFlow()

    private val _printResult = MutableStateFlow<String?>(null)
    val printResult: StateFlow<String?> = _printResult.asStateFlow()

    /**
     * Se resolvió un cobro pendiente que venía de OTRA venta: el flujo debe CERRARSE y
     * devolver al cajero a donde estaba, con este mensaje.
     *
     * 🔴 No basta con avisar y seguir: el cajero vino a resolver un pendiente, no a cobrar.
     * Antes se le soltaba en el primer paso de la venta nueva mientras un toast verde se
     * desvanecía encima — o sea que el desenlace del cobro viejo (¡dinero!) pasaba volando
     * mientras la pantalla ya le pedía otra cosa. El mensaje lo pinta quien queda en
     * pantalla, no la pantalla que se va.
     */
    private val _previousChargeResolved = MutableStateFlow<String?>(null)
    val previousChargeResolved: StateFlow<String?> = _previousChargeResolved.asStateFlow()

    fun clearPreviousChargeResolved() { _previousChargeResolved.value = null }

    /**
     * La cancelación de la orden fue RECHAZADA por el server (típicamente 409: ya está pagada).
     * Antes esto sólo se logueaba mientras la app navegaba afuera, así que el cajero se quedaba
     * creyendo que canceló algo que sigue vivo — y encima el cobro podía aterrizar sobre esa
     * orden. Ahora se ve.
     */
    private val _cancelFailure = MutableStateFlow<String?>(null)
    val cancelFailure: StateFlow<String?> = _cancelFailure.asStateFlow()

    fun clearCancelFailure() { _cancelFailure.value = null }

    /**
     * La comanda automática post-cobro NO salió (o salió a medias) en alguna estación.
     *
     * 🔴 El cobro nunca se frena por una impresora — pero callar el fallo deja al barista
     * sin enterarse del pedido: Testarudo (2026-08-31) cobró cafés durante días sin que
     * saliera la comanda de barra y el único rastro era una línea de logcat. El aviso
     * nombra la estación para que el cajero sepa QUÉ impresora revisar.
     */
    private val _comandaWarning = MutableStateFlow<String?>(null)
    val comandaWarning: StateFlow<String?> = _comandaWarning.asStateFlow()

    fun clearComandaWarning() { _comandaWarning.value = null }

    private val _canPrintOnTerminal = MutableStateFlow(false)
    val canPrintOnTerminal: StateFlow<Boolean> = _canPrintOnTerminal.asStateFlow()

    fun clearPrintResult() {
        _printResult.value = null
    }

    // Customer attachment state
    private val _customerAttachSending = MutableStateFlow(false)
    val customerAttachSending: StateFlow<Boolean> = _customerAttachSending.asStateFlow()

    private val _customerAttachResult = MutableStateFlow<String?>(null)
    val customerAttachResult: StateFlow<String?> = _customerAttachResult.asStateFlow()

    /**
     * Cliente de ESTA venta. Se siembra con el que ya venía elegido en el
     * carrito y lo sobrescribe el alta desde la pantalla de recibo.
     *
     * 🔴 Antes vivía sólo como estado local de la pantalla de recibo, arrancando
     * en null: el cajero elegía "Juan Pérez" en el carrito, cobraba, y al
     * terminar la pantalla le ofrecía "Agregar cliente" como si no hubiera
     * nadie. No era sólo la etiqueta — la orden se creaba SIN `customerId`, así
     * que la venta quedaba anónima en el server: sin historial de compra, sin
     * lealtad y sin cliente que facturar.
     */
    private val _attachedCustomerName = MutableStateFlow<String?>(null)
    val attachedCustomerName: StateFlow<String?> = _attachedCustomerName.asStateFlow()

    /** Id del cliente que viaja en `POST /orders`. */
    private var attachedCustomerId: String? = null

    fun clearCustomerAttachResult() {
        _customerAttachResult.value = null
    }

    fun attachCustomerToCurrentPayment(customerId: String, customerName: String) {
        val paymentId = lastPaymentId
        val amountCents = currentBaseAmount()
        val tipCents = currentTipCents

        viewModelScope.launch {
            _customerAttachSending.value = true
            _customerAttachResult.value = null
            val result = if (!paymentId.isNullOrBlank()) {
                orderRepository.attachCustomerToPayment(paymentId, customerId)
            } else {
                orderRepository.attachCustomerToLatestPayment(
                    customerId = customerId,
                    amountCents = amountCents,
                    tipCents = tipCents,
                    staffId = selectedStaffId(),
                )
            }
            result
                .fold(
                    onSuccess = {
                        attachedCustomerId = customerId
                        _attachedCustomerName.value = customerName
                        _customerAttachResult.value = "Cliente agregado: $customerName"
                        // El aviso ya se resolvió: la venta SÍ tiene cliente ahora. Dejarlo
                        // puesto es estado que miente — y era la única divergencia con iOS
                        // en el campo que este cambio introdujo.
                        (_state.value as? PaymentFlowState.Success)?.let { exito ->
                            _state.value = exito.copy(customerLinkWarning = null)
                        }
                    },
                    onFailure = { error ->
                        _customerAttachResult.value = error.message ?: "No se pudo agregar cliente"
                    },
                )
            _customerAttachSending.value = false
        }
    }

    fun reprintReceipt() {
        val receipt = lastReceipt ?: buildReceiptSnapshot()?.also { lastReceipt = it }
        if (receipt == null) {
            if (!selectedTerminalId.isNullOrBlank()) {
                _printResult.value = LOCAL_PRINTER_UNAVAILABLE
            } else {
                _printResult.value = "No hay recibo disponible para reimprimir"
            }
            return
        }
        viewModelScope.launch {
            _printSending.value = true
            _printResult.value = null
            try {
                // El motivo importa: "pon papel" y "configura una impresora" son
                // acciones distintas, y decir "Recibo impreso" cuando no salió
                // nada deja al cajero sin ticket creyendo que sí se imprimió.
                _printResult.value = when (val outcome = printerService.manualPrintReceipt(receipt)) {
                    is PrinterService.PrintOutcome.Printed -> "Recibo impreso"
                    is PrinterService.PrintOutcome.OutOfPaper -> "La impresora no tiene papel"
                    is PrinterService.PrintOutcome.Failed ->
                        if (!selectedTerminalId.isNullOrBlank()) LOCAL_PRINTER_UNAVAILABLE
                        else "No se pudo imprimir: ${outcome.reason}"
                    is PrinterService.PrintOutcome.NoPrinter ->
                        if (!selectedTerminalId.isNullOrBlank()) LOCAL_PRINTER_UNAVAILABLE
                        else "No hay impresora de recibos configurada"
                }
            } catch (e: Exception) {
                _printResult.value = "Error al imprimir: ${e.message ?: "desconocido"}"
            } finally {
                _printSending.value = false
            }
        }
    }

    fun printReceiptOnTerminal() {
        val receipt = lastReceipt ?: buildReceiptSnapshot()?.also { lastReceipt = it }
        val terminalId = selectedTerminalId
        if (receipt == null) {
            _printResult.value = "No hay recibo disponible para imprimir"
            return
        }
        if (terminalId.isNullOrBlank()) {
            _printResult.value = "No hay TPV seleccionada para imprimir"
            return
        }

        viewModelScope.launch {
            _printSending.value = true
            _printResult.value = null
            terminalPaymentService.printReceiptOnTerminal(
                terminalId = terminalId,
                receipt = receipt,
                paymentId = lastPaymentId,
                receiptAccessKey = lastReceiptAccessKey,
            ).fold(
                onSuccess = {
                    _printResult.value = "Recibo impreso en TPV"
                },
                onFailure = { error ->
                    _printResult.value = error.message ?: "No se pudo imprimir en TPV"
                },
            )
            _printSending.value = false
        }
    }

    // Split payment support
    private val _splitType = MutableStateFlow("FULLPAYMENT")
    val splitType: StateFlow<String> = _splitType.asStateFlow()

    fun setSplitType(type: String) {
        _splitType.value = type
        splitSelectedItemIds = emptySet()
        splitNumberOfParts = null
        splitCustomAmountCents = null
        splitBaseAmountOverride = null
    }

    fun setSplitConfig(
        type: String,
        selectedItemIds: List<String> = emptyList(),
        numberOfParts: Int? = null,
        customAmountCents: Int? = null,
    ) {
        _splitType.value = type
        splitSelectedItemIds = selectedItemIds.toSet()
        splitNumberOfParts = numberOfParts
        splitCustomAmountCents = customAmountCents
    }

    val settings: TpvSettings get() = tpvSettingsRepository.getCurrentSettings()

    private fun selectedStaffId(): String {
        return cartState?.selectedStaffId?.takeIf { it.isNotBlank() }
            ?: secureStorage.userId.orEmpty()
    }

    /**
     * @param customerId Cliente que el cajero ya eligió en el carrito. Viaja en
     *   `POST /orders` para que la venta quede ligada a él (historial, lealtad,
     *   facturación). Va como parámetro de ESTA función —y no en un setter
     *   aparte— porque aquí mismo se limpia el estado de la venta anterior: un
     *   setter externo se podía llamar antes y quedar borrado en silencio.
     * @param resumeOrderId 🔴 DINERO. La orden que esta venta ya tiene abierta
     *   porque una parte se cobró antes (split de MOSTRADOR, sin mesa). Va como
     *   parámetro por lo mismo que `customerId`: aquí se borra el estado de la
     *   venta anterior, así que un setter externo se perdería en silencio — y
     *   perderlo es exactamente el bug que esto cierra. Lo resuelve el carrito,
     *   que es el dueño de la venta (`CartViewModel.resolvePendingSplitOrderForCharge`).
     */
    fun startPaymentFlow(
        cart: CartState,
        customerId: String? = null,
        customerName: String? = null,
        resumeOrderId: String? = null,
    ) {
        cartState = cart
        completionConsumed = false
        // El aviso de comanda es de la venta ANTERIOR: arrastrarlo culparía a una
        // impresora que en esta venta puede estar perfectamente bien.
        _comandaWarning.value = null
        splitBaseAmountOverride = resolveSplitBaseAmount(cart)
        // El total autoritativo es de la venta ANTERIOR: arrastrarlo cobraría
        // esta venta al precio de la pasada.
        serverTotalOverrideCents = null
        serverOrderTotalCents = null
        serverRemainingBalanceCents = null
        val amount = currentBaseAmount()

        // Reset transient state from any previous session.
        isProcessingPayment = false
        paymentIdempotencyKey = null
        selectedMethod = null
        currentRating = null
        currentTipCents = 0
        createdOrderId = null
        createdOrderNumber = null

        // 🔴 DINERO — MOSTRADOR: partes 2..N de un split.
        //
        // Al quedar saldo, el checkout sustituye el carrito por una línea
        // "Saldo pendiente" (o le quita los artículos ya cobrados). Sin esto, la
        // parte 2 arrancaba sin orden: efectivo caía al cobro rápido y tarjeta
        // creaba un pago suelto —o, con productos reales, una SEGUNDA orden con
        // las líneas duplicadas—. La venta quedaba partida en dos, la orden
        // original PARTIAL para siempre y **el stock nunca se descontaba** (sólo
        // se descuenta al llegar a PAID).
        //
        // Sembrarlo aquí basta: las dos ramas de cobro ya prefieren la orden que
        // existe (`recordCashPaymentForOrder` en efectivo, `orderId` a la
        // terminal en tarjeta), y el importe sale de `currentBaseAmount()`, que
        // ya refleja el resto.
        //
        // Quién garantiza que NO se filtre a la venta siguiente: el carrito. El
        // vínculo muere con él (`CartViewModel.clearCart`), y se revalida contra
        // el carrito real antes de cada cobro.
        resumeOrderId?.takeIf { it.isNotBlank() }?.let { createdOrderId = it }

        // TABLE_SERVICE (PRO) seam — a PAYING table session means this payment
        // settles the table's EXISTING order: preset its id so (a) the card path
        // sends orderId to the terminal (the TPV records against the order and
        // marks it PAID, its native table flow) and (b) the cash path records
        // against it via recordCashPaymentForOrder — and NO new order is ever
        // created for this charge. With no session active this is a no-op and
        // every retail/quick flow is byte-identical.
        tableSession.current()
            ?.takeIf { it.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING }
            ?.let { createdOrderId = it.orderId }
        selectedTerminalId = null
        _canPrintOnTerminal.value = false
        lastPaymentId = null
        lastReceiptAccessKey = null
        lastReceiptUrl = null
        lastAreaDeliveryCode = null
        lastCashTenderedCents = null
        _onlineTerminals.value = emptyList()

        // Clear receipt sending state from previous payment
        _whatsAppResult.value = null
        _whatsAppSending.value = false
        _emailResult.value = null
        _emailSending.value = false
        _printResult.value = null
        _printSending.value = false
        _customerAttachResult.value = null
        _customerAttachSending.value = false
        attachedCustomerId = customerId?.takeIf { it.isNotBlank() }
        _attachedCustomerName.value = customerName?.takeIf { it.isNotBlank() }
        lastReceipt = null

        Log.d("💰", "Starting payment flow - amount: $amount")

        // Probe terminal availability up-front so the CARD option can disable
        // itself before we collect tip/rating.
        probeTerminalAvailability()

        // 🔴 Antes de dejar cobrar nada: ¿quedó un cobro con tarjeta sin resolver? La llave
        // vive en disco, así que sobrevive al cambio de pestaña y a la muerte del proceso —
        // que es justo cuando la pantalla de advertencia se evaporaba y el siguiente "Cobrar"
        // arrancaba limpio. Se resuelve ESE antes de ofrecer uno nuevo.
        val pending = terminalPaymentService.unresolvedRequestId
        if (pending != null) {
            val fromPreviousSale = pending != undeterminedRequestId
            undeterminedRequestId = pending
            _state.value = PaymentFlowState.Undetermined(
                totalAmount = amount,
                message = if (fromPreviousSale) PREVIOUS_CHARGE_MESSAGE else CardChargeDecision.UNDETERMINED_MESSAGE,
                fromPreviousSale = fromPreviousSale,
            )
            return
        }

        enterInitialState(amount)
    }

    /** Primer paso del cobro según la configuración de la TPV (calificación → propina → método). */
    private fun enterInitialState(amount: Int) {
        if (settings.showReviewScreen) {
            _state.value = PaymentFlowState.CollectingRating(amount)
        } else if (settings.showTipScreen) {
            _state.value = PaymentFlowState.CollectingTip(amount, null)
        } else {
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        }
    }

    fun submitRating(rating: Int?) {
        // 🔴 Con doble pantalla hay DOS dedos sobre el mismo flujo. Si el
        // cliente toca su pantalla un instante después de que el cajero avanzó,
        // esto reescribía el paso ya cerrado. Solo se acepta si seguimos en él.
        if (_state.value !is PaymentFlowState.CollectingRating) return
        currentRating = rating
        val amount = currentBaseAmount()

        if (settings.showTipScreen) {
            _state.value = PaymentFlowState.CollectingTip(amount, rating)
        } else {
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        }
    }

    fun submitTip(tipCents: Int) {
        // 🔴 MONEY: un toque tardío del cliente NO puede cambiar la propina con
        // el cajero ya en método de pago / efectivo / terminal — cambiaría el
        // total por debajo de una pantalla que ya mostraba otro.
        if (_state.value !is PaymentFlowState.CollectingTip) return
        currentTipCents = tipCents
        val amount = currentBaseAmount()

        _state.value = PaymentFlowState.SelectingPaymentMethod(amount + tipCents)
    }

    fun currentTipPercentageBaseCents(): Int {
        return computeTipPercentageBaseAmount(currentBaseAmount())
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        selectedMethod = method
        val baseAmount = currentBaseAmount()
        val total = baseAmount + currentTipCents

        when (method) {
            PaymentMethod.CASH -> {
                _state.value = PaymentFlowState.CollectingCashAmount(total)
            }
            PaymentMethod.CARD -> {
                // Fetch online terminals and show terminal selection
                _state.value = PaymentFlowState.SelectingTerminal(total)
                fetchTerminals()
            }
        }
    }

    /** Cash preset tapped directly from payment method screen (iOS-style direct confirm) */
    /**
     * Cobro registrado a mano: el dinero NO pasó por Avoqado (terminal ajena,
     * transferencia). null = efectivo. Se limpia con la sesión de cobro para
     * que el siguiente cliente no herede el método del anterior.
     */
    private var manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null

    /**
     * Tipo de pago del catálogo del negocio elegido para ESTE cobro ("Uber Eats").
     * Se limpia igual que `manualMethod` para que el siguiente cliente no lo herede.
     */
    private var selectedTender: com.avoqado.pos.payment.domain.TenderTypeOption? = null

    /**
     * El mesero declara que ya le pagaron por otro medio. No hay teclado ni
     * cambio: el monto es exactamente el total, así que se cobra de una.
     */
    /** Etiqueta del cobro manual para la pantalla de éxito y el recibo. */
    val manualMethodLabel: String? get() = selectedTender?.name ?: manualMethod?.label

    /**
     * Catálogo del negocio para la hoja de "¿cómo pagó el cliente?".
     *
     * 🔴 Es un StateFlow, NO un getter. Con un getter, Compose no tiene cómo enterarse
     * de que el refresh terminó: al abrir el cobro la caché estaba vacía, la hoja se
     * componía sin tipos y la respuesta llegaba a un valor que nadie volvía a leer —
     * o sea que **la primera venta después de abrir la app nunca veía los tipos del
     * negocio**. Medido en el D3 (2026-08-17). Si lo vuelves a un getter, vuelve el bug.
     */
    val tenderTypes: StateFlow<List<com.avoqado.pos.payment.domain.TenderTypeOption>> =
        tenderTypeRepository.tenderTypes

    fun confirmManualChoice(choice: com.avoqado.pos.payment.domain.ManualPaymentChoice) {
        when (choice) {
            is com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed -> {
                manualMethod = choice.method
                selectedTender = null
            }
            is com.avoqado.pos.payment.domain.ManualPaymentChoice.Tender -> {
                manualMethod = null
                selectedTender = choice.option
                // El server RECHAZA tip>0 en un tipo sin propina; no le mandamos
                // una venta que sabemos que va a rebotar.
                if (!choice.option.captureTip) currentTipCents = 0
            }
        }
        selectedMethod = PaymentMethod.CASH
        val total = currentBaseAmount() + currentTipCents
        lastCashTenderedCents = total
        processCashPayment(total)
    }

    fun confirmCashPreset(tenderedCents: Int) {
        manualMethod = null
        selectedTender = null
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    /** Custom cash amount confirmed from bottom sheet keypad */
    fun confirmCashCustom(tenderedCents: Int) {
        manualMethod = null
        selectedTender = null
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    /**
     * Sonda de disponibilidad: pregunta si hay terminales conectadas para poder
     * deshabilitar "Cobrar con terminal" ANTES de que el cajero lo intente.
     *
     * @param background por defecto SÍ, porque el arranque del cobro la dispara
     * solo — nadie la pidió, y su fracaso no impide nada (falla en abierto: la
     * opción se queda habilitada y el error real sale al enviar). Sólo el enlace
     * "Reintentar", que es un toque explícito sobre esta misma pantalla, la corre
     * en primer plano: ahí el "no" tiene que verse.
     */
    fun probeTerminalAvailability(background: Boolean = true) {
        _terminalAvailability.value = TerminalAvailability.CHECKING
        viewModelScope.launch {
            when (val result = terminalPaymentService.fetchOnlineTerminals(background = background)) {
                is TerminalListResult.Success -> {
                    _onlineTerminals.value = result.terminals
                    _terminalAvailability.value =
                        if (result.terminals.isEmpty()) TerminalAvailability.NONE
                        else TerminalAvailability.AVAILABLE
                }
                is TerminalListResult.Error -> {
                    // Fail OPEN: can't verify (network) shouldn't block a working
                    // terminal — keep the option enabled and let the send-step
                    // surface any real error.
                    _terminalAvailability.value = TerminalAvailability.ERROR
                }
            }
        }
    }

    /**
     * Trae la lista para ELEGIR terminal. Nace del toque "Cobrar con terminal" y
     * su fracaso impide exactamente lo que el cajero pidió, así que NO va marcada
     * como de fondo: aquí el 403 debe verse.
     */
    private fun fetchTerminals() {
        viewModelScope.launch {
            when (val result = terminalPaymentService.fetchOnlineTerminals()) {
                is TerminalListResult.Success -> {
                    _onlineTerminals.value = result.terminals
                    _terminalAvailability.value =
                        if (result.terminals.isEmpty()) TerminalAvailability.NONE
                        else TerminalAvailability.AVAILABLE
                    if (result.terminals.isEmpty()) {
                        _state.value = PaymentFlowState.Error(
                            message = "No hay terminales conectadas",
                            source = PaymentErrorSource.TERMINAL,
                        )
                    }
                }
                is TerminalListResult.Error -> {
                    _state.value = PaymentFlowState.Error(
                        message = result.message,
                        source = PaymentErrorSource.TERMINAL,
                    )
                }
            }
        }
    }

    fun selectTerminalAndPay(terminalId: String) {
        selectedTerminalId = terminalId
        _canPrintOnTerminal.value = true
        confirmPayment()
    }

    fun confirmPayment() {
        if (isProcessingPayment) return
        val cart = cartState ?: return
        isProcessingPayment = true
        val total = currentBaseAmount() + currentTipCents
        _state.value = PaymentFlowState.Processing(total)

        viewModelScope.launch {
            try {
                if (areaTicketRepository.session.current() != null) {
                    if (!materializeAreaTicketCheckout(cart)) return@launch
                    processPaymentMethod(total)
                    return@launch
                }
                val hasRealProducts = hasProductItems(cart)

                if (hasRealProducts) {
                    if (createdOrderId == null) {
                        // Create order only once per payment session.
                        val orderRequest = buildOrderRequest(cart)
                        val orderExternalId = sessionIdempotencyKey()
                        val orderResult = orderRepository.createOrder(
                            orderRequest,
                            staffId = selectedStaffId(),
                            customerId = attachedCustomerId,
                            orderType = cart.orderType,
                            externalId = orderExternalId,
                            // 🔴 El premio viaja EN LA CREACIÓN. El total que devuelva
                            // esta llamada es el que se cobra (`adoptarTotalDelServer`),
                            // así que el descuento tiene que existir antes de que
                            // vuelva. Con una segunda llamada quedaría una ventana con
                            // la cuenta al total completo.
                            stampRewardId = cart.pendingStampRewardId,
                        )

                        orderResult.fold(
                            onSuccess = { response ->
                                val orderId = response.data?.id
                                if (orderId.isNullOrBlank()) {
                                    _state.value = PaymentFlowState.Error(
                                        message = "No se pudo obtener la orden creada",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                    return@fold
                                }
                                createdOrderId = orderId
                                createdOrderNumber = response.data?.orderNumber
                                // La orden ya existe y todavía no se ha tomado
                                // dinero: se cobra lo que dice el server, no el
                                // estimado del carrito. Ver `totalACobrarCents`.
                                processPaymentMethod(adoptarTotalDelServer(orderRequest, response, total))
                            },
                            onFailure = { error ->
                                // For CASH payments: queue offline if network/server error
                                if (selectedMethod == PaymentMethod.CASH) {
                                    val isQueueable = OrderRepository.isQueueableError(error) ||
                                        (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))

                                    if (isQueueable) {
                                        cashPaymentRepository.queueCashPayment(
                                            orderRequest = orderRequest,
                                            staffId = selectedStaffId(),
                                            customerId = attachedCustomerId,
                                            idempotencyKey = sessionIdempotencyKey(),
                                            cashTenderedCents = null,
                                            changeCents = null,
                                            rating = currentRating,
                                            orderId = null,
                                            orderExternalId = orderExternalId,
                                            manualMethod = manualMethod,
                                            tenderType = selectedTender,
                                        )
                                        // Record cash sale in drawer (defensive: same fix as B4)
                                        recordCashSale(total, null)
                                        _state.value = PaymentFlowState.Success(
                                            totalAmount = total,
                                            method = PaymentMethod.CASH,
                                            changeAmount = 0,
                                            isQueued = true,
                                        )
                                        createKDSOrderAndPrint(PaymentMethod.CASH)
                                    } else {
                                        estrenarLlaveTrasRechazoDeOrden(error)
                                        _state.value = PaymentFlowState.Error(
                                            message = error.message ?: "Error al crear la orden",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                    }
                                } else {
                                    estrenarLlaveTrasRechazoDeOrden(error)
                                    _state.value = PaymentFlowState.Error(
                                        message = error.message ?: "Error al crear la orden",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                }
                            },
                        )
                    } else {
                        // Ya hay orden: se reusa en vez de crear otra. Entran por
                        // aquí el reintento de tarjeta y —desde el fix del split de
                        // mostrador— la parte 2 de una venta por artículos, donde el
                        // carrito conserva productos reales y crear una segunda orden
                        // duplicaría esas líneas en base.
                        processPaymentMethod(total)
                    }
                } else {
                    // Custom amount only — use Fast Payment endpoint
                    Log.d("💰", "Custom amount payment (fast) - total: $total")
                    processPaymentMethod(total)
                }
            } catch (e: Exception) {
                Log.e("💰", "Payment error: ${e.message}")
                _state.value = PaymentFlowState.Error(
                    message = e.message ?: "Error inesperado",
                    source = PaymentErrorSource.UNKNOWN,
                )
            }
        }
    }

    private suspend fun processPaymentMethod(total: Int) {
        when (selectedMethod) {
            PaymentMethod.CARD -> {
                val terminalId = selectedTerminalId
                if (terminalId == null) {
                    _state.value = PaymentFlowState.Error(
                        message = "No se seleccionó una terminal",
                        source = PaymentErrorSource.TERMINAL,
                    )
                    return
                }

                _state.value = PaymentFlowState.SentToTerminal(total)
                val generation = paymentGeneration
                val terminalResult = terminalPaymentService.sendPaymentToTerminal(
                    terminalId = terminalId,
                    amountCents = currentBaseAmount(),
                    tipCents = currentTipCents,
                    rating = currentRating,
                    orderId = createdOrderId,
                    processedByStaffId = selectedStaffId(),
                    // 🔴 EL CLIENTE DE LA VENTA. Es el valor CONGELADO al abrir el cobro
                    // (`clienteDelCobro` de CheckoutScreen → startPaymentFlow), nunca el
                    // flujo vivo del carrito. En el cobro rápido con tarjeta no hay orden
                    // (`createdOrderId` va nulo), así que sin esta línea la venta `FAST-*`
                    // nacía anónima aunque el cajero sí lo hubiera elegido. Espejo exacto
                    // del camino de EFECTIVO (`recordFastCashPayment`).
                    customerId = attachedCustomerId,
                )
                if (generation != paymentGeneration) {
                    // El cajero canceló mientras el envío seguía en vuelo (hasta 330 s): no se
                    // marca Success/Error ni se imprime sobre una pantalla de la que ya se fue.
                    // Pero el DINERO no se descarta con la navegación — ver handleStaleCardResult.
                    handleStaleCardResult(terminalResult)
                    return
                }
                when (terminalResult) {
                    is TerminalPaymentResult.Success -> applyCardCharged(terminalResult, total)
                    is TerminalPaymentResult.Error -> {
                        // Consta que no se cobró: reintentar vuelve a ser seguro.
                        undeterminedRequestId = null
                        _state.value = PaymentFlowState.Error(
                            message = terminalResult.message,
                            source = PaymentErrorSource.TERMINAL,
                        )
                    }
                    // 🔴 No se sabe si la tarjeta se cobró. Ni Success ni Error: su propia
                    // pantalla, sin Reintentar a ciegas. Ver PaymentFlowState.Undetermined.
                    is TerminalPaymentResult.Undetermined -> {
                        undeterminedRequestId = terminalResult.requestId
                        _state.value = PaymentFlowState.Undetermined(
                            totalAmount = total,
                            message = terminalResult.message,
                        )
                    }
                }
            }
            PaymentMethod.CASH -> {
                // Record cash payment on the order
                val orderId = createdOrderId
                if (orderId != null) {
                    val subtotal = total - currentTipCents
                    val payResult = orderRepository.recordCashPayment(
                        orderId = orderId,
                        amount = subtotal,
                        staffId = selectedStaffId(),
                        tip = currentTipCents,
                        splitType = _splitType.value,
                        idempotencyKey = sessionIdempotencyKey(),
                        manualMethod = manualMethod,
                                            tenderType = selectedTender,
                    )
                    payResult.fold(
                        onSuccess = { result ->
                            lastPaymentId = result.paymentId
                            // 🔴 El saldo que queda lo dice el server. Ver `buildCompletion`.
                            adoptarSaldoDelServer(result.remainingBalanceCents, result.orderPaymentStatus)
                            // accessKey del recibo → QR en pantalla del cliente y recibo
                            // impreso, igual que en tarjeta. Se setea ANTES de imprimir y
                            // de armar el estado Success para que el QR ya esté disponible.
                            result.receiptAccessKey?.let { lastReceiptAccessKey = it }
                            result.receiptUrl?.let { lastReceiptUrl = it }
                            finishAreaTicketPayment()
                            recordCashSale(total, orderId)
                            _state.value = PaymentFlowState.Success(
                                totalAmount = total,
                                method = PaymentMethod.CASH,
                                paymentId = result.paymentId,
                                receiptAccessKey = result.receiptAccessKey,
                                receiptUrl = result.receiptUrl,
                                inventoryWarningMessage = result.inventoryWarningMessage,
                            )
                            createKDSOrderAndPrint(PaymentMethod.CASH)
                        },
                        onFailure = { error ->
                            Log.w("💵", "Cash payment recording failed: ${error.message}")
                            // FIX B2: Don't silently show Success on payment failure.
                            // Queue for offline sync if error is transient, otherwise show Error.
                            val isQueueable = OrderRepository.isQueueableError(error) ||
                                (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                            val cart = cartState
                            if (isQueueable && cart != null && areaTicketRepository.session.current() == null) {
                                cashPaymentRepository.queueCashPayment(
                                    orderRequest = buildOrderRequest(cart),
                                    staffId = selectedStaffId(),
                                    customerId = attachedCustomerId,
                                    idempotencyKey = sessionIdempotencyKey(),
                                    cashTenderedCents = null,
                                    changeCents = null,
                                    rating = currentRating,
                                    orderId = orderId,
                                    manualMethod = manualMethod,
                                            tenderType = selectedTender,
                                )
                                recordCashSale(total, orderId)
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = total,
                                    method = PaymentMethod.CASH,
                                    isQueued = true,
                                )
                                createKDSOrderAndPrint(PaymentMethod.CASH)
                            } else {
                                _state.value = PaymentFlowState.Error(
                                    message = "No se pudo registrar el pago: ${error.message ?: "error desconocido"}",
                                    source = PaymentErrorSource.SERVER,
                                )
                            }
                        },
                    )
                } else {
                    // No orderId — fast payment path. Let caller handle.
                    recordCashSale(total)
                    _state.value = PaymentFlowState.Success(
                        totalAmount = total,
                        method = PaymentMethod.CASH,
                        paymentId = lastPaymentId,
                    )
                    createKDSOrderAndPrint(PaymentMethod.CASH)
                }
            }
            null -> {
                _state.value = PaymentFlowState.Error(
                    message = "Método de pago no seleccionado",
                    source = PaymentErrorSource.UNKNOWN,
                )
            }
        }
    }

    fun processCashPayment(cashReceivedCents: Int) {
        if (isProcessingPayment) return
        isProcessingPayment = true
        lastCashTenderedCents = cashReceivedCents
        val total = currentBaseAmount() + currentTipCents

        when (val result = cashPaymentRepository.processCashPayment(total, cashReceivedCents)) {
            is CashPaymentResult.Success -> {
                viewModelScope.launch {
                    _state.value = PaymentFlowState.Processing(total)

                    val cart = cartState
                    if (cart != null && areaTicketRepository.session.current() != null) {
                        if (!materializeAreaTicketCheckout(cart)) return@launch
                        recordCashPaymentForOrder(
                            orderId = createdOrderId!!,
                            total = total,
                            cashReceivedCents = cashReceivedCents,
                            changeCents = result.changeCents,
                            orderRequest = buildOrderRequest(cart),
                        )
                        return@launch
                    }
                    val hasRealProducts = cart?.let(::hasProductItems) ?: false

                    // TABLE_SERVICE: a preset createdOrderId (PAYING table session)
                    // routes cash through the order-based path even though the
                    // seeded cart only carries the "Cuenta Mesa N" amount line.
                    if (hasRealProducts || (!createdOrderId.isNullOrBlank() && cart != null)) {
                        // Order-based cash payment: create order once, then reuse it across retries.
                        val orderRequest = buildOrderRequest(cart)
                        val existingOrderId = createdOrderId
                        if (!existingOrderId.isNullOrBlank()) {
                            recordCashPaymentForOrder(
                                orderId = existingOrderId,
                                total = total,
                                cashReceivedCents = cashReceivedCents,
                                changeCents = result.changeCents,
                                orderRequest = orderRequest,
                            )
                        } else {
                            val orderExternalId = sessionIdempotencyKey()
                            val orderResult = orderRepository.createOrder(
                                orderRequest,
                                staffId = selectedStaffId(),
                                customerId = attachedCustomerId,
                                orderType = cart.orderType,
                                externalId = orderExternalId,
                            )
                            orderResult.fold(
                                onSuccess = { orderResponse ->
                                    val orderId = orderResponse.data?.id
                                    if (orderId.isNullOrBlank()) {
                                        _state.value = PaymentFlowState.Error(
                                            message = "No se pudo obtener la orden creada",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                        return@fold
                                    }
                                    createdOrderId = orderId
                                    // El efectivo ya está en la mano, así que el
                                    // total del server sólo se adopta si el
                                    // dinero recibido alcanza; si no, se cobra el
                                    // estimado (como hasta hoy) en vez de dejar
                                    // al cajero pidiendo centavos de vuelta.
                                    val totalFinal = adoptarTotalDelServer(orderRequest, orderResponse, total)
                                        .takeIf { it <= cashReceivedCents }
                                        ?: total.also { serverTotalOverrideCents = null }
                                    recordCashPaymentForOrder(
                                        orderId = orderId,
                                        total = totalFinal,
                                        cashReceivedCents = cashReceivedCents,
                                        changeCents = if (totalFinal == total) {
                                            result.changeCents
                                        } else {
                                            (cashReceivedCents - totalFinal).coerceAtLeast(0)
                                        },
                                        orderRequest = orderRequest,
                                    )
                                },
                                onFailure = { error ->
                                    val isQueueable = OrderRepository.isQueueableError(error) ||
                                        (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                                    if (isQueueable) {
                                        cashPaymentRepository.queueCashPayment(
                                            orderRequest = orderRequest,
                                            staffId = selectedStaffId(),
                                            customerId = attachedCustomerId,
                                            idempotencyKey = sessionIdempotencyKey(),
                                            cashTenderedCents = cashReceivedCents,
                                            changeCents = result.changeCents,
                                            rating = currentRating,
                                            orderId = null,
                                            orderExternalId = orderExternalId,
                                            manualMethod = manualMethod,
                                            tenderType = selectedTender,
                                        )
                                        // FIX B4: Record cash sale in drawer even on offline queue
                                        recordCashSale(total, null)
                                        _state.value = PaymentFlowState.Success(
                                            totalAmount = total,
                                            method = PaymentMethod.CASH,
                                            changeAmount = result.changeCents,
                                            isQueued = true,
                                        )
                                        createKDSOrderAndPrint(PaymentMethod.CASH, result.changeCents)
                                    } else {
                                        // Non-queueable error (validation, auth, etc.)
                                        estrenarLlaveTrasRechazoDeOrden(error)
                                        _state.value = PaymentFlowState.Error(
                                            message = error.message ?: "Error al crear la orden",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        // Fast payment (custom amount, no products)
                        // Send amount WITHOUT tip, tip separately
                        val subtotal = total - currentTipCents
                        val fastResult = orderRepository.recordFastCashPayment(
                            amount = subtotal,
                            staffId = selectedStaffId(),
                            tip = currentTipCents,
                            splitType = _splitType.value,
                            idempotencyKey = sessionIdempotencyKey(),
                            manualMethod = manualMethod,
                                            tenderType = selectedTender,
                            // 🔴 EL CLIENTE DE LA VENTA. Es el valor CONGELADO al abrir el
                            // cobro (`clienteDelCobro` de CheckoutScreen → startPaymentFlow),
                            // nunca el flujo vivo del carrito. Sin esta línea la venta rápida
                            // nacía anónima aunque el cajero sí lo hubiera elegido.
                            customerId = attachedCustomerId,
                        )
                        fastResult.fold(
                            onSuccess = { fast ->
                                lastPaymentId = fast.paymentId
                                // Recibo → QR en pantalla del cliente y recibo impreso.
                                fast.receiptAccessKey?.let { lastReceiptAccessKey = it }
                                fast.receiptUrl?.let { lastReceiptUrl = it }
                                // 🔴 La pantalla NO puede mentir: si el server no vinculó al
                                // cliente, el recibo no puede seguir enseñándolo puesto. Se
                                // suelta para que vuelva a ofrecer "Agregar cliente" — que es
                                // justo la reasignación, sin volver a cobrar nada.
                                if (fast.customerLinkWarning != null) {
                                    attachedCustomerId = null
                                    _attachedCustomerName.value = null
                                }
                                recordCashSale(total, null)
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = total,
                                    method = PaymentMethod.CASH,
                                    changeAmount = result.changeCents,
                                    paymentId = fast.paymentId,
                                    receiptAccessKey = fast.receiptAccessKey,
                                    receiptUrl = fast.receiptUrl,
                                    // El server no pudo vincular al cliente: se AVISA (ámbar)
                                    // y el cobro queda como está. Jamás se vuelve a cobrar.
                                    customerLinkWarning = fast.customerLinkWarning,
                                )
                            },
                            onFailure = { error ->
                                val isQueueable = OrderRepository.isQueueableError(error) ||
                                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                                if (isQueueable) {
                                    // FIX B1: Actually queue the fast-cash payment for offline sync
                                    // FIX B4: Also record cash sale in drawer
                                    if (cart != null) {
                                        cashPaymentRepository.queueCashPayment(
                                            orderRequest = buildOrderRequest(cart),
                                            staffId = selectedStaffId(),
                                            customerId = attachedCustomerId,
                                            idempotencyKey = sessionIdempotencyKey(),
                                            cashTenderedCents = cashReceivedCents,
                                            changeCents = result.changeCents,
                                            rating = currentRating,
                                            orderId = null,
                                            manualMethod = manualMethod,
                                            tenderType = selectedTender,
                                        )
                                    }
                                    recordCashSale(total, null)
                                    _state.value = PaymentFlowState.Success(
                                        totalAmount = total,
                                        method = PaymentMethod.CASH,
                                        changeAmount = result.changeCents,
                                        isQueued = true,
                                    )
                                } else {
                                    _state.value = PaymentFlowState.Error(
                                        message = error.message ?: "Error al registrar pago",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            is CashPaymentResult.InsufficientFunds -> {
                // Stay on cash screen — insufficient funds handled in UI
            }
        }
    }

    private suspend fun recordCashPaymentForOrder(
        orderId: String,
        total: Int,
        cashReceivedCents: Int,
        changeCents: Int,
        orderRequest: CreateOrderRequest,
    ) {
        // Send amount WITHOUT tip, tip separately
        val subtotal = total - currentTipCents

        // Offline-first Corte C: mesa abierta SIN red (sesión provisional) —
        // la orden aún no existe en el server, así que el cobro va como intent
        // PAY_CASH al outbox con el UUID local. El reducer lo aplicará DESPUÉS
        // del OPEN_TABLE/ADD_ITEMS del mismo dispositivo (FIFO). Regla
        // "Backgrounded": si el replay lo rechaza, la cuenta queda visible en
        // cuarentena — jamás se pierde una venta en silencio.
        val provisionalSession = tableSession.current()?.takeIf { it.isProvisional && it.orderId == orderId }
        if (provisionalSession != null) {
            val vId = secureStorage.venueId
            if (vId != null) {
                syncOutbox.enqueue(
                    vId,
                    com.avoqado.pos.core.data.sync.SyncIntentTypes.PAY_CASH,
                    kotlinx.serialization.json.buildJsonObject {
                        put("localOrderId", kotlinx.serialization.json.JsonPrimitive(orderId))
                        put("amountCents", kotlinx.serialization.json.JsonPrimitive(subtotal))
                        put("tipCents", kotlinx.serialization.json.JsonPrimitive(currentTipCents))
                        // Sin esto, un cobro con terminal ajena hecho sin red
                        // se reproduciría como EFECTIVO y descuadraría el corte.
                        manualMethod?.let { m ->
                            put("method", kotlinx.serialization.json.JsonPrimitive(m.serverMethod))
                            m.externalSource?.let { put("externalSource", kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    },
                )
                recordCashSale(total, orderId)
                _state.value = PaymentFlowState.Success(
                    totalAmount = total,
                    method = PaymentMethod.CASH,
                    changeAmount = changeCents,
                    isQueued = true,
                )
                createKDSOrderAndPrint(PaymentMethod.CASH, changeCents)
                return
            }
        }
        val payResult = orderRepository.recordCashPayment(
            orderId = orderId,
            amount = subtotal,
            staffId = selectedStaffId(),
            tip = currentTipCents,
            splitType = _splitType.value,
            idempotencyKey = sessionIdempotencyKey(),
            manualMethod = manualMethod,
                                            tenderType = selectedTender,
        )
        payResult.fold(
            onSuccess = { result ->
                lastPaymentId = result.paymentId
                // 🔴 El saldo que queda lo dice el server. Ver `buildCompletion`.
                adoptarSaldoDelServer(result.remainingBalanceCents, result.orderPaymentStatus)
                // Una segunda caja pudo mover la orden mientras cobrábamos. En
                // ese caso el server recorta SÓLO efectivo de cajón al saldo
                // fresco y devuelve el importe/cambio que de verdad registró.
                // Pantalla, ticket y arqueo deben contar ese resultado; los
                // campos opcionales conservan el fallback para servers viejos.
                val authoritativeOutcome = result.recordedAmountCents?.let { recordedAmount ->
                    result.recordedTipCents?.let { recordedTip ->
                        val recordedTotal = recordedAmount.toLong() + recordedTip.toLong()
                        recordedTotal
                            .takeIf { recordedAmount >= 0 && recordedTip >= 0 && it <= Int.MAX_VALUE.toLong() }
                            ?.let { safeTotal ->
                                safeTotal.toInt() to (result.authoritativeChangeCents ?: changeCents)
                            }
                    }
                }
                // Un payload imposible no puede desbordar Int y convertir una
                // venta positiva en un movimiento negativo. Igual que iOS,
                // ante cualquier inconsistencia conservamos TODO el resultado
                // local (total y cambio), no una mezcla de ambas fuentes.
                val authoritativeTotal = authoritativeOutcome?.first ?: total
                val finalChange = authoritativeOutcome?.second ?: changeCents
                // Recibo → QR en pantalla del cliente y recibo impreso.
                result.receiptAccessKey?.let { lastReceiptAccessKey = it }
                result.receiptUrl?.let { lastReceiptUrl = it }
                finishAreaTicketPayment()
                recordCashSale(authoritativeTotal, orderId)
                _state.value = PaymentFlowState.Success(
                    totalAmount = authoritativeTotal,
                    method = PaymentMethod.CASH,
                    changeAmount = finalChange,
                    paymentId = result.paymentId,
                    receiptAccessKey = result.receiptAccessKey,
                    receiptUrl = result.receiptUrl,
                    inventoryWarningMessage = result.inventoryWarningMessage,
                )
                createKDSOrderAndPrint(PaymentMethod.CASH, finalChange)
            },
            onFailure = { error ->
                val isQueueable = OrderRepository.isQueueableError(error) ||
                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                if (isQueueable && areaTicketRepository.session.current() == null) {
                    cashPaymentRepository.queueCashPayment(
                        orderRequest = orderRequest,
                        staffId = selectedStaffId(),
                        customerId = attachedCustomerId,
                        idempotencyKey = sessionIdempotencyKey(),
                        cashTenderedCents = cashReceivedCents,
                        changeCents = changeCents,
                        rating = currentRating,
                        orderId = orderId,
                        manualMethod = manualMethod,
                                            tenderType = selectedTender,
                    )
                    recordCashSale(total, orderId)
                    _state.value = PaymentFlowState.Success(
                        totalAmount = total,
                        method = PaymentMethod.CASH,
                        changeAmount = changeCents,
                        isQueued = true,
                    )
                    createKDSOrderAndPrint(PaymentMethod.CASH, changeCents)
                } else {
                    _state.value = PaymentFlowState.Error(
                        message = "No se pudo registrar el pago: ${error.message ?: "error desconocido"}",
                        source = PaymentErrorSource.SERVER,
                    )
                }
            },
        )
    }

    fun sendReceiptWhatsApp(phone: String) {
        val paymentId = lastPaymentId
        val receiptAccessKey = lastReceiptAccessKey
        if (paymentId.isNullOrBlank() && receiptAccessKey.isNullOrBlank()) {
            _whatsAppResult.value = "No se encontró identificador del recibo"
            return
        }
        viewModelScope.launch {
            _whatsAppSending.value = true
            _whatsAppResult.value = null
            orderRepository.sendReceiptWhatsApp(
                paymentId = paymentId,
                phone = phone,
                receiptAccessKey = receiptAccessKey,
            ).fold(
                onSuccess = {
                    _whatsAppResult.value = "Recibo enviado por WhatsApp"
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is OrderRepository.ServerException -> "Error del servidor (${e.code}). Intenta de nuevo."
                        is java.net.UnknownHostException -> "Sin conexión a internet"
                        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
                        else -> e.message ?: "Error al enviar recibo"
                    }
                    _whatsAppResult.value = msg
                },
            )
            _whatsAppSending.value = false
        }
    }

    fun sendReceiptEmail(email: String) {
        val paymentId = lastPaymentId
        val receiptAccessKey = lastReceiptAccessKey
        if (paymentId.isNullOrBlank() && receiptAccessKey.isNullOrBlank()) {
            _emailResult.value = "No se encontró identificador del recibo"
            return
        }
        viewModelScope.launch {
            _emailSending.value = true
            _emailResult.value = null
            orderRepository.sendReceiptEmail(
                paymentId = paymentId,
                email = email,
                receiptAccessKey = receiptAccessKey,
            ).fold(
                onSuccess = {
                    _emailResult.value = "Recibo enviado por correo"
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is OrderRepository.ServerException -> "Error del servidor (${e.code}). Intenta de nuevo."
                        is java.net.UnknownHostException -> "Sin conexión a internet"
                        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
                        else -> e.message ?: "Error al enviar recibo"
                    }
                    _emailResult.value = msg
                },
            )
            _emailSending.value = false
        }
    }

    fun retry() {
        when (selectedMethod) {
            PaymentMethod.CARD -> {
                val total = currentBaseAmount() + currentTipCents
                // 🔴 NUNCA cobrar de nuevo sin preguntar antes cómo quedó el intento anterior.
                // Este `retry()` mandaba directo a "Seleccionar terminal" y cobró una tarjeta
                // dos veces (2026-08-10). Si queda un cobro sin resolver, primero se consulta;
                // sólo si CONSTA que no hubo cargo se ofrece cobrar.
                val pending = undeterminedRequestId
                if (pending != null) {
                    reconcileThenOffer(pending, total)
                    return
                }
                _state.value = PaymentFlowState.SelectingTerminal(total)
                fetchTerminals()
            }
            PaymentMethod.CASH -> {
                val tendered = lastCashTenderedCents ?: (currentBaseAmount() + currentTipCents)
                processCashPayment(tendered)
            }
            null -> {
                val total = currentBaseAmount() + currentTipCents
                _state.value = PaymentFlowState.SelectingPaymentMethod(total)
            }
        }
    }

    /**
     * El cobro con tarjeta consta como exitoso: se cierra el flujo como cualquier venta buena.
     *
     * Se usa igual cuando el éxito llega por la respuesta directa de la terminal que cuando se
     * descubre TARDE, re-consultando el estado durable. En ese segundo caso el cajero no ve
     * ningún error: el cobro salió bien, la app sólo se enteró después.
     */
    private fun applyCardCharged(charged: TerminalPaymentResult.Success, total: Int) {
        undeterminedRequestId = null // el desenlace ya consta
        lastPaymentId = charged.paymentId
        lastReceiptAccessKey = charged.receiptAccessKey
        lastReceiptUrl = charged.receiptUrl
        finishAreaTicketPayment()
        _state.value = PaymentFlowState.Success(
            totalAmount = total,
            method = PaymentMethod.CARD,
            paymentId = charged.paymentId,
            receiptAccessKey = charged.receiptAccessKey,
            receiptUrl = charged.receiptUrl,
        )
        createKDSOrderAndPrint(PaymentMethod.CARD)
    }

    /**
     * El desenlace de la terminal llegó TARDE, después de que el cajero canceló y la pantalla
     * ya avanzó a otra cosa. Descartarlo es correcto para la NAVEGACIÓN; para el DINERO, no.
     *
     * 🔴 **Cancelar es una PETICIÓN, no una garantía.** Si la tarjeta ya se pasó, la terminal
     * cobra igual y el server reconcilia la fila a COMPLETED. El guard anterior tiraba ese
     * desenlace ENTERO —incluido el cobro exitoso—: el dinero salía y la venta quedaba marcada
     * como impaga. Nadie sabía que ese pago existía, y el cajero cobraba otra vez.
     *
     * Ahora la referencia del cobro se re-arma como pendiente en la llave DURABLE (disco), que
     * es la misma que sobrevive al cambio de pestaña y a la muerte del proceso. La próxima venta
     * la encuentra en `startFlow` y muestra "Cobro sin confirmar" con su "Volver a consultar",
     * por la ruta `fromPreviousSale`: informa del cargo viejo SIN pagar la venta nueva.
     */
    private fun handleStaleCardResult(result: TerminalPaymentResult) {
        val (outcome, requestId) = when (result) {
            is TerminalPaymentResult.Success ->
                CardChargeOutcome.Charged(result.paymentId) to result.requestId
            is TerminalPaymentResult.Error ->
                CardChargeOutcome.NotCharged(result.message) to null
            is TerminalPaymentResult.Undetermined ->
                CardChargeOutcome.Undetermined(result.message) to result.requestId
        }
        val pending = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = outcome,
            requestId = requestId,
            armedKey = terminalPaymentService.unresolvedRequestId,
        )
        terminalPaymentService.rearmUnresolvedCharge(pending)
        // Esta pantalla ya no gobierna ese cobro: la llave durable manda, y al no coincidir
        // con ésta la próxima venta lo tratará como "cobro anterior" (no paga la venta nueva).
        undeterminedRequestId = null
        if (pending != null) {
            Log.w("PaymentFlow", "⚠️ Se canceló, pero el cobro no consta como no cobrado (requestId: $pending)")
        } else {
            Log.d("PaymentFlow", "⏭️ Resultado obsoleto tras cancelar: consta que no se cobró")
        }
    }

    /**
     * Vuelve a preguntarle al server cómo quedó un cobro sin resolver y actúa según el desenlace:
     * cobró → flujo normal; no cobró → recién ahí se ofrece cobrar; sigue sin saberse → la
     * pantalla honesta. **Nunca dispara un cargo.**
     */
    private fun reconcileThenOffer(requestId: String, total: Int, fromPreviousSale: Boolean = false) {
        val pendingMessage = if (fromPreviousSale) PREVIOUS_CHARGE_MESSAGE else CardChargeDecision.UNDETERMINED_MESSAGE
        _state.value = PaymentFlowState.Undetermined(
            totalAmount = total,
            message = pendingMessage,
            checking = true,
            fromPreviousSale = fromPreviousSale,
        )
        viewModelScope.launch {
            when (val outcome = terminalPaymentService.resolveOutcome(requestId)) {
                is TerminalPaymentResult.Success -> {
                    undeterminedRequestId = null
                    if (fromPreviousSale) {
                        // 🔴 Ese cobro era de OTRA venta: confirmarlo NO paga ésta.
                        // Y tampoco arranca ésta: el cajero vino a resolver un pendiente,
                        // no a cobrar. Soltarlo en el primer paso de la venta nueva —con el
                        // aviso desvaneciéndose encima— hacía que el desenlace del cobro
                        // viejo pasara volando mientras la pantalla ya le pedía otra cosa.
                        // Vuelve a donde estaba, con el carrito intacto, y él decide.
                        _previousChargeResolved.value = "El cobro anterior sí se había realizado"
                    } else {
                        applyCardCharged(outcome, total)
                    }
                }
                is TerminalPaymentResult.Error -> {
                    // Consta que NO se cobró: aquí sí es seguro ofrecer cobrar de nuevo.
                    undeterminedRequestId = null
                    if (fromPreviousSale) {
                        _previousChargeResolved.value = "El cobro anterior no se realizó"
                    } else {
                        _state.value = PaymentFlowState.SelectingTerminal(total)
                        fetchTerminals()
                    }
                }
                is TerminalPaymentResult.Undetermined -> {
                    undeterminedRequestId = outcome.requestId
                    _state.value = PaymentFlowState.Undetermined(
                        totalAmount = total,
                        message = pendingMessage,
                        checking = false,
                        fromPreviousSale = fromPreviousSale,
                    )
                }
            }
        }
    }

    /**
     * "Volver a consultar" desde la pantalla de cobro no confirmado. Es la acción SEGURA:
     * sólo pregunta, jamás cobra.
     */
    fun recheckCardCharge() {
        val total = currentBaseAmount() + currentTipCents
        val fromPreviousSale = (_state.value as? PaymentFlowState.Undetermined)?.fromPreviousSale == true
        // La llave durable manda: si el proceso murió, `undeterminedRequestId` viene vacío
        // pero el cobro sigue sin resolverse en disco.
        val pending = undeterminedRequestId ?: terminalPaymentService.unresolvedRequestId
        if (pending == null) {
            // Ya no hay nada pendiente que consultar: consta que no hay cargo vivo.
            if (fromPreviousSale) enterInitialState(total) else {
                _state.value = PaymentFlowState.SelectingTerminal(total)
                fetchTerminals()
            }
            return
        }
        reconcileThenOffer(pending, total, fromPreviousSale)
    }

    /**
     * El cajero revisó la terminal, vio la advertencia del riesgo de doble cobro y aun así
     * decide cobrar otra vez. Es una decisión HUMANA y explícita — nunca un camino automático.
     */
    fun chargeAgainDespiteUndetermined() {
        val total = currentBaseAmount() + currentTipCents
        val fromPreviousSale = (_state.value as? PaymentFlowState.Undetermined)?.fromPreviousSale == true
        Log.w("PaymentFlow", "⚠️ Cobro repetido autorizado por el cajero tras un desenlace no confirmado")
        // El cajero se hizo cargo: el intento anterior deja de gobernar este flujo, y la llave
        // durable se suelta para que no vuelva a bloquear la siguiente venta.
        undeterminedRequestId = null
        terminalPaymentService.forgetUnresolvedCharge()
        if (fromPreviousSale) {
            // La venta de la llave era otra: ésta empieza por su primer paso normal.
            enterInitialState(total)
            return
        }
        _state.value = PaymentFlowState.SelectingTerminal(total)
        fetchTerminals()
    }


    fun cancel() {
        isProcessingPayment = false
        paymentGeneration++
        paymentIdempotencyKey = null
        // Cancel pending terminal payment if in progress
        terminalPaymentService.cancelCurrentPayment()

        if (areaTicketRepository.session.current() != null) {
            viewModelScope.launch {
                runCatching { areaTicketRepository.cancel() }
                    .onFailure { Log.e("PaymentFlow", "⚠️ No se pudo liberar la sesión de vales: ${it.message}") }
            }
            return
        }

        createdOrderId?.let { orderId ->
            viewModelScope.launch {
                // 🔴 Un cancel rechazado deja la orden ABIERTA en el server. Loguearlo y
                // navegar afuera le hacía creer al cajero que había cancelado — y un cobro
                // podía aterrizar sobre esa orden viva. El rechazo se VE.
                orderRepository.cancelOrder(orderId).onFailure { e ->
                    Log.e("PaymentFlow", "⚠️ Failed to cancel order $orderId (may be left OPEN): ${e.message}")
                    _cancelFailure.value =
                        "No se pudo cancelar la orden: ${e.message ?: "error desconocido"}. Sigue abierta — revísala en Órdenes."
                }
            }
        }
    }

    /**
     * Cancelación desde una pantalla de dinero (error / cobro sin confirmar): ESPERA el
     * resultado antes de dejar salir. Si el server rechaza, no se sale: se muestra el motivo.
     *
     * @param onCancelled se invoca sólo cuando la cancelación realmente quedó.
     */
    fun cancelAndExit(onCancelled: () -> Unit) {
        val orderId = createdOrderId
        if (orderId == null || areaTicketRepository.session.current() != null) {
            cancel()
            onCancelled()
            return
        }
        isProcessingPayment = false
        paymentGeneration++
        paymentIdempotencyKey = null
        terminalPaymentService.cancelCurrentPayment()
        viewModelScope.launch {
            orderRepository.cancelOrder(orderId).fold(
                onSuccess = { onCancelled() },
                onFailure = { e ->
                    Log.e("PaymentFlow", "⚠️ Failed to cancel order $orderId (may be left OPEN): ${e.message}")
                    _cancelFailure.value =
                        "No se pudo cancelar la orden: ${e.message ?: "error desconocido"}. Sigue abierta — revísala en Órdenes."
                },
            )
        }
    }

    /**
     * 🔴 CINCO de los diez sitios que llaman aquí registran la venta SIN orden
     * (`orderId = null`): es el cobro de MOSTRADOR, que se cobra antes de que exista
     * orden alguna. Son, por flujo:
     *
     *  1. la orden con productos que no se pudo crear y se encoló;
     *  2. el camino de cobro rápido de `processPaymentMethod` ("No orderId — fast
     *     payment path");
     *  3. la orden que falló al crearse dentro de `processCashPayment` y se encoló;
     *  4. el cobro rápido que SÍ pasó en línea;
     *  5. el cobro rápido que falló y se encoló.
     *
     * Se cuentan por FIRMA, no por número de línea (que se mueve con cada edición). La
     * expresión se ancla al principio de la línea a propósito: sin eso el propio
     * comentario que estás leyendo entra en la cuenta y da 5 por el motivo equivocado.
     *
     * ```
     * grep -cE '^ +recordCashSale\(total(, null)?\)$' PaymentFlowViewModel.kt   # → 5 (de 10 sitios)
     * ```
     *
     * Importa porque una fila sin orden no se puede parear por identidad con su cobro
     * encolado: es la que `PendingCashSales` tiene que salvar por MONTO.
     *
     * @param amountCents el total CON propina — el dinero que quedó en el cajón.
     */
    private fun recordCashSale(amountCents: Int, orderId: String? = null) {
        // Un cobro declarado a mano (terminal ajena, transferencia) NUNCA entró al
        // cajón. Meterlo como venta en efectivo le inventa al cajero un faltante por
        // ese monto al cerrar el turno — justo el descuadre que el método manual vino
        // a evitar. El cobro sí se registra en el server con su método real; lo único
        // que NO debe tocar es el arqueo de efectivo.
        manualMethod?.let { declarado ->
            Log.d("💰", "Cobro '${declarado.label}' fuera de Avoqado: no entra al arqueo de efectivo")
            return
        }
        // 🔴 Una venta en CERO (cuenta cortesiada al 100%) no movió efectivo. El server
        // ya la rechaza a propósito (`postCashSaleToDrawer` → `NOT_DRAWER_CASH` cuando
        // el total es <= 0, "sólo ensucia el listado del corte"), así que una fila local
        // en cero es un huérfano permanente: su gemelo del server no va a existir nunca
        // y ninguna confirmación la puede limpiar.
        //
        // También es lo que hace coincidir POR CONSTRUCCIÓN los dos guards del cobro
        // rápido encolado, donde esta llamada vive fuera del `if (cart != null)` que
        // encola: sin carrito el total es forzosamente 0 (`currentBaseAmount()` sale de
        // `cartState`/`splitBaseAmountOverride`, y los dos nacen del carrito), así que
        // la única fila que ese `if` dejaba entrar al cajón sin nadie en la cola que la
        // respaldara es exactamente la que aquí ya no se escribe.
        if (amountCents <= 0) {
            Log.d("💰", "Venta en \$0: no movió efectivo, no entra al arqueo")
            return
        }
        viewModelScope.launch {
            try {
                // addCashSale returns null gracefully when no drawer is open
                // (normal — not every venue runs one). A thrown exception is a
                // REAL insert failure: the drawer total will drift from recorded
                // sales, so log at error level instead of a silent Log.d.
                cashDrawerRepository.addCashSale(amountCents, orderId)
            } catch (e: Exception) {
                Log.e("💰", "Cash drawer insert FAILED (drawer total will drift): ${e.message}", e)
            }
        }
    }

    // MARK: - KDS Order Creation & Auto Print

    private fun createKDSOrderAndPrint(method: PaymentMethod = PaymentMethod.CARD, changeCents: Int? = null) {
        createKDSOrderIfNeeded()
        autoPrintAfterPayment(method, changeCents)
    }

    private fun createKDSOrderIfNeeded() {
        val cart = cartState ?: return
        val realItems = cart.items.filter {
            it.type is CartItemType.ProductItem && !it.locked
        }
        if (realItems.isEmpty()) return

        val orderNumber = createdOrderId?.takeLast(4) ?: "Q-${(1000..9999).random()}"

        viewModelScope.launch {
            val kdsItems = realItems.map { item ->
                KDSOrderItemRequest(
                    productName = item.name,
                    quantity = item.quantity,
                    modifiers = item.selectedModifiers.map { it.modifierName },
                    notes = item.itemNote,
                )
            }

            kdsRepository.createOrder(
                orderNumber = orderNumber,
                orderType = "DINE_IN",
                orderId = createdOrderId,
                items = kdsItems,
            ).fold(
                onSuccess = {
                    Log.d("💰", "KDS order created: #$orderNumber")
                    // Notify same-device KDS via bus
                    kdsOrderBus.publish(
                        com.avoqado.pos.kds.domain.KDSOrder(
                            id = "local-${System.currentTimeMillis()}",
                            orderNumber = orderNumber,
                            orderType = "En tienda",
                            items = realItems.map { item ->
                                com.avoqado.pos.kds.domain.KDSOrderItem(
                                    id = item.id,
                                    productName = item.name,
                                    quantity = item.quantity,
                                    modifiers = item.selectedModifiers.map { it.modifierName },
                                    notes = item.itemNote,
                                )
                            },
                            createdAt = System.currentTimeMillis(),
                            status = com.avoqado.pos.kds.domain.KDSOrderStatus.NEW,
                        ),
                    )
                },
                onFailure = { error ->
                    Log.d("💰", "KDS order failed (non-blocking): ${error.message}")
                },
            )
        }
    }

    // MARK: - Auto Print

    private fun autoPrintAfterPayment(method: PaymentMethod, changeCents: Int? = null) {
        val cart = cartState ?: return
        val realItems = cart.items.filter {
            it.type is CartItemType.ProductItem && !it.locked
        }

        viewModelScope.launch {
            buildReceiptSnapshot(method, changeCents)?.let { receipt ->
                lastReceipt = receipt
                printerService.autoPrintReceipt(receipt)
            }

            // Cajón de dinero: en EFECTIVO se abre SOLO (conducta estándar de POS)
            // si la impresora de recibos tiene el auto-open activado. Va aquí —
            // antes del return por carrito vacío — para que aplique también a un
            // cobro en efectivo de monto personalizado (sin productos). Nunca
            // rompe el cobro: cualquier fallo del cajón/impresora solo se loguea.
            if (method == PaymentMethod.CASH) {
                runCatching {
                    val receiptPrinter = printerService.getDefaultPrinter(
                        com.avoqado.pos.printing.data.model.PrinterRole.RECEIPT,
                    )
                    if (receiptPrinter != null && receiptPrinter.autoOpenCashDrawer) {
                        printerService.openCashDrawer(receiptPrinter)
                    }
                }.onFailure { Log.w("💰", "No se pudo abrir el cajón en venta de efectivo: ${it.message}") }
            }

            // Auto-print kitchen ticket(s)
            if (realItems.isEmpty()) return@launch
            val orderNumber = createdOrderId?.takeLast(4) ?: "Q-${(1000..9999).random()}"

            // PRINT_STATIONS — el disparo POST-PAGO del mostrador. La secuencia (refrescar config →
            // rutear → imprimir, o el ticket legado si el venue no tiene estaciones) vive ahora en
            // [ComandaDispatcher], que es la MISMA pieza que usa el disparo PRE-PAGO del vale de
            // área (§5.6). Mover el mecanismo no cambió ni una llamada de este camino: mismos
            // argumentos, mismo orden, mismo ticket legado (con su `category`) — lo fijan
            // PaymentFlowViewModelTest y ComandaDispatcherTest.
            val comandaResult = comandaDispatcher.dispatch(
                venueId = secureStorage.venueId,
                lines = realItems.map { item ->
                    RoutableItem(
                        orderItemId = item.id,
                        productId = (item.type as? CartItemType.ProductItem)?.productId,
                        categoryId = item.categoryId,
                        productName = item.name,
                        quantity = item.quantity,
                        modifiers = item.selectedModifiers.map { it.modifierName },
                        notes = item.itemNote,
                        // COMBOS — el nombre viaja con la línea para que cada estación
                        // pueda encabezar SUS productos con el combo al que pertenecen.
                        comboName = item.promotionInstanceId?.let { item.promotionName ?: "Combo" },
                    )
                },
                orderNumber = orderNumber,
                orderType = "En tienda",
                // Sin estaciones configuradas: EXACTAMENTE lo de antes — un solo ticket de cocina
                // abanicado a todas las impresoras con rol KITCHEN.
                noStationsFallback = NoStationsFallback.LegacySingleTicket(
                    // COMBOS — en la comanda la llave es el NOMBRE (ver ComboPrintLines):
                    // los productos del mismo combo van juntos bajo un encabezado.
                    ComboPrintLines.kitchen(
                        realItems.map { item ->
                            val comboName = item.promotionInstanceId?.let { item.promotionName ?: "Combo" }
                            val tag = comboName?.let { ComboPrintLines.Tag(key = it, name = it) }
                            tag to KitchenItem(
                                name = item.name,
                                quantity = item.quantity,
                                modifiers = item.selectedModifiers.map { it.modifierName }.ifEmpty { null },
                                note = item.itemNote,
                                category = item.subtitle,
                            )
                        },
                    ),
                ),
            )

            // Una comanda que no salió se DICE, con la estación por nombre. El cobro ya
            // terminó — esto es informativo y jamás lo frena.
            val sinComanda = comandaResult?.stationsSinComanda.orEmpty()
            if (sinComanda.isNotEmpty()) {
                _comandaWarning.value =
                    "No salió la comanda de: ${sinComanda.joinToString(", ")}. Revisa la impresora de esa estación."
            }
        }
    }

    private fun buildReceiptSnapshot(method: PaymentMethod? = null, changeCents: Int? = null): ReceiptData? {
        val cart = cartState
        val successState = _state.value as? PaymentFlowState.Success
        val resolvedMethod = method ?: successState?.method ?: selectedMethod
        val baseAmount = currentBaseAmount()
        val receiptTotal = successState?.totalAmount ?: (baseAmount + currentTipCents)

        fun com.avoqado.pos.pos.data.model.CartItem.toReceiptItem(): ReceiptItem {
            val modifierUnitTotal = selectedModifiers.sumOf { it.priceInCents }
            val effectiveUnitWithModifiers = effectiveUnitPrice + modifierUnitTotal
            return ReceiptItem(
                name = name,
                quantity = quantity,
                unitPrice = effectiveUnitWithModifiers,
                // 🔴 BRUTO, sin el descuento de la línea. El descuento se imprime
                // UNA vez, en su propio renglón junto al subtotal: si además se
                // rebajara aquí, las líneas sumarían menos que el subtotal y el
                // ticket que se lleva el cliente no cuadraría consigo mismo.
                totalPrice = grossPrice,
                modifiers = selectedModifiers.map { it.modifierName }.ifEmpty { null },
                note = itemNote,
                isCortesia = isCortesia,
                weightSummary = weightSummary,
                areaSourceLabel = subtitle.takeIf { locked },
            )
        }

        /**
         * COMBOS — el nombre del combo como renglón y debajo sus componentes sin
         * precio (founder 2026-08-18, patrón Fudo/Square/Toast). La llave es la
         * INSTANCIA: cada combo vendido es su propio renglón con su propio precio.
         * Sin combos en el carrito devuelve exactamente la lista de siempre.
         */
        fun List<com.avoqado.pos.pos.data.model.CartItem>.toReceiptItemsConCombos(): List<ReceiptItem> =
            ComboPrintLines.receipt(
                map { item ->
                    val tag = item.promotionInstanceId?.let { instanceId ->
                        ComboPrintLines.Tag(
                            key = instanceId,
                            name = item.promotionName ?: "Combo",
                        )
                    }
                    tag to item.toReceiptItem()
                },
            )

        val splitTypeValue = _splitType.value
        val receiptItems = when {
            cart == null || cart.items.isEmpty() -> listOf(
                ReceiptItem(
                    name = "Venta",
                    quantity = 1,
                    unitPrice = baseAmount,
                    totalPrice = baseAmount,
                ),
            )
            splitTypeValue == "BYPRODUCT" -> {
                val selected = cart.items.filter { splitSelectedItemIds.contains(it.id) }
                (if (selected.isEmpty()) cart.items else selected).toReceiptItemsConCombos()
            }
            splitTypeValue == "EQUALPARTS" || splitTypeValue == "CUSTOMAMOUNT" -> listOf(
                ReceiptItem(
                    name = "Pago parcial",
                    quantity = 1,
                    unitPrice = baseAmount,
                    totalPrice = baseAmount,
                ),
            )
            else -> cart.items.toReceiptItemsConCombos()
        }

        val isFullPayment = splitTypeValue == "FULLPAYMENT"
        val receiptSubtotal = if (cart != null && isFullPayment) cart.subtotalCents else baseAmount
        val receiptDiscount = if (cart != null && isFullPayment && cart.discountCents > 0) cart.discountCents else 0
        val receiptTax = if (cart != null && isFullPayment) cart.taxCents else 0
        val resolvedChange = changeCents ?: successState?.changeAmount

        // Folio real del backend; solo si falta cae a los últimos 4 del id (mejor
        // algo que "---", pero lo normal es el folio).
        val folio = createdOrderNumber?.takeIf { it.isNotBlank() }
            ?: createdOrderId?.takeLast(4)
            ?: "---"
        // URL del recibo digital para el QR (misma que la pantalla del cliente).
        // La del backend si vino; si no, se arma contra el DASHBOARD — nunca contra
        // la base del API, que es lo que mandaba a la página vieja sin facturación.
        val receiptUrl = com.avoqado.pos.core.data.network.resolveReceiptUrl(
            lastReceiptUrl,
            lastReceiptAccessKey,
        )
        return ReceiptData(
            orderNumber = folio,
            orderType = "En tienda",
            items = receiptItems,
            subtotal = receiptSubtotal,
            taxAmount = receiptTax,
            tipAmount = if (currentTipCents > 0) currentTipCents else null,
            discountAmount = if (receiptDiscount > 0) receiptDiscount else null,
            total = receiptTotal,
            paymentMethod = manualMethod?.label ?: when (resolvedMethod) {
                PaymentMethod.CASH -> "Efectivo"
                PaymentMethod.CARD -> "Tarjeta"
                null -> null
            },
            venueName = secureStorage.venueName ?: "Avoqado",
            customerName = _attachedCustomerName.value,
            cashTendered = if (resolvedMethod == PaymentMethod.CASH && manualMethod == null) resolvedChange?.let { receiptTotal + it } else null,
            changeAmount = resolvedChange,
            transactionId = lastPaymentId,
            receiptUrl = receiptUrl,
            areaDeliveryCode = lastAreaDeliveryCode,
        )
    }

    private fun buildOrderRequest(cart: CartState): CreateOrderRequest {
        // Keep full cart context here. OrderRepository strips non-product lines
        // when building the backend /orders payload, but mixed carts still need
        // full totals locally for payment and offline queue handling.
        //
        // El mapeo vive en `buildOrderItemRequests` porque "pagar después"
        // (CartViewModel.createPayLaterOrder) tiene que producir EXACTAMENTE lo
        // mismo. Ahí es donde el combo se colapsa en una línea con promotionRef.
        val items = buildOrderItemRequests(cart.items)

        return CreateOrderRequest(
            items = items,
            subtotal = cart.subtotalCents,
            // 🔴 SÓLO el descuento de ORDEN, nunca `discountCents` (que ya incluye
            // los de línea). El server calcula los de línea POR SU CUENTA desde el
            // `discountId` de cada item y los suma a éste:
            // `discountDecimal = itemDiscountTotal + orderLevelDiscount`
            // (`order.mobile.service.ts`). Mandar el combinado los restaría DOS
            // veces y la orden saldría más barata de lo que se cobró.
            discount = cart.orderDiscountCents,
            tip = currentTipCents,
            total = cart.totalCents + currentTipCents,
            paymentMethod = selectedMethod?.value ?: "CARD",
            rating = currentRating,
            note = cart.orderNote,
            splitType = _splitType.value,
            reservationId = cart.reservationId,
        )
    }

    /**
     * 🔴 DINERO. Guarda el saldo que el server le puso a la orden tras ESTE
     * cobro. Sólo lo pisa cuando de verdad vino un número: un `null` (server
     * viejo, camino de tarjeta, cobro encolado sin red) deja lo que hubiera y
     * el cliente se queda con su aritmética local.
     */
    private fun adoptarSaldoDelServer(remainingBalanceCents: Int?, orderPaymentStatus: String?) {
        // La orden ya está cerrada: no queda nada por cobrar, diga lo que diga
        // el número. Cobrarle más la rechazaría y el cajero quedaría con una
        // venta que no cierra.
        //
        // 🔴 `uppercase()` no es adorno: `orderPaymentStatus` es un String libre y
        // la normalización vive en el extractor, así que un `"paid"` armado por
        // otro camino (cola offline, un mock) haría fallar el guard EN SILENCIO.
        // Esta clase de bug ya se vio 3 veces en el workspace — ver la memoria
        // `serial-case-sensitivity-bug-class`.
        if (orderPaymentStatus?.uppercase() == "PAID") {
            serverRemainingBalanceCents = 0
            return
        }
        val saldo = (remainingBalanceCents ?: return).coerceAtLeast(0)

        // 🔴 EL MONTO NO DISTINGUE; SÓLO EL ESTADO. El mismo "1 centavo" son dos
        // cosas opuestas, y la aritmética del server lo demuestra:
        //
        //   50.00 − 49.99 = 0.00999999999999801  → PAID     → redondea a 1¢
        //   35.70 − 35.69 = 0.010000000000005116 → PARTIAL  → redondea a 1¢
        //
        // Por eso el perdón del residuo SÓLO aplica con un server VIEJO, que no
        // manda estado y donde no hay forma de distinguirlos. Si el server dijo
        // PARTIAL, ese centavo es deuda REAL: perdonarlo cerraría el carrito
        // dejando la orden abierta, **con el stock sin descontar** — el mismo bug
        // que esta tarea existe para cerrar, a escala de un centavo. Y sería
        // regresión: hoy la aritmética local deja "Saldo pendiente $0.01" y el
        // cajero lo cobra, que es justo lo que cierra la orden.
        val serverViejoSinEstado = orderPaymentStatus == null
        serverRemainingBalanceCents = if (serverViejoSinEstado && saldo <= 1) 0 else saldo
        Log.d("💵", "Saldo del server tras el cobro: $saldo centavos (estado: $orderPaymentStatus)")
    }

    fun buildCompletion(): PaymentCompletion {
        val cart = cartState
        val splitTypeValue = _splitType.value
        if (cart == null) {
            return PaymentCompletion(
                splitType = splitTypeValue,
                remainingBalanceCents = 0,
            )
        }

        return when (splitTypeValue) {
            "BYPRODUCT" -> {
                val validPaidIds = splitSelectedItemIds.intersect(cart.items.map { it.id }.toSet())
                val remaining = cart.items
                    .filterNot { validPaidIds.contains(it.id) }
                    .sumOf { it.totalPrice }
                    .coerceAtLeast(0)
                PaymentCompletion(
                    splitType = splitTypeValue,
                    // 🔴 El saldo del server gana; los artículos pagados NO son
                    // suyos —eso lo eligió el cajero— y siguen saliendo de aquí.
                    remainingBalanceCents = serverRemainingBalanceCents ?: remaining,
                    paidItemIds = validPaidIds,
                    orderId = createdOrderId,
                )
            }
            "EQUALPARTS", "CUSTOMAMOUNT" -> {
                // 🔴 DINERO. Con promoción, lo que vale la venta lo dice el
                // server, no el estimado del carrito. Si el resto se calculara
                // del estimado, la suma de las partes quedaría hasta ±11¢ del
                // total de la orden: el cliente paga otra cosa y la cuenta no
                // cierra. La ÚLTIMA parte absorbe la diferencia.
                //
                // El total del server viene CON la propina de la parte que ya se
                // cobró; el resto se mide sin ella, igual que `currentBaseAmount`.
                val totalDeLaVenta = serverOrderTotalCents
                    ?.let { (it - currentTipCents).coerceAtLeast(0) }
                    ?: cart.totalCents
                val remaining = (totalDeLaVenta - currentBaseAmount()).coerceAtLeast(0)
                PaymentCompletion(
                    splitType = splitTypeValue,
                    // 🔴 Cuando el server dice cuánto queda, ÉSE gana: es el
                    // único que ve los pagos que este aparato no vio (otra caja,
                    // un link, un abono anterior). La resta local es el respaldo.
                    remainingBalanceCents = serverRemainingBalanceCents ?: remaining,
                    orderId = createdOrderId,
                )
            }
            else -> {
                // 🔴 PAGO COMPLETO: el saldo del server NO manda aquí, A PROPÓSITO.
                // Es el camino de más tráfico del mostrador y preferirlo rompía dos
                // cosas: (1) revierte la decisión deliberada de cobrar el estimado
                // local cuando el efectivo no alcanza para el total del server
                // ("en vez de dejar al cajero pidiendo centavos de vuelta", ver
                // `processCashPayment`), y (2) un residuo desviaría el commit a la
                // rama de saldo pendiente, donde `pendingPackGrant` NO se otorga —
                // un cliente pagaría $500 de membresía y no recibiría un solo
                // crédito, sin forma de recuperarlos.
                //
                // Queda vivo el caso preexistente "pago completo con centavos de
                // diferencia ⇒ orden PARTIAL": no lo introdujo este fix y cambiar
                // lo que el cajero hace todos los días es decisión del founder.
                PaymentCompletion(
                    splitType = splitTypeValue,
                    remainingBalanceCents = 0,
                    orderId = createdOrderId,
                )
            }
        }
    }

    /**
     * Entrega el resultado económico una sola vez, en cuanto el pago quedó confirmado.
     * La pantalla de recibo puede permanecer abierta para imprimir o enviar el comprobante,
     * pero cerrar esa pantalla ya no decide si el carrito sigue siendo cobrable.
     */
    fun consumeCompletion(): PaymentCompletion? {
        if (_state.value !is PaymentFlowState.Success || completionConsumed) return null
        completionConsumed = true
        return buildCompletion()
    }

    fun buildPaymentContext(): PaymentContext {
        val cart = cartState ?: return PaymentContext(
            subtotalCents = currentBaseAmount(),
            tipCents = currentTipCents,
            totalCents = currentBaseAmount() + currentTipCents,
            rating = currentRating,
            splitType = _splitType.value,
        )

        val splitTypeValue = _splitType.value
        val baseAmount = currentBaseAmount()
        val visibleItems = visiblePaymentItems(cart)

        return when (splitTypeValue) {
            "FULLPAYMENT" -> PaymentContext(
                subtotalCents = cart.subtotalCents,
                discountCents = cart.discountCents,
                taxCents = cart.taxCents,
                tipCents = currentTipCents,
                totalCents = cart.totalCents + currentTipCents,
                rating = currentRating,
                items = visibleItems,
                splitType = splitTypeValue,
            )
            else -> PaymentContext(
                subtotalCents = baseAmount,
                discountCents = 0,
                taxCents = 0,
                tipCents = currentTipCents,
                totalCents = baseAmount + currentTipCents,
                rating = currentRating,
                items = visibleItems,
                splitType = splitTypeValue,
            )
        }
    }

    private fun currentBaseAmount(): Int {
        // El total del server llega CON propina (se la mandamos al crear la
        // orden); la base es lo que queda al quitársela.
        serverTotalOverrideCents?.let { return (it - currentTipCents).coerceAtLeast(0) }
        return splitBaseAmountOverride ?: (cartState?.totalCents ?: 0)
    }

    /**
     * Fija el total que se va a cobrar cuando el server acaba de crear la orden
     * y devuelve ese total. Ver `totalACobrarCents` para el porqué y para las
     * cuatro condiciones que tiene que cumplir para adoptarse.
     */
    private fun adoptarTotalDelServer(
        orderRequest: CreateOrderRequest,
        response: CreateOrderResponse,
        estimadoLocal: Int,
    ): Int {
        val laVentaLlevaPromocion = orderRequest.items.any { it.promotionRef != null }
        // Se GUARDA aunque no se adopte. En pago dividido el importe de ESTA
        // parte lo eligió el cajero y no se toca, pero el RESTO tiene que salir
        // del total real o la suma de las partes no es lo que vale la venta.
        if (laVentaLlevaPromocion) {
            serverOrderTotalCents = response.data?.totalCents?.takeIf { it >= 0 }
        }
        val total = totalACobrarCents(
            estimadoLocalCents = estimadoLocal,
            orden = response.data,
            esPagoCompleto = _splitType.value == "FULLPAYMENT" && splitBaseAmountOverride == null,
            laVentaLlevaPromocion = laVentaLlevaPromocion,
        )
        if (total != estimadoLocal) {
            serverTotalOverrideCents = total
            Log.d("🎁", "Total del server $total ≠ estimado del carrito $estimadoLocal — se cobra el del server")
        }
        return total
    }

    private fun computeTipPercentageBaseAmount(baseAmount: Int): Int {
        if (baseAmount <= 0) return 0
        if (settings.includeTaxInTipBase) return baseAmount
        val cart = cartState ?: return baseAmount
        val taxComponent = estimateTaxComponentForTipBase(cart, baseAmount)
        return (baseAmount - taxComponent).coerceAtLeast(0)
    }

    private fun estimateTaxComponentForTipBase(cart: CartState, baseAmount: Int): Int {
        if (cart.taxCents <= 0 || baseAmount <= 0) return 0
        return when (_splitType.value) {
            "FULLPAYMENT" -> {
                cart.taxCents.coerceAtMost(baseAmount)
            }
            "BYPRODUCT" -> {
                // BYPRODUCT split amount currently comes from selected item totals,
                // which are pre-tax values; avoid subtracting tax twice.
                0
            }
            else -> {
                val cartTotal = cart.totalCents
                if (cartTotal <= 0) return 0
                ((cart.taxCents.toLong() * baseAmount.toLong()) / cartTotal.toLong())
                    .toInt()
                    .coerceAtMost(baseAmount)
            }
        }
    }

    private fun hasProductItems(cart: CartState): Boolean {
        return cart.items.any { it.type is CartItemType.ProductItem }
    }

    private suspend fun materializeAreaTicketCheckout(cart: CartState): Boolean {
        return try {
            val normalItems = cart.items
                .filter { !it.locked }
                .mapNotNull { item ->
                    val productId = (item.type as? CartItemType.ProductItem)?.productId ?: return@mapNotNull null
                    NormalCheckoutItem(
                        productId = productId,
                        quantity = item.quantity,
                        notes = item.itemNote,
                        modifierIds = item.selectedModifiers.map { it.modifierId },
                        discountId = item.itemDiscountId,
                        weightQuantity = item.weightKg,
                    )
                }
            val checkout = areaTicketRepository.materialize(
                normalItems = normalItems,
                customerName = null,
                note = cart.orderNote,
            )
            if (checkout.status in setOf("PAYMENT_PENDING", "RECONCILIATION_REQUIRED")) {
                _state.value = PaymentFlowState.Error(
                    message = "Este cobro sigue en confirmación. No vuelvas a cobrar; revisa el estado del pago.",
                    source = PaymentErrorSource.SERVER,
                )
                return false
            }
            val order = checkout.order
            if (order == null) {
                _state.value = PaymentFlowState.Error(
                    message = "No se pudo materializar la orden de los vales.",
                    source = PaymentErrorSource.SERVER,
                )
                false
            } else {
                createdOrderId = order.id
                createdOrderNumber = order.orderNumber
                lastAreaDeliveryCode = order.areaDeliveryCode
                true
            }
        } catch (error: Exception) {
            // Una sesión vencida no es un cobro fallido: no se cobró nada y los vales
            // siguen vivos. Tirar aquí la sesión muerta es lo que permite que el
            // siguiente escaneo abra una nueva; si la dejáramos, la caja se quedaría
            // pulsando Cobrar contra algo que el server ya no acepta.
            if ((error as? com.avoqado.pos.areatickets.data.AreaTicketException)?.code == "CHECKOUT_SESSION_STALE") {
                runCatching { areaTicketRepository.session.clear() }
            }
            _state.value = PaymentFlowState.Error(
                message = error.message ?: "No se pudo preparar la venta de vales.",
                source = PaymentErrorSource.SERVER,
            )
            false
        }
    }

    private fun finishAreaTicketPayment() {
        if (areaTicketRepository.session.current() == null) return
        if (_splitType.value == "FULLPAYMENT") {
            areaTicketRepository.session.clear()
        } else {
            viewModelScope.launch {
                runCatching { areaTicketRepository.refresh() }
                    .onFailure { Log.e("🎟️", "No se pudo refrescar el saldo de la sesión: ${it.message}") }
            }
        }
    }

    private fun visiblePaymentItems(cart: CartState): List<PaymentItem> {
        val sourceItems = when (_splitType.value) {
            "BYPRODUCT" -> {
                val selected = cart.items.filter { splitSelectedItemIds.contains(it.id) }
                if (selected.isEmpty()) cart.items else selected
            }
            else -> cart.items
        }

        return sourceItems.map { item ->
            val modifierNames = item.selectedModifiers.map { it.modifierName }
            PaymentItem(
                name = item.name,
                quantity = item.quantity,
                unitPrice = item.effectiveUnitPrice + item.selectedModifiers.sumOf { it.priceInCents },
                // BRUTO, igual que en el ticket: esta lista se pinta JUNTO al
                // desglose (Subtotal / Descuento) en la pantalla de cobro. Con la
                // línea rebajada, los renglones sumaban menos que el subtotal y
                // parecía que el descuento se aplicaba dos veces.
                lineTotal = item.grossPrice,
                modifiers = modifierNames,
                note = item.itemNote,
                isCortesia = item.isCortesia,
            )
        }
    }

    private fun resolveSplitBaseAmount(cart: CartState): Int? {
        return when (_splitType.value) {
            "BYPRODUCT" -> {
                val selectedTotal = cart.items
                    .filter { splitSelectedItemIds.contains(it.id) }
                    .sumOf { it.totalPrice }
                selectedTotal.coerceAtLeast(0)
            }
            "EQUALPARTS" -> {
                val parts = (splitNumberOfParts ?: 1).coerceAtLeast(1)
                (cart.totalCents + parts - 1) / parts
            }
            "CUSTOMAMOUNT" -> {
                (splitCustomAmountCents ?: cart.totalCents).coerceIn(0, cart.totalCents)
            }
            else -> null
        }
    }

    private companion object {
        /** Sobrevive a la muerte del proceso junto con el resto del SavedStateHandle. */
        const val KEY_UNDETERMINED_REQUEST = "undeterminedChargeRequestId"

        /** Copy para un cobro sin confirmar heredado de OTRA venta. */
        const val PREVIOUS_CHARGE_MESSAGE =
            "Quedó un cobro sin confirmar de una venta anterior. " +
                "Revisa la terminal antes de cobrar de nuevo."
    }
}
