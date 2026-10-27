package com.avoqado.pos.transactions.presentation.exchange

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.FullscreenHeaderNav
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.payment.presentation.PaymentProcessingView
import com.avoqado.pos.payment.presentation.PaymentErrorView
import com.avoqado.pos.payment.presentation.PaymentUndeterminedView
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun ExchangeScreen(viewModel: ExchangeViewModel, onClose: () -> Unit,
    payments: ExchangePaymentViewModel = androidx.hilt.navigation.compose.hiltViewModel(), onStartAgain: () -> Unit = onClose) {
    val state by viewModel.state.collectAsState()
    val send by payments.state.collectAsState()
    val ready by payments.ready.collectAsState()
    val busy by payments.busy.collectAsState()
    val message by payments.message.collectAsState()
    val draft by payments.draft.collectAsState()
    val terminals by payments.terminals.collectAsState()
    LaunchedEffect(state.transaction?.id) { state.transaction?.let { payments.open(it) } }
    LaunchedEffect(send.locked, ready, busy, draft) { viewModel.lockSubmission(send.locked || !ready || busy || draft != null) }
    LaunchedEffect(send.calculation) { send.calculation?.let(viewModel::applyServerCalculation) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(send.phase) { if (send.phase == ExchangeSendPhase.COMPLETED) successMessage = TextosCambio.CAMBIO_HECHO }
    LaunchedEffect(message) { if (message == TextosCambio.TICKET_IMPRESO) successMessage = message }
    var showCart by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding().navigationBarsPadding()) {
        val isTablet = maxWidth >= 600.dp
        BackHandler { if (!busy) { if (!isTablet && showCart) showCart = false else onClose() } }
        Column(Modifier.fillMaxSize()) {
            AvoqadoFullscreenHeader(
                title = TextosCambio.TITULO.replace("{folio}", state.transaction?.orderNumber.orEmpty())
                    .replace("{cuando}", state.transaction?.dateGroup.orEmpty()),
                onNav = { if (!isTablet && showCart) showCart = false else onClose() },
                navEnabled = !busy,
                navStyle = if (!isTablet && showCart) FullscreenHeaderNav.BACK else FullscreenHeaderNav.CLOSE,
                backContentDescription = TextosCambio.REGRESAR,
                showDivider = true,
            )
            if (!ready || busy || send.locked) {
                Box(Modifier.weight(1f)) {
                    when {
                        busy || !ready -> PaymentProcessingView(esperandoTerminal = send.phase == ExchangeSendPhase.PROCESSING_CARD,
                            onCancel = onClose, title = TextosCambio.IMPRIMIENDO.takeIf { send.phase == ExchangeSendPhase.COMPLETED })
                        send.phase == ExchangeSendPhase.CANCELLED -> PaymentErrorView(
                            message = message ?: send.message.orEmpty(), onRetry = onStartAgain, onCancel = onClose,
                            retryLabel = TextosCambio.PROBAR_OTRA_VEZ, cancelLabel = TextosCambio.CERRAR, titleLabel = TextosCambio.CANCELADO)
                        send.phase == ExchangeSendPhase.UNCERTAIN && send.result != null -> PaymentUndeterminedView(
                            message = message ?: send.message.orEmpty(), isChecking = false, onRecheck = payments::retry,
                            onChargeAgain = {}, onSalir = onClose, recheckLabel = TextosCambio.CONFIRMAR_ESTADO)
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md)) {
                            Text(message ?: send.message.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            if (send.phase == ExchangeSendPhase.COMPLETED) PrimaryButton(TextosCambio.IMPRIMIR, payments::print, fullWidth = true)
                            else send.action?.let { PrimaryButton(it, payments::retry, fullWidth = true) }
                            PrimaryButton(TextosCambio.CERRAR, onClose, fullWidth = true)
                        }
                    }
                }
            } else if (isTablet) {
                Row(Modifier.weight(1f)) {
                    ExchangeTakenPicker(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExchangeCart(state, viewModel, Modifier.weight(1f).fillMaxHeight()) { viewModel.prepareExchange()?.let(payments::begin) }
                }
            } else if (showCart) {
                ExchangeCart(state, viewModel, Modifier.weight(1f)) { viewModel.prepareExchange()?.let(payments::begin) }
            } else {
                ExchangeTakenPicker(state, viewModel, Modifier.weight(1f))
                Box(Modifier.padding(AvoqadoTheme.spacing.md).fillMaxWidth()
                    .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                    .clickable { showCart = true }.padding(AvoqadoTheme.spacing.lg), contentAlignment = Alignment.Center) {
                    Text(if (state.calculation.takenPieces.size == state.taken.size)
                        TextosCambio.VER_CAMBIO.replace("{precio}", exchangeMoney(state.calculation.differenceCents))
                        else TextosCambio.CAMBIO,
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            if (!send.locked && !busy) (message ?: send.message)?.let {
                Text(it, Modifier.padding(AvoqadoTheme.spacing.md), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (draft != null && !send.locked) {
        AvoqadoDialog(title = if (terminals.isNotEmpty()) TextosCambio.EN_CUAL_TERMINAL
            else if (draft!!.settlement.kind == "PAYOUT") TextosCambio.ELIGE_DEVOLUCION else TextosCambio.ELIGE_METODO,
            description = exchangeBar(draft!!.settlement.expectedDifferenceCents).label,
            onDismiss = payments::dismissChoice) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                if (terminals.isNotEmpty()) terminals.forEach { terminal ->
                    PrimaryButton(terminal.name, { payments.chooseTerminal(terminal.terminalId) }, enabled = !busy, fullWidth = true)
                } else payments.options(draft!!).forEach { option ->
                    if (option.requiresPin) Text(TextosCambio.PIN_EFECTIVO, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton(option.label, { payments.choose(option) }, enabled = !busy && option.disabledReason == null, fullWidth = true)
                    option.disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    successMessage?.let { AvoqadoSuccessToast(it, onDismiss = { successMessage = null }) }
}

@Composable
private fun ExchangeCart(state: ExchangeUiState, viewModel: ExchangeViewModel, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            ExchangeReturnedList(state, viewModel)
            Text(TextosCambio.SE_LLEVA, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.taken.forEachIndexed { index, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.product.name, style = MaterialTheme.typography.bodyLarge)
                        state.calculation.takenPieces.getOrNull(index)?.let { piece ->
                            Text("+" + exchangeMoney(piece.priceCents), style = MaterialTheme.typography.titleMedium)
                        }
                        viewModel.inheritedLabel(index)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = { viewModel.removeTaken(index) }) {
                        Icon(Icons.Filled.Close, TextosCambio.QUITAR.replace("{producto}", row.product.name))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ExchangeBottomBar(state, onConfirm = onConfirm)
    }
}

@Composable
fun ExchangeRecoveryNotice(transaction: com.avoqado.pos.transactions.data.model.Transaction, onResume: () -> Unit) {
    val payments: ExchangePaymentViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "exchange-recovery")
    val send by payments.state.collectAsState()
    val ready by payments.ready.collectAsState()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, transaction) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) payments.open(transaction, force = true)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(transaction) { payments.open(transaction, force = true) }
    if (ready && send.locked) {
        Column(Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            Text(send.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(TextosCambio.CONFIRMAR_ESTADO, onResume, fullWidth = true)
        }
    }
}
