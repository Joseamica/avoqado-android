package com.avoqado.pos.payment.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.avoqado.pos.payment.data.model.PaymentErrorSource
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.pos.presentation.cart.CartState
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

    val settings: TpvSettings get() = tpvSettingsRepository.getCurrentSettings()

    fun startPaymentFlow(cart: CartState) {
        cartState = cart
        val amount = cart.totalCents

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
        val amount = cartState?.totalCents ?: return

        if (settings.showTipScreen) {
            _state.value = PaymentFlowState.CollectingTip(amount, rating)
        } else {
            _state.value = PaymentFlowState.SelectingPaymentMethod(amount)
        }
    }

    fun submitTip(tipCents: Int) {
        currentTipCents = tipCents
        val amount = cartState?.totalCents ?: return

        _state.value = PaymentFlowState.SelectingPaymentMethod(amount + tipCents)
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        selectedMethod = method
        val baseAmount = cartState?.totalCents ?: return
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
        val total = cart.totalCents + currentTipCents
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
                                    )
                                    _state.value = PaymentFlowState.Success(
                                        totalAmount = total,
                                        method = PaymentMethod.CASH,
                                        changeAmount = 0,
                                        isQueued = true,
                                    )
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
                    // Custom amount only — process payment directly without server order
                    Log.d("💰", "Custom amount payment - skipping order creation, total: $total")
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
                    amountCents = cartState?.totalCents ?: total,
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
                _state.value = PaymentFlowState.Success(
                    totalAmount = total,
                    method = PaymentMethod.CASH,
                )
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
        val total = (cartState?.totalCents ?: 0) + currentTipCents

        when (val result = cashPaymentRepository.processCashPayment(total, cashReceivedCents)) {
            is CashPaymentResult.Success -> {
                viewModelScope.launch {
                    _state.value = PaymentFlowState.Processing(total)
                    val orderRequest = buildOrderRequest(cartState!!)

                    val orderResult = orderRepository.createOrder(orderRequest)
                    orderResult.fold(
                        onSuccess = {
                            createdOrderId = it.data?.id
                            _state.value = PaymentFlowState.Success(
                                totalAmount = total,
                                method = PaymentMethod.CASH,
                                changeAmount = result.changeCents,
                            )
                        },
                        onFailure = { error ->
                            // Check if this is a queueable error (network/server)
                            val isQueueable = OrderRepository.isQueueableError(error) ||
                                (error is OrderRepository.ServerException && OrderRepository.isQueueableHttpCode(error.code))

                            if (isQueueable) {
                                // Queue for offline sync
                                cashPaymentRepository.queueCashPayment(
                                    orderRequest = orderRequest,
                                    cashTenderedCents = cashReceivedCents,
                                    changeCents = result.changeCents,
                                    rating = currentRating,
                                )
                                _state.value = PaymentFlowState.Success(
                                    totalAmount = total,
                                    method = PaymentMethod.CASH,
                                    changeAmount = result.changeCents,
                                    isQueued = true,
                                )
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
            }
            is CashPaymentResult.InsufficientFunds -> {
                // Stay on cash screen — insufficient funds handled in UI
            }
        }
    }

    fun retry() {
        val baseAmount = cartState?.totalCents ?: return
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
        )
    }
}
