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
    onComplete: (PaymentCompletion) -> Unit,
    onCancel: () -> Unit,
    splitConfig: SplitConfig = SplitConfig(),
    viewModel: PaymentFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val terminals by viewModel.onlineTerminals.collectAsState()
    val paymentContext = viewModel.buildPaymentContext()

    LaunchedEffect(cartState, splitConfig) {
        viewModel.setSplitConfig(
            type = splitConfig.type.toApiSplitType(),
            selectedItemIds = splitConfig.selectedItemIds,
            numberOfParts = splitConfig.numberOfParts,
            customAmountCents = splitConfig.customAmountCents,
        )
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
                paymentContext = paymentContext.copy(totalCents = currentState.amount),
                onMethodSelected = { viewModel.selectPaymentMethod(it) },
                onCashPresetSelected = { viewModel.confirmCashPreset(it) },
                onCashCustomSelected = { viewModel.confirmCashCustom(it) },
                onCancel = onCancel,
            )
            TipSelectionSheet(
                amountCents = currentState.amount,
                tipBaseCents = viewModel.currentTipPercentageBaseCents(),
                tipSuggestions = viewModel.settings.tipSuggestions,
                onTipSelected = { viewModel.submitTip(it) },
                onSkip = { viewModel.submitTip(0) },
                onDismiss = { viewModel.submitTip(0) },
            )
        }
        is PaymentFlowState.SelectingPaymentMethod -> {
            PaymentMethodSelectionScreen(
                paymentContext = paymentContext.copy(totalCents = currentState.amount),
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
            val whatsAppSending by viewModel.whatsAppSending.collectAsState()
            val whatsAppResult by viewModel.whatsAppResult.collectAsState()
            val emailSending by viewModel.emailSending.collectAsState()
            val emailResult by viewModel.emailResult.collectAsState()
            val printSending by viewModel.printSending.collectAsState()
            val printResult by viewModel.printResult.collectAsState()

            PaymentResultScreen(
                totalCents = currentState.totalAmount,
                method = currentState.method,
                changeCents = currentState.changeAmount,
                isQueued = currentState.isQueued,
                paymentId = currentState.paymentId,
                canSendReceipt = !currentState.paymentId.isNullOrBlank() || !currentState.receiptAccessKey.isNullOrBlank(),
                isSendingWhatsApp = whatsAppSending,
                whatsAppResultMessage = whatsAppResult,
                onSendWhatsApp = { phone -> viewModel.sendReceiptWhatsApp(phone) },
                onClearWhatsAppResult = { viewModel.clearWhatsAppResult() },
                isSendingEmail = emailSending,
                emailResultMessage = emailResult,
                onSendEmail = { email -> viewModel.sendReceiptEmail(email) },
                onClearEmailResult = { viewModel.clearEmailResult() },
                isPrintingReceipt = printSending,
                printResultMessage = printResult,
                onPrintReceipt = { viewModel.reprintReceipt() },
                onClearPrintResult = { viewModel.clearPrintResult() },
                onDone = { onComplete(viewModel.buildCompletion()) },
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

private fun SplitType.toApiSplitType(): String {
    return when (this) {
        SplitType.FULL_PAYMENT -> "FULLPAYMENT"
        SplitType.BY_PRODUCT -> "BYPRODUCT"
        SplitType.EQUAL_PARTS -> "EQUALPARTS"
        SplitType.CUSTOM_AMOUNT -> "CUSTOMAMOUNT"
    }
}
