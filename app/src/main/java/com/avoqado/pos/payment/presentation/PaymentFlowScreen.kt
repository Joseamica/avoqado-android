package com.avoqado.pos.payment.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.pos.presentation.cart.CartState

@Composable
fun PaymentFlowScreen(
    cartState: CartState,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PaymentFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val terminals by viewModel.onlineTerminals.collectAsState()

    LaunchedEffect(cartState) {
        viewModel.startPaymentFlow(cartState)
    }

    when (val currentState = state) {
        is PaymentFlowState.Loading -> {
            PaymentLoadingView()
        }
        is PaymentFlowState.CollectingRating -> {
            RatingScreen(
                onRatingSubmitted = { viewModel.submitRating(it) },
                onSkip = { viewModel.submitRating(null) },
            )
        }
        is PaymentFlowState.CollectingTip -> {
            // Show payment method screen underneath with tip sheet overlay (matching iOS)
            PaymentMethodSelectionScreen(
                amountCents = currentState.amount,
                onMethodSelected = { viewModel.selectPaymentMethod(it) },
                onCashPresetSelected = { viewModel.confirmCashPreset(it) },
                onCashCustomSelected = { viewModel.confirmCashCustom(it) },
                onCancel = onCancel,
            )
            TipSelectionSheet(
                amountCents = currentState.amount,
                tipSuggestions = viewModel.settings.tipSuggestions,
                onTipSelected = { viewModel.submitTip(it) },
                onSkip = { viewModel.submitTip(0) },
                onDismiss = { viewModel.submitTip(0) },
            )
        }
        is PaymentFlowState.SelectingPaymentMethod -> {
            PaymentMethodSelectionScreen(
                amountCents = currentState.amount,
                onMethodSelected = { viewModel.selectPaymentMethod(it) },
                onCashPresetSelected = { viewModel.confirmCashPreset(it) },
                onCashCustomSelected = { viewModel.confirmCashCustom(it) },
                onCancel = onCancel,
            )
        }
        is PaymentFlowState.CollectingCashAmount -> {
            // Legacy full-screen cash (kept for backward compat, rarely reached)
            CashPaymentScreen(
                totalCents = currentState.total,
                onCashReceived = { viewModel.processCashPayment(it) },
                onCancel = onCancel,
            )
        }
        is PaymentFlowState.Confirming -> {
            PaymentConfirmScreen(
                subtotalCents = currentState.amount,
                tipCents = currentState.tip,
                totalCents = currentState.amount + currentState.tip,
                rating = currentState.rating,
                onConfirm = { viewModel.confirmPayment() },
                onCancel = onCancel,
            )
        }
        is PaymentFlowState.SelectingTerminal -> {
            TerminalSelectionScreen(
                terminals = terminals,
                onTerminalSelected = { viewModel.selectTerminalAndPay(it) },
                onCancel = onCancel,
            )
        }
        is PaymentFlowState.Processing, is PaymentFlowState.SentToTerminal -> {
            PaymentProcessingView(
                onCancel = {
                    viewModel.cancel()
                    onCancel()
                },
            )
        }
        is PaymentFlowState.Success -> {
            PaymentResultScreen(
                totalCents = currentState.totalAmount,
                method = currentState.method,
                changeCents = currentState.changeAmount,
                isQueued = currentState.isQueued,
                onDone = onComplete,
            )
        }
        is PaymentFlowState.Error -> {
            PaymentErrorView(
                message = currentState.message,
                onRetry = { viewModel.retry() },
                onCancel = {
                    viewModel.cancel()
                    onCancel()
                },
            )
        }
    }
}
