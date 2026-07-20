package com.avoqado.pos.payment.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
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
)

const val LOCAL_PRINTER_UNAVAILABLE = "__LOCAL_PRINTER_UNAVAILABLE__"

@HiltViewModel
class PaymentFlowViewModel @Inject constructor(
    private val tableSession: com.avoqado.pos.tables.data.TableSession,
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
    private val printConfigRepository: PrintConfigRepository,
    private val comandaPrinter: ComandaPrinter,
    private val customerDisplay: com.avoqado.pos.customerdisplay.CustomerDisplayState,
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentFlowState>(PaymentFlowState.Loading)
    val state: StateFlow<PaymentFlowState> = _state.asStateFlow()

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
                        // Sin llave de recibo no se dibuja QR: mejor nada que un
                        // código que no lleva a ningún lado.
                        receiptUrl = st.receiptAccessKey?.let { key ->
                            com.avoqado.pos.core.data.network.ApiConstants.BASE_URL + "/public/receipt/" + key
                        },
                    )

                // El resto (selección de método, efectivo, terminal, error) es
                // trabajo del cajero: el cliente sigue viendo su total.
                else -> return
            },
        )
    }
    private var selectedTerminalId: String? = null
    private var lastPaymentId: String? = null
    private var lastReceiptAccessKey: String? = null
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
                val count = printerService.manualPrintReceipt(receipt)
                _printResult.value = if (count > 0) {
                    "Recibo impreso"
                } else if (!selectedTerminalId.isNullOrBlank()) {
                    LOCAL_PRINTER_UNAVAILABLE
                } else {
                    "No hay impresora de recibos configurada"
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

    fun startPaymentFlow(cart: CartState) {
        cartState = cart
        splitBaseAmountOverride = resolveSplitBaseAmount(cart)
        val amount = currentBaseAmount()

        // Reset transient state from any previous session.
        isProcessingPayment = false
        paymentIdempotencyKey = null
        selectedMethod = null
        currentRating = null
        currentTipCents = 0
        createdOrderId = null

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
        currentRating = rating
        val amount = currentBaseAmount()

        if (settings.showTipScreen) {
            _state.value = PaymentFlowState.CollectingTip(amount, rating)
        } else {
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        }
    }

    fun submitTip(tipCents: Int) {
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
    fun confirmCashPreset(tenderedCents: Int) {
        selectedMethod = PaymentMethod.CASH
        lastCashTenderedCents = tenderedCents
        processCashPayment(tenderedCents)
    }

    /** Custom cash amount confirmed from bottom sheet keypad */
    fun confirmCashCustom(tenderedCents: Int) {
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
                val hasRealProducts = hasProductItems(cart)

                if (hasRealProducts) {
                    if (createdOrderId == null) {
                        // Create order only once per payment session.
                        val orderRequest = buildOrderRequest(cart)
                        val orderResult = orderRepository.createOrder(orderRequest, staffId = selectedStaffId(), orderType = cart.orderType)

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
                                            cashTenderedCents = null,
                                            changeCents = null,
                                            rating = currentRating,
                                            orderId = null,
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
                        _state.value = PaymentFlowState.Success(
                            totalAmount = total,
                            method = PaymentMethod.CARD,
                            paymentId = terminalResult.paymentId,
                            receiptAccessKey = terminalResult.receiptAccessKey,
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
                    )
                    payResult.fold(
                        onSuccess = { paymentId ->
                            lastPaymentId = paymentId
                            recordCashSale(total, orderId)
                            _state.value = PaymentFlowState.Success(
                                totalAmount = total,
                                method = PaymentMethod.CASH,
                                paymentId = paymentId,
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
                            if (isQueueable && cart != null) {
                                cashPaymentRepository.queueCashPayment(
                                    orderRequest = buildOrderRequest(cart),
                                    staffId = selectedStaffId(),
                                    cashTenderedCents = null,
                                    changeCents = null,
                                    rating = currentRating,
                                    orderId = orderId,
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
                            val orderResult = orderRepository.createOrder(orderRequest, staffId = selectedStaffId(), orderType = cart.orderType)
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
                                            cashTenderedCents = cashReceivedCents,
                                            changeCents = result.changeCents,
                                            rating = currentRating,
                                            orderId = null,
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
                        )
                        fastResult.fold(
                            onSuccess = { paymentId ->
                                lastPaymentId = paymentId
                                recordCashSale(total, null)
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = total,
                                    method = PaymentMethod.CASH,
                                    changeAmount = result.changeCents,
                                    paymentId = paymentId,
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
                                            cashTenderedCents = cashReceivedCents,
                                            changeCents = result.changeCents,
                                            rating = currentRating,
                                            orderId = null,
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
        val payResult = orderRepository.recordCashPayment(
            orderId = orderId,
            amount = subtotal,
            staffId = selectedStaffId(),
            tip = currentTipCents,
            splitType = _splitType.value,
            idempotencyKey = sessionIdempotencyKey(),
        )
        payResult.fold(
            onSuccess = { paymentId ->
                lastPaymentId = paymentId
                recordCashSale(total, orderId)
                _state.value = PaymentFlowState.Success(
                    totalAmount = total,
                    method = PaymentMethod.CASH,
                    changeAmount = changeCents,
                    paymentId = paymentId,
                )
                createKDSOrderAndPrint(PaymentMethod.CASH, changeCents)
            },
            onFailure = { error ->
                val isQueueable = OrderRepository.isQueueableError(error) ||
                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                if (isQueueable) {
                    cashPaymentRepository.queueCashPayment(
                        orderRequest = orderRequest,
                        staffId = selectedStaffId(),
                        cashTenderedCents = cashReceivedCents,
                        changeCents = changeCents,
                        rating = currentRating,
                        orderId = orderId,
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
                        is java.net.UnknownHostException -> "Sin conexion a internet"
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
                        is java.net.UnknownHostException -> "Sin conexion a internet"
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
            it.type is CartItemType.ProductItem
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
            it.type is CartItemType.ProductItem
        }

        viewModelScope.launch {
            buildReceiptSnapshot(method, changeCents)?.let { receipt ->
                lastReceipt = receipt
                printerService.autoPrintReceipt(receipt)
            }

            // Auto-print kitchen ticket(s)
            if (realItems.isEmpty()) return@launch
            val orderNumber = createdOrderId?.takeLast(4) ?: "Q-${(1000..9999).random()}"

            // PRINT_STATIONS — refresh() never throws (fails open to an empty/default
            // config internally), so a stale/slow/failed fetch just falls back to the
            // legacy single-ticket path below — safe.
            secureStorage.venueId?.let { venueId -> printConfigRepository.refresh(venueId) }
            val printConfig = printConfigRepository.getCurrentConfig()

            if (printConfig.stations.any { it.active }) {
                val routableItems = realItems.map { item ->
                    RoutableItem(
                        orderItemId = item.id,
                        productId = (item.type as? CartItemType.ProductItem)?.productId,
                        categoryId = item.categoryId,
                        productName = item.name,
                        quantity = item.quantity,
                        modifiers = item.selectedModifiers.map { it.modifierName },
                        notes = item.itemNote,
                    )
                }
                val plans = PrintRoutingMapper.buildComandas(routableItems, printConfig)
                comandaPrinter.printComandas(
                    plans = plans,
                    config = printConfig,
                    orderNumber = orderNumber,
                    orderType = "En tienda",
                )
            } else {
                // No print stations configured for this venue — EXACT legacy behavior:
                // one kitchen ticket, fanned out to every enabled KITCHEN-role printer.
                val kitchenTicket = KitchenTicketData(
                    orderNumber = orderNumber,
                    orderType = "En tienda",
                    items = realItems.map { item ->
                        KitchenItem(
                            name = item.name,
                            quantity = item.quantity,
                            modifiers = item.selectedModifiers.map { it.modifierName }.ifEmpty { null },
                            note = item.itemNote,
                            category = item.subtitle,
                        )
                    },
                )
                printerService.autoPrintKitchenTicket(kitchenTicket)
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
                totalPrice = totalPrice,
                modifiers = selectedModifiers.map { it.modifierName }.ifEmpty { null },
                note = itemNote,
                isCortesia = isCortesia,
                weightSummary = weightSummary,
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

        return ReceiptData(
            orderNumber = createdOrderId?.takeLast(4) ?: "---",
            orderType = "En tienda",
            items = receiptItems,
            subtotal = receiptSubtotal,
            taxAmount = receiptTax,
            tipAmount = if (currentTipCents > 0) currentTipCents else null,
            discountAmount = if (receiptDiscount > 0) receiptDiscount else null,
            total = receiptTotal,
            paymentMethod = when (resolvedMethod) {
                PaymentMethod.CASH -> "Efectivo"
                PaymentMethod.CARD -> "Tarjeta"
                null -> null
            },
            venueName = secureStorage.venueName ?: "Avoqado",
            cashTendered = if (resolvedMethod == PaymentMethod.CASH) resolvedChange?.let { receiptTotal + it } else null,
            changeAmount = resolvedChange,
            transactionId = lastPaymentId,
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
                )
            }
            "EQUALPARTS", "CUSTOMAMOUNT" -> {
                val remaining = (cart.totalCents - currentBaseAmount()).coerceAtLeast(0)
                PaymentCompletion(
                    splitType = splitTypeValue,
                    remainingBalanceCents = remaining,
                )
            }
            else -> {
                PaymentCompletion(
                    splitType = splitTypeValue,
                    remainingBalanceCents = 0,
                )
            }
        }
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
