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
import com.avoqado.pos.payment.data.model.PaymentErrorSource
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaymentFlowViewModel @Inject constructor(
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
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentFlowState>(PaymentFlowState.Loading)
    val state: StateFlow<PaymentFlowState> = _state.asStateFlow()

    private val _onlineTerminals = MutableStateFlow<List<OnlineTerminal>>(emptyList())
    val onlineTerminals: StateFlow<List<OnlineTerminal>> = _onlineTerminals.asStateFlow()

    private var cartState: CartState? = null
    private var selectedMethod: PaymentMethod? = null
    private var currentRating: Int? = null
    private var currentTipCents: Int = 0
    private var createdOrderId: String? = null
    private var selectedTerminalId: String? = null
    private var lastPaymentId: String? = null
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

    fun clearPrintResult() {
        _printResult.value = null
    }

    fun reprintReceipt() {
        val receipt = lastReceipt
        if (receipt == null) {
            _printResult.value = "No hay recibo disponible para reimprimir"
            return
        }
        viewModelScope.launch {
            _printSending.value = true
            _printResult.value = null
            try {
                val count = printerService.manualPrintReceipt(receipt)
                _printResult.value = if (count > 0) {
                    "Recibo impreso"
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

    fun startPaymentFlow(cart: CartState) {
        cartState = cart
        splitBaseAmountOverride = resolveSplitBaseAmount(cart)
        val amount = currentBaseAmount()

        // Clear receipt sending state from previous payment
        _whatsAppResult.value = null
        _whatsAppSending.value = false
        _emailResult.value = null
        _emailSending.value = false
        _printResult.value = null
        _printSending.value = false
        lastReceipt = null

        Log.d("💰", "Starting payment flow - amount: $amount")

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
        processCashPayment(tenderedCents)
    }

    /** Custom cash amount confirmed from bottom sheet keypad */
    fun confirmCashCustom(tenderedCents: Int) {
        selectedMethod = PaymentMethod.CASH
        processCashPayment(tenderedCents)
    }

    private fun fetchTerminals() {
        viewModelScope.launch {
            when (val result = terminalPaymentService.fetchOnlineTerminals()) {
                is TerminalListResult.Success -> {
                    _onlineTerminals.value = result.terminals
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
        confirmPayment()
    }

    fun confirmPayment() {
        val cart = cartState ?: return
        val total = currentBaseAmount() + currentTipCents
        _state.value = PaymentFlowState.Processing(total)

        viewModelScope.launch {
            try {
                // Check if cart has real products (not just custom amounts from keypad)
                val hasRealProducts = cart.items.any {
                    it.type is com.avoqado.pos.pos.data.model.CartItemType.ProductItem
                }

                if (hasRealProducts) {
                    // Create order on server for real products
                    val orderRequest = buildOrderRequest(cart)
                    val orderResult = orderRepository.createOrder(orderRequest)

                    orderResult.fold(
                        onSuccess = { response ->
                            createdOrderId = response.data?.id
                            processPaymentMethod(total, orderRequest = null, cashTenderedCents = null)
                        },
                        onFailure = { error ->
                            // For CASH payments: queue offline if network/server error
                            if (selectedMethod == PaymentMethod.CASH) {
                                val isQueueable = OrderRepository.isQueueableError(error) ||
                                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))

                                if (isQueueable) {
                                    cashPaymentRepository.queueCashPayment(
                                        orderRequest = orderRequest,
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
                    // Custom amount only — use Fast Payment endpoint
                    Log.d("💰", "Custom amount payment (fast) - total: $total")
                    processPaymentMethod(total, orderRequest = null, cashTenderedCents = null)
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

    private suspend fun processPaymentMethod(
        total: Int,
        orderRequest: CreateOrderRequest?,
        cashTenderedCents: Int?,
    ) {
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
                val terminalResult = terminalPaymentService.sendPaymentToTerminal(
                    terminalId = terminalId,
                    amountCents = currentBaseAmount(),
                    tipCents = currentTipCents,
                    rating = currentRating,
                    orderId = createdOrderId,
                )
                when (terminalResult) {
                    is TerminalPaymentResult.Success -> {
                        _state.value = PaymentFlowState.Success(
                            totalAmount = total,
                            method = PaymentMethod.CARD,
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
                        tip = currentTipCents,
                        splitType = _splitType.value,
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
        val total = currentBaseAmount() + currentTipCents

        when (val result = cashPaymentRepository.processCashPayment(total, cashReceivedCents)) {
            is CashPaymentResult.Success -> {
                viewModelScope.launch {
                    _state.value = PaymentFlowState.Processing(total)

                    val cart = cartState
                    val hasRealProducts = cart?.items?.any {
                        it.type is com.avoqado.pos.pos.data.model.CartItemType.ProductItem
                    } ?: false

                    if (hasRealProducts) {
                        // Order-based cash payment: create order then record payment
                        val orderRequest = buildOrderRequest(cart!!)
                        val orderResult = orderRepository.createOrder(orderRequest)
                        orderResult.fold(
                            onSuccess = { orderResponse ->
                                createdOrderId = orderResponse.data?.id
                                val orderId = createdOrderId
                                if (orderId == null) {
                                    _state.value = PaymentFlowState.Error(
                                        message = "No se pudo obtener la orden creada",
                                        source = PaymentErrorSource.SERVER,
                                    )
                                    return@fold
                                }

                                // Send amount WITHOUT tip, tip separately
                                val subtotal = total - currentTipCents
                                val payResult = orderRepository.recordCashPayment(
                                    orderId = orderId,
                                    amount = subtotal,
                                    tip = currentTipCents,
                                    splitType = _splitType.value,
                                )
                                payResult.fold(
                                    onSuccess = { paymentId ->
                                        lastPaymentId = paymentId
                                        recordCashSale(total, orderId)
                                        _state.value = PaymentFlowState.Success(
                                            totalAmount = total,
                                            method = PaymentMethod.CASH,
                                            changeAmount = result.changeCents,
                                            paymentId = paymentId,
                                        )
                                        createKDSOrderAndPrint(PaymentMethod.CASH, result.changeCents)
                                    },
                                    onFailure = { error ->
                                        val isQueueable = OrderRepository.isQueueableError(error) ||
                                            (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                                        if (isQueueable) {
                                            cashPaymentRepository.queueCashPayment(
                                                orderRequest = orderRequest,
                                                cashTenderedCents = cashReceivedCents,
                                                changeCents = result.changeCents,
                                                rating = currentRating,
                                                orderId = orderId,
                                            )
                                            recordCashSale(total, orderId)
                                            _state.value = PaymentFlowState.Success(
                                                totalAmount = total,
                                                method = PaymentMethod.CASH,
                                                changeAmount = result.changeCents,
                                                isQueued = true,
                                            )
                                            createKDSOrderAndPrint(PaymentMethod.CASH, result.changeCents)
                                        } else {
                                            _state.value = PaymentFlowState.Error(
                                                message = "No se pudo registrar el pago: ${error.message ?: "error desconocido"}",
                                                source = PaymentErrorSource.SERVER,
                                            )
                                        }
                                    },
                                )
                            },
                            onFailure = { error ->
                                val isQueueable = OrderRepository.isQueueableError(error) ||
                                    (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))
                                if (isQueueable) {
                                    cashPaymentRepository.queueCashPayment(
                                        orderRequest = orderRequest,
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
                    } else {
                        // Fast payment (custom amount, no products)
                        // Send amount WITHOUT tip, tip separately
                        val subtotal = total - currentTipCents
                        val fastResult = orderRepository.recordFastCashPayment(
                            amount = subtotal,
                            tip = currentTipCents,
                            splitType = _splitType.value,
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

    fun sendReceiptWhatsApp(phone: String) {
        val paymentId = lastPaymentId ?: return
        viewModelScope.launch {
            _whatsAppSending.value = true
            _whatsAppResult.value = null
            orderRepository.sendReceiptWhatsApp(paymentId, phone).fold(
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
        val paymentId = lastPaymentId ?: return
        viewModelScope.launch {
            _emailSending.value = true
            _emailResult.value = null
            orderRepository.sendReceiptEmail(paymentId, email).fold(
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
        val baseAmount = currentBaseAmount()
        val total = baseAmount + currentTipCents
        // Go back to terminal selection instead of restarting the whole flow
        _state.value = PaymentFlowState.SelectingTerminal(total)
        fetchTerminals()
    }

    fun cancel() {
        // Cancel pending terminal payment if in progress
        terminalPaymentService.cancelCurrentPayment()

        createdOrderId?.let { orderId ->
            viewModelScope.launch {
                orderRepository.cancelOrder(orderId)
            }
        }
    }

    private fun recordCashSale(amountCents: Int, orderId: String? = null) {
        viewModelScope.launch {
            try {
                cashDrawerRepository.addCashSale(amountCents, orderId)
            } catch (e: Exception) {
                Log.d("💰", "Cash drawer not active or error: ${e.message}")
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
            it.type is com.avoqado.pos.pos.data.model.CartItemType.ProductItem
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
            it.type is com.avoqado.pos.pos.data.model.CartItemType.ProductItem
        }
        if (realItems.isEmpty()) return

        val orderNumber = createdOrderId?.takeLast(4) ?: "---"

        viewModelScope.launch {
            // Auto-print receipt
            val receipt = ReceiptData(
                orderNumber = orderNumber,
                orderType = "En tienda",
                items = realItems.map { item ->
                    ReceiptItem(
                        name = item.name,
                        quantity = item.quantity,
                        unitPrice = item.effectiveUnitPrice,
                        totalPrice = item.effectiveUnitPrice * item.quantity,
                        modifiers = item.selectedModifiers.map { it.modifierName }.ifEmpty { null },
                        note = item.itemNote,
                        isCortesia = item.isCortesia,
                    )
                },
                subtotal = cart.subtotalCents,
                taxAmount = 0, // Tax included in item prices
                tipAmount = if (currentTipCents > 0) currentTipCents else null,
                discountAmount = if (cart.discountCents > 0) cart.discountCents else null,
                total = cart.totalCents + currentTipCents,
                paymentMethod = when (method) {
                    PaymentMethod.CASH -> "Efectivo"
                    PaymentMethod.CARD -> "Tarjeta"
                },
                venueName = secureStorage.venueName ?: "Avoqado",
                cashTendered = if (method == PaymentMethod.CASH) changeCents?.let { cart.totalCents + currentTipCents + it } else null,
                changeAmount = changeCents,
            )
            // Cache for manual reprint from the success screen
            lastReceipt = receipt
            printerService.autoPrintReceipt(receipt)

            // Auto-print kitchen ticket
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

    private fun buildOrderRequest(cart: CartState): CreateOrderRequest {
        // Only include real products — custom amounts are handled separately
        val items = cart.items
            .filter { it.type is com.avoqado.pos.pos.data.model.CartItemType.ProductItem }
            .map { item ->
                OrderItemRequest(
                    productId = (item.type as com.avoqado.pos.pos.data.model.CartItemType.ProductItem).productId,
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
        )
    }

    private fun currentBaseAmount(): Int {
        return splitBaseAmountOverride ?: (cartState?.totalCents ?: 0)
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
