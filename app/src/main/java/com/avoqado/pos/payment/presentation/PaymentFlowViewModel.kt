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
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.OrderModifierRequest
import com.avoqado.pos.payment.data.model.PaymentContext
import com.avoqado.pos.payment.data.model.PaymentErrorSource
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentItem
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.printing.NoStationsFallback
import com.avoqado.pos.printing.data.PrinterService
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

    /// Invalidates in-flight terminal sends: cancel() bumps it, and a late
    /// result from a cancelled send is ignored instead of overwriting the
    /// screen, marking Success and PRINTING a receipt for a cancelled payment.
    private var paymentGeneration = 0

    init {
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
                if (st is PaymentFlowState.Success || st is PaymentFlowState.Error) {
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
                is PaymentFlowState.Loading,
                is PaymentFlowState.Error,
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
     */
    fun startPaymentFlow(cart: CartState, customerId: String? = null, customerName: String? = null) {
        cartState = cart
        completionConsumed = false
        splitBaseAmountOverride = resolveSplitBaseAmount(cart)
        val amount = currentBaseAmount()

        // Reset transient state from any previous session.
        isProcessingPayment = false
        paymentIdempotencyKey = null
        selectedMethod = null
        currentRating = null
        currentTipCents = 0
        createdOrderId = null
        createdOrderNumber = null

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

        // Determine first step based on TPV settings
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
     * El mesero declara que ya le pagaron por otro medio. No hay teclado ni
     * cambio: el monto es exactamente el total, así que se cobra de una.
     */
    /** Etiqueta del cobro manual para la pantalla de éxito y el recibo. */
    val manualMethodLabel: String? get() = manualMethod?.label

    fun confirmManualMethod(method: com.avoqado.pos.payment.domain.ManualPaymentMethod) {
        manualMethod = method
        selectedMethod = PaymentMethod.CASH
        val total = currentBaseAmount() + currentTipCents
        lastCashTenderedCents = total
        processCashPayment(total)
    }

    fun confirmCashPreset(tenderedCents: Int) {
        manualMethod = null
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    /** Custom cash amount confirmed from bottom sheet keypad */
    fun confirmCashCustom(tenderedCents: Int) {
        manualMethod = null
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    fun probeTerminalAvailability() {
        _terminalAvailability.value = TerminalAvailability.CHECKING
        viewModelScope.launch {
            when (val result = terminalPaymentService.fetchOnlineTerminals()) {
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
                                processPaymentMethod(total)
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
                                        _state.value = PaymentFlowState.Error(
                                            message = error.message ?: "Error al crear la orden",
                                            source = PaymentErrorSource.SERVER,
                                        )
                                    }
                                } else {
                                    _state.value = PaymentFlowState.Error(
                                        message = error.message ?: "Error al crear la orden",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                }
                            },
                        )
                    } else {
                        // Retry card flow: reuse existing order instead of creating a new one.
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
                )
                if (generation != paymentGeneration) {
                    // User cancelled while the send was in flight (up to 310s):
                    // do NOT mark Success/Error nor print for a dead attempt.
                    Log.d("PaymentFlow", "⏭️ Ignoring stale terminal result (cancelled)")
                    return
                }
                when (terminalResult) {
                    is TerminalPaymentResult.Success -> {
                        lastPaymentId = terminalResult.paymentId
                        lastReceiptAccessKey = terminalResult.receiptAccessKey
                        lastReceiptUrl = terminalResult.receiptUrl
                        finishAreaTicketPayment()
                        _state.value = PaymentFlowState.Success(
                            totalAmount = total,
                            method = PaymentMethod.CARD,
                            paymentId = terminalResult.paymentId,
                            receiptAccessKey = terminalResult.receiptAccessKey,
                            receiptUrl = terminalResult.receiptUrl,
                        )
                        createKDSOrderAndPrint(PaymentMethod.CARD)
                    }
                    is TerminalPaymentResult.Error -> {
                        _state.value = PaymentFlowState.Error(
                            message = terminalResult.message,
                            source = PaymentErrorSource.TERMINAL,
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
                    )
                    payResult.fold(
                        onSuccess = { result ->
                            lastPaymentId = result.paymentId
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
                                    recordCashPaymentForOrder(
                                        orderId = orderId,
                                        total = total,
                                        cashReceivedCents = cashReceivedCents,
                                        changeCents = result.changeCents,
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
                        )
                        fastResult.fold(
                            onSuccess = { fast ->
                                lastPaymentId = fast.paymentId
                                // Recibo → QR en pantalla del cliente y recibo impreso.
                                fast.receiptAccessKey?.let { lastReceiptAccessKey = it }
                                fast.receiptUrl?.let { lastReceiptUrl = it }
                                recordCashSale(total, null)
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = total,
                                    method = PaymentMethod.CASH,
                                    changeAmount = result.changeCents,
                                    paymentId = fast.paymentId,
                                    receiptAccessKey = fast.receiptAccessKey,
                                    receiptUrl = fast.receiptUrl,
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
        )
        payResult.fold(
            onSuccess = { result ->
                lastPaymentId = result.paymentId
                // Recibo → QR en pantalla del cliente y recibo impreso.
                result.receiptAccessKey?.let { lastReceiptAccessKey = it }
                result.receiptUrl?.let { lastReceiptUrl = it }
                finishAreaTicketPayment()
                recordCashSale(total, orderId)
                _state.value = PaymentFlowState.Success(
                    totalAmount = total,
                    method = PaymentMethod.CASH,
                    changeAmount = changeCents,
                    paymentId = result.paymentId,
                    receiptAccessKey = result.receiptAccessKey,
                    receiptUrl = result.receiptUrl,
                )
                createKDSOrderAndPrint(PaymentMethod.CASH, changeCents)
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
                // A failed cancel leaves the order OPEN server-side with nobody
                // aware. Log it (was fully discarded) so orphaned orders are
                // diagnosable; the order screen reconciliation can pick them up.
                orderRepository.cancelOrder(orderId).onFailure { e ->
                    Log.e("PaymentFlow", "⚠️ Failed to cancel order $orderId (may be left OPEN): ${e.message}")
                }
            }
        }
    }

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
            comandaDispatcher.dispatch(
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
                    )
                },
                orderNumber = orderNumber,
                orderType = "En tienda",
                // Sin estaciones configuradas: EXACTAMENTE lo de antes — un solo ticket de cocina
                // abanicado a todas las impresoras con rol KITCHEN.
                noStationsFallback = NoStationsFallback.LegacySingleTicket(
                    realItems.map { item ->
                        KitchenItem(
                            name = item.name,
                            quantity = item.quantity,
                            modifiers = item.selectedModifiers.map { it.modifierName }.ifEmpty { null },
                            note = item.itemNote,
                            category = item.subtitle,
                        )
                    },
                ),
            )
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
                totalPrice = totalPrice,
                modifiers = selectedModifiers.map { it.modifierName }.ifEmpty { null },
                note = itemNote,
                isCortesia = isCortesia,
                weightSummary = weightSummary,
                areaSourceLabel = subtitle.takeIf { locked },
            )
        }

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
                (if (selected.isEmpty()) cart.items else selected).map { it.toReceiptItem() }
            }
            splitTypeValue == "EQUALPARTS" || splitTypeValue == "CUSTOMAMOUNT" -> listOf(
                ReceiptItem(
                    name = "Pago parcial",
                    quantity = 1,
                    unitPrice = baseAmount,
                    totalPrice = baseAmount,
                ),
            )
            else -> cart.items.map { it.toReceiptItem() }
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
        val items = cart.items.map { item ->
            OrderItemRequest(
                productId = when (val type = item.type) {
                    is CartItemType.ProductItem -> type.productId
                    CartItemType.CustomAmount -> null
                    is CartItemType.CreditPack -> null // not a product line; credits granted separately
                },
                name = item.name,
                quantity = item.quantity,
                unitPrice = item.effectiveUnitPrice,
                modifiers = item.selectedModifiers.map {
                    OrderModifierRequest(
                        modifierId = it.modifierId,
                        name = it.modifierName,
                        price = it.priceInCents,
                    )
                },
                note = item.itemNote,
                isCortesia = item.isCortesia,
                discountId = item.itemDiscountId,
                weightQuantity = item.weightKg,
            )
        }

        return CreateOrderRequest(
            items = items,
            subtotal = cart.subtotalCents,
            discount = cart.discountCents,
            tip = currentTipCents,
            total = cart.totalCents + currentTipCents,
            paymentMethod = selectedMethod?.value ?: "CARD",
            rating = currentRating,
            note = cart.orderNote,
            splitType = _splitType.value,
            reservationId = cart.reservationId,
        )
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
                    remainingBalanceCents = remaining,
                    paidItemIds = validPaidIds,
                    orderId = createdOrderId,
                )
            }
            "EQUALPARTS", "CUSTOMAMOUNT" -> {
                val remaining = (cart.totalCents - currentBaseAmount()).coerceAtLeast(0)
                PaymentCompletion(
                    splitType = splitTypeValue,
                    remainingBalanceCents = remaining,
                    orderId = createdOrderId,
                )
            }
            else -> {
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
        return splitBaseAmountOverride ?: (cartState?.totalCents ?: 0)
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
                lineTotal = item.totalPrice,
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
}
