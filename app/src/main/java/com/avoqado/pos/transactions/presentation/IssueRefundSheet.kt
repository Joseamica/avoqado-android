package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.avoqado.pos.designsystem.theme.Warning
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.AssociatedRefundItem
import com.avoqado.pos.transactions.data.RefundApiException
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.model.RefundAmountCalculator
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionItem
import kotlinx.coroutines.launch

private enum class RefundTab { ITEMS, AMOUNT }

private data class ReasonOption(val code: String, val label: String)

private val REASONS = listOf(
    ReasonOption("RETURNED_GOODS", "Productos devueltos"),
    ReasonOption("ACCIDENTAL_CHARGE", "Cargo accidental"),
    ReasonOption("CANCELLED_ORDER", "Pedido cancelado"),
    ReasonOption("FRAUDULENT_CHARGE", "Cargo fraudulento"),
    ReasonOption("OTHER", "Otro"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueRefundSheet(
    transaction: Transaction,
    maxRefundable: Double,
    refundRepository: RefundRepository,
    // 🔴 NO recibe el CashDrawerRepository, y es a propósito: esta pantalla no
    // tiene por qué saber que el cajón existe. El egreso del reembolso lo escribe
    // el SERVIDOR (ver el comentario en `onSuccess`); volver a pasar el repositorio
    // aquí es el primer paso para que alguien reinstale el doble descuento.
    terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService,
    onDismiss: () -> Unit,
    onRefunded: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    val corners = AvoqadoTheme.cornerRadius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var tab by remember(transaction.id) {
        mutableStateOf(if (transaction.items.isNotEmpty()) RefundTab.ITEMS else RefundTab.AMOUNT)
    }
    var selectedItemIds by remember(transaction.id) { mutableStateOf(emptySet<String>()) }
    var refundQtyByItem by remember(transaction.id) { mutableStateOf(emptyMap<String, Int>()) }
    var restockItemIds by remember(transaction.id) { mutableStateOf(emptySet<String>()) }
    var amountStr by remember(transaction.id) { mutableStateOf("") }
    var reason by remember(transaction.id) { mutableStateOf<ReasonOption?>(null) }
    var reasonMenuOpen by remember(transaction.id) { mutableStateOf(false) }
    var submitting by remember(transaction.id) { mutableStateOf(false) }
    var errorMsg by remember(transaction.id) { mutableStateOf<String?>(null) }

    // --- Abrir la devolución en una terminal física ---
    //
    // 🔴 Esto NO registra un reembolso en Avoqado: la TPV lo registra ella sola
    // cuando el dinero se mueve. Registrarlo también aquí lo contaría dos veces.
    var enviandoATerminal by remember(transaction.id) { mutableStateOf(false) }
    var terminalMsg by remember(transaction.id) { mutableStateOf<String?>(null) }
    var terminalAbierta by remember(transaction.id) { mutableStateOf(false) }
    var terminalesParaElegir by remember(transaction.id) {
        mutableStateOf<List<com.avoqado.pos.payment.data.OnlineTerminal>>(emptyList())
    }

    fun mandarATerminal(terminalId: String) {
        scope.launch {
            enviandoATerminal = true
            terminalMsg = null
            val resultado = terminalPaymentService.requestRefundOnTerminal(
                terminalId = terminalId,
                paymentId = transaction.id,
                reason = reason?.label,
            )
            enviandoATerminal = false
            resultado.fold(
                onSuccess = {
                    // 🔴 NO se cierra como "reembolsado": todavía no se devolvió
                    // nada. Se le dice al cajero exactamente qué pasó y qué falta,
                    // porque si esto se pintara como éxito daría por cerrada una
                    // devolución que sigue esperando a alguien en el aparato.
                    terminalAbierta = true
                },
                onFailure = { e ->
                    terminalMsg = e.message ?: "No se pudo abrir la devolución en la terminal."
                },
            )
        }
    }

    fun abrirEnTerminal() {
        scope.launch {
            enviandoATerminal = true
            terminalMsg = null
            when (val lista = terminalPaymentService.fetchOnlineTerminals()) {
                is com.avoqado.pos.payment.data.TerminalListResult.Error -> {
                    enviandoATerminal = false
                    terminalMsg = lista.message
                }
                is com.avoqado.pos.payment.data.TerminalListResult.Success -> {
                    enviandoATerminal = false
                    when (lista.terminals.size) {
                        0 -> terminalMsg = "No hay terminales conectadas. Abre la app en la terminal e inténtalo de nuevo."
                        1 -> mandarATerminal(lista.terminals.first().terminalId)
                        // Con varias, la elige una persona: mandar la devolución a
                        // la terminal equivocada la abre lejos de quien la espera.
                        else -> terminalesParaElegir = lista.terminals
                    }
                }
            }
        }
    }
    // Include-tip toggle for amount refunds on payments that had tip. Defaults
    // ON → backend uses proportional split. OFF → send tipRefundCents=0 so the
    // refund pulls 100% from the sale, leaving the staff tip intact.
    val paymentTipAmount = transaction.tipAmount
    var includeTip by remember(transaction.id) { mutableStateOf(true) }

    val parsedAmount = amountStr.replace(',', '.').toDoubleOrNull() ?: 0.0
    val selectedItems = transaction.items.filter { item ->
        item.id != null && selectedItemIds.contains(item.id)
    }
    val itemsAmount = RefundAmountCalculator.calculateSelectedAmount(
        items = transaction.items,
        selectedIds = selectedItemIds,
        refundQtyByItem = refundQtyByItem,
    )
    val restockableItems = selectedItems.filter { item ->
        item.id != null && item.trackInventory
    }

    val amountToRefund = if (tab == RefundTab.ITEMS) itemsAmount else parsedAmount
    val canSubmit = reason != null &&
        amountToRefund > 0 &&
        amountToRefund <= maxRefundable + 0.001 &&
        !submitting

    if (terminalesParaElegir.isNotEmpty()) {
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = "¿En cuál terminal?",
            description = "Ahí se abrirá la devolución para que la confirmen con la tarjeta.",
            onDismiss = { terminalesParaElegir = emptyList() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                terminalesParaElegir.forEach { terminal ->
                    PrimaryButton(
                        text = terminal.name.ifBlank { terminal.terminalId },
                        onClick = {
                            terminalesParaElegir = emptyList()
                            mandarATerminal(terminal.terminalId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        // Three-band layout so footer (amount summary + Reembolsar) stays
        // visible on small tablets (≈9"): fixed header + scrollable middle
        // (weight=1f) + fixed footer. Also capped width for landscape.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .heightIn(max = 720.dp)
                .padding(horizontal = spacing.xl),
        ) {
            // === FIXED HEADER ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.sm, bottom = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = onDismiss)
                Text(
                    text = "Emitir reembolso",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.size(36.dp))
            }

            // 🔴 Un cobro con TARJETA no se devuelve desde aquí.
            //
            // La devolución a la tarjeta la hace la TERMINAL, con su propia
            // función; no hay API para ello. Y el server registra TODO reembolso
            // como efectivo (`method: 'CASH'` forzado en refund.mobile.service).
            // Sin este aviso, un gerente devuelve un cobro con tarjeta desde el
            // POS, el sistema lo da por hecho, y el cliente no recibe nada —
            // además de sacar del cajón un dinero que nunca entró ahí.
            if (!transaction.method.equals("CASH", ignoreCase = true)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = spacing.md)
                        .clip(RoundedCornerShape(spacing.md))
                        .background(Warning.copy(alpha = 0.12f))
                        .padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = "Este cobro no fue en efectivo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "La devolución a la tarjeta se hace en la terminal. Puedes abrirla " +
                            "ahí desde aquí, y sólo falta que alguien la confirme con la tarjeta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(spacing.xs))
                    if (terminalAbierta) {
                        // Lo que el cajero necesita saber: qué pasó, qué falta, y
                        // que todavía NO se ha devuelto el dinero.
                        Text(
                            text = "Abierta en la terminal. Falta que la confirmen ahí con la " +
                                "tarjeta — hasta entonces no se ha devuelto nada. Cuando se " +
                                "haga, la venta se actualiza sola.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        PrimaryButton(
                            text = if (enviandoATerminal) "Abriendo…" else "Abrir en la terminal",
                            onClick = { abrirEnTerminal() },
                            enabled = !enviandoATerminal && !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    terminalMsg?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // 🔴 Aquí vivían dos pastillas ("En efectivo" / "En la terminal") que
                    // decidían si esta app mandaba el egreso al cajón. Ya no lo manda —lo
                    // manda el servidor— así que el control se quedó SIN EFECTO: tocarlo
                    // no cambiaba nada y la leyenda "Saldrá del efectivo de la caja" pasó a
                    // ser mentira. Un control muerto miente más que no tener control.
                    //
                    // Y el hueco es real, no se está escondiendo: el servidor decide con la
                    // semántica del cobro ORIGINAL, así que una venta con tarjeta devuelta
                    // en efectivo NO baja del cajón. Cerrarlo de verdad exige mandarle al
                    // servidor CÓMO se entregó el dinero (`issueAssociatedRefund` no lleva
                    // método) y que él lo honre: cambio de servidor + cliente, pendiente y
                    // anotado. Mientras tanto se dice en voz alta y con qué hacer, en vez de
                    // dejar que el cajero crea que ya quedó.
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = "Si le entregas efectivo de la caja, regístralo tú como retiro " +
                            "en Caja: el sistema no lo descuenta solo, porque el cobro no fue " +
                            "en efectivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                TabPill(
                    label = "Reembolsar artículos",
                    active = tab == RefundTab.ITEMS,
                    modifier = Modifier.weight(1f),
                    enabled = transaction.items.isNotEmpty(),
                    onClick = { tab = RefundTab.ITEMS },
                )
                TabPill(
                    label = "Reembolsos por importe",
                    active = tab == RefundTab.AMOUNT,
                    modifier = Modifier.weight(1f),
                    onClick = { tab = RefundTab.AMOUNT },
                )
            }

            // === SCROLLABLE MIDDLE — takes remaining vertical space ===
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
            when (tab) {
                RefundTab.ITEMS -> ItemsBody(
                    transaction = transaction,
                    selectedIds = selectedItemIds,
                    refundQty = refundQtyByItem,
                    restockIds = restockItemIds,
                    selectedAmount = itemsAmount,
                    maxRefundable = maxRefundable,
                    onToggle = { id ->
                        selectedItemIds = if (selectedItemIds.contains(id)) {
                            restockItemIds = restockItemIds - id
                            selectedItemIds - id
                        } else {
                            selectedItemIds + id
                        }
                    },
                    onQtyChange = { id, delta ->
                        val item = transaction.items.firstOrNull { it.id == id } ?: return@ItemsBody
                        val remaining = if (item.remainingQty > 0) item.remainingQty else (item.quantity - item.refundedQty).coerceAtLeast(1)
                        val current = refundQtyByItem[id] ?: remaining
                        val next = (current + delta).coerceIn(1, remaining)
                        refundQtyByItem = refundQtyByItem + (id to next)
                    },
                    onToggleRestock = { id ->
                        restockItemIds = if (restockItemIds.contains(id)) {
                            restockItemIds - id
                        } else {
                            restockItemIds + id
                        }
                    },
                )

                RefundTab.AMOUNT -> AmountBody(
                    amountStr = amountStr,
                    onAmountChange = { amountStr = it },
                    maxRefundable = maxRefundable,
                    paymentTipAmount = paymentTipAmount,
                    includeTip = includeTip,
                    onIncludeTipChange = { includeTip = it },
                )
            }

            Box {
                OutlinedTextField(
                    value = reason?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Motivo del reembolso") },
                    placeholder = { Text("Selecciona un motivo") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { reasonMenuOpen = true },
                )
                DropdownMenu(
                    expanded = reasonMenuOpen,
                    onDismissRequest = { reasonMenuOpen = false },
                ) {
                    REASONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                reason = option
                                reasonMenuOpen = false
                            },
                        )
                    }
                }
            }
            }
            // === END SCROLLABLE MIDDLE ===

            // === FIXED FOOTER — always visible ===
            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md)
                    .clip(RoundedCornerShape(corners.lg))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "Importe a reembolsar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$${"%.2f".format(amountToRefund)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Disponible: ${transaction.remainingRefundableDisplay}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md, bottom = spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !submitting,
                ) {
                    Text("Cancelar")
                }

                PrimaryButton(
                    text = "Reembolsar",
                    onClick = {
                        val chosenReason = reason ?: return@PrimaryButton
                        submitting = true
                        errorMsg = null

                        scope.launch {
                            val result = if (tab == RefundTab.ITEMS) {
                                refundRepository.issueAssociatedRefund(
                                    paymentId = transaction.id,
                                    reason = chosenReason.code,
                                    items = selectedItems.mapNotNull { item ->
                                        item.id?.let { id ->
                                            AssociatedRefundItem(
                                                orderItemId = id,
                                                quantity = refundQtyByItem[id] ?: item.quantity,
                                            )
                                        }
                                    },
                                    restockItemIds = restockableItems
                                        .mapNotNull { it.id }
                                        .filter { restockItemIds.contains(it) },
                                )
                            } else {
                                // When the user unchecks "Incluir propina" and
                                // the payment had tip, force tipRefundCents=0
                                // so the refund pulls 100% from the sale.
                                // Otherwise (checkbox ON or no tip) omit the
                                // override and let the backend apply its
                                // default proportional split.
                                val tipOverride = if (paymentTipAmount > 0 && !includeTip) 0 else null
                                refundRepository.issueAssociatedRefund(
                                    paymentId = transaction.id,
                                    reason = chosenReason.code,
                                    amountCents = (parsedAmount * 100).toInt(),
                                    tipRefundCents = tipOverride,
                                )
                            }

                            submitting = false
                            result.fold(
                                onSuccess = {
                                    Log.d("💸", "Refund OK: $it")
                                    // 🔴 AQUÍ NO SE ESCRIBE EN EL CAJÓN. El servidor ya lo hace.
                                    //
                                    // Hasta el 2026-08-16 esta pantalla mandaba su propio PAY_OUT
                                    // después de cada reembolso, porque la ruta que usa
                                    // (`refund.dashboard.service.issueRefund`) no tocaba la caja y el
                                    // arqueo inventaba un SOBRANTE del tamaño de lo devuelto — medido
                                    // en hardware: $50,380 en pantalla contra $50,230 físicos.
                                    //
                                    // Ese lado se arregló en el SERVIDOR
                                    // (`shared/cashDrawerPosting.postCashRefundToDrawer`, `08a3fe6f`).
                                    // Volver a escribir desde aquí restaría DOS VECES y le cobraría al
                                    // cajero un faltante que nadie se robó: la llave del servidor
                                    // (`srv-refund:<refundId>`) no puede deduplicar contra un UUID
                                    // local, y un PAY_OUT que llega por `/cash-drawer/sync` es
                                    // indistinguible de un retiro a mano.
                                    //
                                    // El movimiento aparece en la tablet en cuanto se abre Caja:
                                    // `CashDrawerViewModel.init` → `syncFromApi()` baja los eventos
                                    // que el servidor confirmó y de ahí sale el corte. La copia local
                                    // se ALIMENTA de lo confirmado; no se adelanta a ciegas.
                                    //
                                    // Vigilado por `RefundCashDrawerOwnershipTest`.
                                    onRefunded()
                                },
                                onFailure = { throwable ->
                                    errorMsg = formatRefundError(throwable)
                                },
                            )
                        }
                    },
                    enabled = canSubmit,
                    isLoading = submitting,
                    modifier = Modifier.weight(1f),
                    fullWidth = true,
                )
            }
        }
    }
}

