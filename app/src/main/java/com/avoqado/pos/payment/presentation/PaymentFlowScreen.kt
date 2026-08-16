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
import com.avoqado.pos.designsystem.components.AvoqadoWarningToast
import com.avoqado.pos.customers.presentation.CreateCustomerView
import com.avoqado.pos.customers.presentation.CustomersView
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.pos.presentation.cart.CartState

@Composable
fun PaymentFlowScreen(
    cartState: CartState,
    onPaymentCommitted: (PaymentCompletion) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Se resolvió un cobro pendiente de OTRA venta: cierra este flujo y muestra el mensaje
     * en la pantalla que queda. Ver [PaymentFlowViewModel.previousChargeResolved].
     */
    onPreviousChargeResolved: (String) -> Unit = { onCancel() },
    splitConfig: SplitConfig = SplitConfig(),
    /** Mesas (Square): link "Dividir importe" en la selección de método. */
    onSplitImporte: (() -> Unit)? = null,
    /**
     * Cliente que el cajero ya eligió ANTES de cobrar (encabezado del carrito o
     * cliente del cheque). Se lo lleva la orden y se muestra ya puesto en la
     * pantalla de recibo, en vez de pedirlo otra vez.
     */
    preselectedCustomerId: String? = null,
    preselectedCustomerName: String? = null,
    viewModel: PaymentFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Doble pantalla: cambia QUIÉN captura propina y calificación.
    val customerDisplayActive by viewModel.customerDisplayActive.collectAsState()
    val terminalAvailability by viewModel.terminalAvailability.collectAsState()
    val terminals by viewModel.onlineTerminals.collectAsState()
    val paymentContext = viewModel.buildPaymentContext()
    val customersViewModel: CustomersViewModel = hiltViewModel()
    val customerAttachSending by viewModel.customerAttachSending.collectAsState()
    val customerAttachResult by viewModel.customerAttachResult.collectAsState()
    // Una sola fuente para el nombre: la del ViewModel. Antes esta pantalla
    // guardaba el suyo aparte, arrancando siempre en null — de ahí que el
    // cliente del carrito no apareciera al terminar el cobro.
    val attachedCustomerName by viewModel.attachedCustomerName.collectAsState()
    var showCustomersSheet by rememberSaveable { mutableStateOf(false) }
    var showCreateCustomer by rememberSaveable { mutableStateOf(false) }
    var createCustomerSearchText by rememberSaveable { mutableStateOf("") }

    fun attachCustomer(customer: Customer) {
        showCustomersSheet = false
        showCreateCustomer = false
        viewModel.attachCustomerToCurrentPayment(customer.id, customer.fullName)
    }

    LaunchedEffect(cartState, splitConfig, preselectedCustomerId, preselectedCustomerName) {
        viewModel.setSplitConfig(
            type = splitConfig.type.toApiSplitType(),
            selectedItemIds = splitConfig.selectedItemIds,
            numberOfParts = splitConfig.numberOfParts,
            customAmountCents = splitConfig.customAmountCents,
        )
        viewModel.startPaymentFlow(
            cart = cartState,
            customerId = preselectedCustomerId,
            customerName = preselectedCustomerName,
        )
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
                // 🔴 Con pantalla de cliente, las estrellas son SUYAS: el cajero
                // solo espera y no puede calificarse a sí mismo.
                if (customerDisplayActive) {
                    WaitingForCustomerScreen(
                        title = "El cliente está calificando",
                        subtitle = "Pídele que use la pantalla frente a él",
                        skipLabel = "Continuar sin calificación",
                        onSkip = { viewModel.submitRating(null) },
                        onCancel = onCancel,
                    )
                } else {
                    RatingScreen(
                        onRatingSubmitted = { viewModel.submitRating(it) },
                        onSkip = { viewModel.submitRating(null) },
                        onCancel = onCancel,
                    )
                }
            }
            is PaymentFlowState.CollectingTip -> {
                // 🔴 Misma regla que las estrellas: con pantalla de cliente la
                // propina la elige ÉL. Y se muestra SOLA — dejar el método de
                // pago debajo hacía que la espera se encimara sobre el desglose
                // (ilegible) y además invitaba al cajero a saltarse el paso.
                if (customerDisplayActive) {
                    WaitingForCustomerScreen(
                        title = "El cliente está eligiendo propina",
                        subtitle = "Pídele que use la pantalla frente a él",
                        skipLabel = "Continuar sin propina",
                        onSkip = { viewModel.submitTip(0) },
                        onCancel = onCancel,
                    )
                } else {
                    // Sin pantalla de cliente: método de pago con la hoja de
                    // propina encima, como siempre.
                    PaymentMethodSelectionScreen(
                        paymentContext = paymentContext.copy(totalCents = currentState.amount),
                        onMethodSelected = { viewModel.selectPaymentMethod(it) },
                        onCashPresetSelected = { viewModel.confirmCashPreset(it) },
                        onCashCustomSelected = { viewModel.confirmCashCustom(it) },
                        onManualMethodSelected = { viewModel.confirmManualMethod(it) },
                        onCancel = onCancel,
                        terminalsUnavailable = terminalAvailability == PaymentFlowViewModel.TerminalAvailability.NONE,
                        // Toque explícito del cajero: si el server dice que no, tiene que verse.
                        onRetryTerminals = { viewModel.probeTerminalAvailability(background = false) },
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
            }
            is PaymentFlowState.SelectingPaymentMethod -> {
                PaymentMethodSelectionScreen(
                    paymentContext = paymentContext.copy(totalCents = currentState.amount),
                    onMethodSelected = { viewModel.selectPaymentMethod(it) },
                    onCashPresetSelected = { viewModel.confirmCashPreset(it) },
                    onCashCustomSelected = { viewModel.confirmCashCustom(it) },
                        onManualMethodSelected = { viewModel.confirmManualMethod(it) },
                    onCancel = onCancel,
                    terminalsUnavailable = terminalAvailability == PaymentFlowViewModel.TerminalAvailability.NONE,
                    // Toque explícito del cajero: si el server dice que no, tiene que verse.
                    onRetryTerminals = { viewModel.probeTerminalAvailability(background = false) },
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
                // El dinero ya se comprometió. Consumir el carrito ahora mantiene la
                // pantalla de recibo abierta, pero hace imposible volver a cobrar la
                // misma venta si imprimir falla o el operador sale por otra ruta.
                LaunchedEffect(currentState) {
                    viewModel.consumeCompletion()?.let(onPaymentCommitted)
                }
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
                        methodLabel = viewModel.manualMethodLabel,
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
                        customerName = if (customerAttachSending) "Agregando..." else attachedCustomerName,
                        customerResultMessage = customerAttachResult,
                        onClearCustomerResult = { viewModel.clearCustomerAttachResult() },
                        onAddCustomer = {
                            viewModel.clearCustomerAttachResult()
                            showCustomersSheet = true
                        },
                        onDone = {
                            // También cerrar la carrera de un toque inmediato contra el
                            // LaunchedEffect: exactamente uno de los dos consume el pago.
                            viewModel.consumeCompletion()?.let(onPaymentCommitted)
                            onDone()
                        },
                    )

                    // Aviso de inventario post-cobro (Square-parity, mockup ②): el cobro
                    // YA quedó registrado; el server avisa que el stock quedó en negativo
                    // o no se pudo descontar. Ámbar, nunca bloquea ni sugiere reintentar.
                    var inventoryWarningShown by rememberSaveable(splashKey) { mutableStateOf(false) }
                    if (!inventoryWarningShown) {
                        currentState.inventoryWarningMessage?.let { aviso ->
                            AvoqadoWarningToast(
                                message = "Revisa tu inventario",
                                subtitle = aviso,
                                onDismiss = { inventoryWarningShown = true },
                            )
                        }
                    }
                }
            }
            is PaymentFlowState.Error -> {
                PaymentErrorView(
                    message = currentState.message,
                    onRetry = { viewModel.retry() },
                    // Cancelar aquí ESPERA al server: si rechaza (409, la orden ya está
                    // pagada), no se sale — se muestra el motivo.
                    onCancel = { viewModel.cancelAndExit(onCancel) },
                )
            }
            // 🔴 Ni éxito ni fracaso: no se sabe si la tarjeta se cobró. Pantalla propia,
            // sin Reintentar a ciegas — ver PaymentUndeterminedView.
            is PaymentFlowState.Undetermined -> {
                PaymentUndeterminedView(
                    message = currentState.message,
                    isChecking = currentState.checking,
                    fromPreviousSale = currentState.fromPreviousSale,
                    onRecheck = { viewModel.recheckCardCharge() },
                    onChargeAgain = { viewModel.chargeAgainDespiteUndetermined() },
                    onCancel = { viewModel.cancelAndExit(onCancel) },
                )
            }
        }
    }

    // Se resolvió un cobro de OTRA venta: este flujo ya cumplió su único propósito, así que
    // se cierra y el mensaje lo pinta el checkout — que es quien se queda en pantalla.
    val previousChargeResolved by viewModel.previousChargeResolved.collectAsState()
    LaunchedEffect(previousChargeResolved) {
        previousChargeResolved?.let { message ->
            viewModel.clearPreviousChargeResolved()
            onPreviousChargeResolved(message)
        }
    }

    // El server rechazó la cancelación: la orden sigue abierta y el cajero tiene que saberlo.
    val cancelFailure by viewModel.cancelFailure.collectAsState()
    cancelFailure?.let { failure ->
        com.avoqado.pos.designsystem.components.AvoqadoErrorToast(
            message = failure,
            onDismiss = { viewModel.clearCancelFailure() },
        )
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
