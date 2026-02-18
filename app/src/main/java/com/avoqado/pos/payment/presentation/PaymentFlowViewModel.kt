package com.avoqado.pos.payment.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OrderRepository
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
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentFlowState>(PaymentFlowState.Loading)
    val state: StateFlow<PaymentFlowState> = _state.asStateFlow()

    private var cartState: CartState? = null
    private var selectedMethod: PaymentMethod? = null
    private var currentRating: Int? = null
    private var currentTipCents: Int = 0
    private var createdOrderId: String? = null

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
                _state.value = PaymentFlowState.Confirming(
                    amount = baseAmount,
                    tip = currentTipCents,
                    rating = currentRating,
                )
            }
        }
    }

    fun confirmPayment() {
        val cart = cartState ?: return
        val total = cart.totalCents + currentTipCents
        _state.value = PaymentFlowState.Processing(total)

        viewModelScope.launch {
            try {
                // Create order
                val orderRequest = buildOrderRequest(cart)
                val orderResult = orderRepository.createOrder(orderRequest)

                orderResult.fold(
                    onSuccess = { response ->
                        createdOrderId = response.data?.id

                        when (selectedMethod) {
                            PaymentMethod.CARD -> {
                                // Send to terminal
                                _state.value = PaymentFlowState.SentToTerminal(total)
                                val terminalResult = terminalPaymentService.sendPaymentToTerminal(
                                    orderId = response.data?.id ?: "",
                                    amountCents = total,
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
                    },
                    onFailure = { error ->
                        _state.value = PaymentFlowState.Error(
                            message = error.message ?: "Error al crear la orden",
                            source = PaymentErrorSource.SERVER,
                        )
                    },
                )
            } catch (e: Exception) {
                Log.e("💰", "Payment error: ${e.message}")
                _state.value = PaymentFlowState.Error(
                    message = e.message ?: "Error inesperado",
                    source = PaymentErrorSource.UNKNOWN,
                )
            }
        }
    }

    fun processCashPayment(cashReceivedCents: Int) {
        val total = (cartState?.totalCents ?: 0) + currentTipCents

        when (val result = cashPaymentRepository.processCashPayment(total, cashReceivedCents)) {
            is CashPaymentResult.Success -> {
                // Create order then show success
                viewModelScope.launch {
                    _state.value = PaymentFlowState.Processing(total)
                    val orderRequest = buildOrderRequest(cartState!!)
                    orderRepository.createOrder(orderRequest)
                    _state.value = PaymentFlowState.Success(
                        totalAmount = total,
                        method = PaymentMethod.CASH,
                        changeAmount = result.changeCents,
                    )
                }
            }
            is CashPaymentResult.InsufficientFunds -> {
                // Stay on cash screen — insufficient funds handled in UI
            }
        }
    }

    fun retry() {
        cartState?.let { startPaymentFlow(it) }
    }

    fun cancel() {
        createdOrderId?.let { orderId ->
            viewModelScope.launch {
                orderRepository.cancelOrder(orderId)
            }
        }
    }

    private fun buildOrderRequest(cart: CartState): CreateOrderRequest {
        val items = cart.items.map { item ->
            OrderItemRequest(
                productId = when (item.type) {
                    is com.avoqado.pos.pos.data.model.CartItemType.ProductItem ->
                        (item.type as com.avoqado.pos.pos.data.model.CartItemType.ProductItem).productId
                    is com.avoqado.pos.pos.data.model.CartItemType.CustomAmount ->
                        "custom_amount"
                },
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
