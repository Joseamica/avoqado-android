package com.avoqado.pos.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.presentation.CreateCustomerView
import com.avoqado.pos.customers.presentation.CustomersView
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.pos.presentation.cart.CartState

@Composable
fun PaymentFlowScreen(
    cartState: CartState,
    onComplete: (PaymentCompletion) -> Unit,
    onCancel: () -> Unit,
    splitConfig: SplitConfig = SplitConfig(),
    /** Mesas (Square): link "Dividir importe" en la selección de método. */
    onSplitImporte: (() -> Unit)? = null,
    viewModel: PaymentFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val terminalAvailability by viewModel.terminalAvailability.collectAsState()
    val terminals by viewModel.onlineTerminals.collectAsState()
    val paymentContext = viewModel.buildPaymentContext()
    val customersViewModel: CustomersViewModel = hiltViewModel()
    val customerAttachSending by viewModel.customerAttachSending.collectAsState()
    val customerAttachResult by viewModel.customerAttachResult.collectAsState()
    var selectedPaymentCustomerName by rememberSaveable { mutableStateOf<String?>(null) }
    var showCustomersSheet by rememberSaveable { mutableStateOf(false) }
    var showCreateCustomer by rememberSaveable { mutableStateOf(false) }
    var createCustomerSearchText by rememberSaveable { mutableStateOf("") }

    fun attachCustomer(customer: Customer) {
        selectedPaymentCustomerName = customer.fullName
        showCustomersSheet = false
        showCreateCustomer = false
        viewModel.attachCustomerToCurrentPayment(customer.id, customer.fullName)
    }

    LaunchedEffect(cartState, splitConfig) {
        viewModel.setSplitConfig(
            type = splitConfig.type.toApiSplitType(),
            selectedItemIds = splitConfig.selectedItemIds,
            numberOfParts = splitConfig.numberOfParts,
            customAmountCents = splitConfig.customAmountCents,
        )
        viewModel.startPaymentFlow(cartState)
    }

    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
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
                    terminalsUnavailable = terminalAvailability == PaymentFlowViewModel.TerminalAvailability.NONE,
                    onRetryTerminals = { viewModel.probeTerminalAvailability() },
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
                    terminalsUnavailable = terminalAvailability == PaymentFlowViewModel.TerminalAvailability.NONE,
                    onRetryTerminals = { viewModel.probeTerminalAvailability() },
                    onSplitImporte = onSplitImporte,
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
                val splashKey = currentState.paymentId
                    ?: "${currentState.totalAmount}-${currentState.method}-${currentState.isQueued}"
                var splashDone by rememberSaveable(splashKey) { mutableStateOf(false) }

                if (!splashDone) {
                    PaymentSuccessSplashScreen(onFinished = { splashDone = true })
                } else {
                    val whatsAppSending by viewModel.whatsAppSending.collectAsState()
                    val whatsAppResult by viewModel.whatsAppResult.collectAsState()
                    val emailSending by viewModel.emailSending.collectAsState()
                    val emailResult by viewModel.emailResult.collectAsState()
                    val printSending by viewModel.printSending.collectAsState()
                    val printResult by viewModel.printResult.collectAsState()
                    val canPrintOnTerminal by viewModel.canPrintOnTerminal.collectAsState()

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
                        canPrintOnTerminal = canPrintOnTerminal,
                        onPrintOnTerminalReceipt = { viewModel.printReceiptOnTerminal() },
                        onClearPrintResult = { viewModel.clearPrintResult() },
                        customerName = if (customerAttachSending) "Agregando..." else selectedPaymentCustomerName,
                        customerResultMessage = customerAttachResult,
                        onClearCustomerResult = { viewModel.clearCustomerAttachResult() },
                        onAddCustomer = {
                            viewModel.clearCustomerAttachResult()
                            showCustomersSheet = true
                        },
                        onDone = { onComplete(viewModel.buildCompletion()) },
                    )
                }
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

    if (showCustomersSheet) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            if (showCreateCustomer) {
                CreateCustomerView(
                    viewModel = customersViewModel,
                    initialPhone = createCustomerSearchText.takeIf { it.all { c -> c.isDigit() || c == '+' } },
                    initialName = createCustomerSearchText.takeIf { !it.all { c -> c.isDigit() || c == '+' } },
                    onCustomerCreated = { customer ->
                        attachCustomer(customer)
                    },
                    onBack = { showCreateCustomer = false },
                )
            } else {
                CustomersView(
                    viewModel = customersViewModel,
                    onCustomerSelected = { customer ->
                        attachCustomer(customer)
                    },
                    onDismiss = {
                        showCustomersSheet = false
                        showCreateCustomer = false
                    },
                    onCreateCustomer = { searchText ->
                        createCustomerSearchText = searchText
                        showCreateCustomer = true
                    },
                )
            }
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