@Composable
private fun ItemsBody(
    transaction: Transaction,
    selectedIds: Set<String>,
    refundQty: Map<String, Int>,
    restockIds: Set<String>,
    selectedAmount: Double,
    maxRefundable: Double,
    onToggle: (String) -> Unit,
    onQtyChange: (String, Int) -> Unit,
    onToggleRestock: (String) -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    val corners = AvoqadoTheme.cornerRadius

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = "Selecciona los artículos a reembolsar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Máximo reembolsable: $${"%.2f".format(maxRefundable)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corners.md)),
        ) {
            items(transaction.items, key = { it.id ?: it.productName }) { item ->
                val id = item.id ?: return@items
                // Fully-refunded lines are shown as "Reembolsado" and can't be
                // selected again. Partially-refunded lines allow the stepper to
                // go up to `remainingQty`, not the original `quantity`.
                val remaining = if (item.remainingQty > 0) {
                    item.remainingQty
                } else {
                    // Backward compat for older backends that don't send remainingQty.
                    (item.quantity - item.refundedQty).coerceAtLeast(0)
                }
                val fullyRefunded = item.fullyRefunded || remaining == 0
                val partiallyRefunded = item.partiallyRefunded

                val selected = selectedIds.contains(id)
                val rawQty = refundQty[id] ?: remaining
                val qty = rawQty.coerceIn(1, maxOf(1, remaining))
                val effectiveAmount = RefundAmountCalculator.calculateSelectedAmount(
                    items = listOf(item),
                    selectedIds = setOf(id),
                    refundQtyByItem = mapOf(id to qty),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (fullyRefunded) {
                                Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            } else {
                                Modifier.clickable { onToggle(id) }
                            },
                        )
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { if (!fullyRefunded) onToggle(id) },
                        enabled = !fullyRefunded,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.quantity > 1) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(horizontal = spacing.sm, vertical = spacing.xxs),
                                ) {
                                    Text(
                                        text = "${item.quantity}x",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(modifier = Modifier.width(spacing.sm))
                            }
                            Text(
                                text = item.productName.ifBlank { "Importe personalizado" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            if (fullyRefunded) {
                                Spacer(modifier = Modifier.width(spacing.sm))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = spacing.sm, vertical = spacing.xxs),
                                ) {
                                    Text(
                                        text = "Reembolsado",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        if (partiallyRefunded) {
                            Text(
                                text = "${item.refundedQty} de ${item.quantity} ya se reembolsó",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (item.trackInventory && !fullyRefunded) {
                            Text(
                                text = "Con seguimiento de inventario",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (selected && remaining > 1 && !fullyRefunded) {
                        QtyStepper(
                            value = qty,
                            max = remaining,
                            onChange = { delta -> onQtyChange(id, delta) },
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                    }
                    val displayAmount = if (fullyRefunded) item.refundedAmount.takeIf { it > 0 } ?: item.amount else effectiveAmount
                    Text(
                        text = "$${"%.2f".format(displayAmount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (fullyRefunded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }

        val restockableItems = transaction.items.filter { item ->
            item.id != null && selectedIds.contains(item.id) && item.trackInventory
        }
        if (restockableItems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(corners.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corners.md))
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "Reabastecer existencias",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Marca solo los artículos que sí regresaron al inventario.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                restockableItems.forEach { item ->
                    val id = item.id ?: return@forEach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleRestock(id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = restockIds.contains(id),
                            onCheckedChange = { onToggleRestock(id) },
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.productName.ifBlank { "Artículo" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Cantidad seleccionada: ${refundQty[id] ?: item.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Selección actual: $${"%.2f".format(selectedAmount)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QtyStepper(
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        StepperButton(
            icon = Icons.Filled.Remove,
            enabled = value > 1,
            onClick = { onChange(-1) },
            contentDescription = "Menos",
        )
        Text(
            text = "$value/$max",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        StepperButton(
            icon = Icons.Filled.Add,
            enabled = value < max,
            onClick = { onChange(+1) },
            contentDescription = "Más",
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            },
        )
    }
}

@Composable
private fun AmountBody(
    amountStr: String,
    onAmountChange: (String) -> Unit,
    maxRefundable: Double,
    paymentTipAmount: Double,
    includeTip: Boolean,
    onIncludeTipChange: (Boolean) -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = "Selecciona el importe a reembolsar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Máximo reembolsable: $${"%.2f".format(maxRefundable)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = amountStr,
            onValueChange = onAmountChange,
            label = { Text("Importe") },
            leadingIcon = { Text("$", style = MaterialTheme.typography.bodyLarge) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (paymentTipAmount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onIncludeTipChange(!includeTip) }
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Checkbox(
                    checked = includeTip,
                    onCheckedChange = { onIncludeTipChange(it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Incluir propina en el reembolso",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (includeTip) {
                            "Se reparte proporcional entre venta y propina del pago original."
                        } else {
                            "Solo se reembolsa el producto; la propina del mesero queda intacta."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$${"%.2f".format(paymentTipAmount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val spacing = AvoqadoTheme.spacing
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (active) 1.dp else 0.dp,
                color = if (active) MaterialTheme.colorScheme.outline else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = spacing.md, horizontal = spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(spacing.xxs))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
        }
    }
}

private fun formatRefundError(throwable: Throwable): String {
    val apiError = throwable as? RefundApiException
    return when (apiError?.statusCode) {
        403 -> "No tienes permiso para emitir reembolsos"
        400, 422 -> apiError.message.ifBlank { "Revisa los datos del reembolso" }
        in 500..599 -> "No se pudo emitir el reembolso. Intenta de nuevo."
        else -> throwable.message ?: "Error al emitir reembolso"
    }
}
