package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.transactions.data.AssociatedRefundItem
import com.avoqado.pos.transactions.data.RefundApiException
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.model.RefundAmountCalculator
import com.avoqado.pos.transactions.data.model.centavosDelImporte
import com.avoqado.pos.transactions.data.model.tipRefundCentsParaEnvio
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
        val bandaScroll = rememberScrollState()
        val pieScroll = rememberScrollState()
        // Three-band layout so footer (amount summary + Reembolsar) stays
        // visible on small tablets (≈9"): fixed header + scrollable middle
        // (weight=1f) + fixed footer. Also capped width for landscape.
        //
        // 🔴 EL `BoxWithConstraints` NO ES DECORATIVO: es lo ÚNICO que sabe cuánto alto
        // queda DE VERDAD, porque lleva el `imePadding` encima y por tanto su `maxHeight`
        // ya viene con el teclado descontado. Sin ese dato la hoja no puede reaccionar al
        // teclado, y lo que pasaba es esto (medido en aparato el 2026-09-12, forzando
        // 360×640 dp en el OrderPAD 3): al tocar el campo de importe, el selector de
        // motivo, el resumen, la casilla «Incluir propina» y **los botones Cancelar y
        // Reembolsar** quedaban debajo del teclado, y como el pie NO se desliza, no había
        // forma de alcanzarlos sin cerrar el teclado — sin una sola señal de que existieran.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
                // a su ventana, asi que el ajuste va en el contenido.
                .imePadding(),
        ) {
        // El umbral sale de MEDIR la hoja en el aparato (OrderPAD 3 forzado, 2026-09-12),
        // no de una corazonada:
        //
        //   encabezado + pestañas .................... 160 dp
        //   pie (importe, motivo, resumen, botones) ... 365 dp   → 525 dp
        //   + la casilla «Incluir propina» ............  64 dp   → 589 dp con propina
        //
        // Alto disponible con el teclado abierto: ~330 dp en una pantalla de 640 dp (el
        // perfil del PAX A910S y el de un celular chico) y ~600 dp en un celular moderno
        // de 891 dp. O sea: en el chico NO cabe por mucho, y en el moderno cabe por 11 dp
        // cuando el cobro trae propina — cualquier ajuste de fuente por accesibilidad lo
        // tira. Por eso el corte va en 640 y no en 560: cubrir sólo el caso extremo dejaba
        // el caso común al filo.
        //
        // Se mide por ALTO DISPONIBLE y no por «¿hay teclado?» porque una pantalla corta
        // en horizontal aprieta igual sin teclado ninguno. Y cuando NO aprieta el pie no
        // lleva scroll: la hoja queda exactamente como estaba.
        //
        // 🔴 SÓLO EN LA PESTAÑA DE IMPORTE, y esto no es un detalle: la primera versión
        // miraba únicamente el alto y una auditoría adversarial (Codex gpt-6-astra, xhigh)
        // demostró que rompía el reembolso por ARTÍCULOS. En la banda no vive sólo el aviso
        // —viven `ItemsBody` y el botón «Abrir en la terminal»—, así que ocultarla en esa
        // pestaña dejaba al cajero con «Selecciona al menos un artículo» y ni un artículo que
        // tocar, sin forma de recuperarlos. Bastaban 600 dp de alto, con el teclado CERRADO.
        //
        // En la pestaña de artículos el pie es corto (motivo, resumen y botones: ~200 dp), así
        // que con el alto de cualquier aparato real cabe sin comprimir nada. La banda sólo
        // cede su sitio donde es prescindible: en importe, donde su único contenido es un
        // aviso que el pie ya repite palabra por palabra.
        val apretado = tab == RefundTab.AMOUNT && maxHeight < 640.dp
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

            // === BANDA DESPLAZABLE ===
            //
            // 🔴 `verticalScroll` NO es cosmético. Sin él, lo que no cabía se
            // recortaba en silencio: en una tablet el selector de motivo quedaba
            // dibujado fuera de la pantalla y, como `canSubmit` exige motivo, el
            // botón "Reembolsar" jamás se encendía. La devolución era imposible y
            // nada en pantalla lo explicaba.
            // 🔴 `fill = false`, no `true`. Con `fill = true` la banda se estiraba a
            // TODO el alto sobrante aunque el contenido fuera corto: de ahí el hueco
            // enorme entre la lista y el motivo que se veía en la tablet de Testarudo.
            // 🔴 Cuando aprieta, la banda cede su sitio ENTERO en vez de quedarse en un
            // hilo de píxeles: lo que hay dentro es un aviso informativo y la lista de
            // artículos, y el aviso que de verdad importa —«esto no devuelve el dinero a
            // la tarjeta»— se repite literal en el pie. Lo que NO puede desaparecer es el
            // pie, que es donde se decide y se confirma el dinero.
            if (!apretado) {
            Box(modifier = Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(bandaScroll),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
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
                        text = "La devolución a la tarjeta se hace en la terminal. Ábrela desde " +
                            "aquí; sólo falta que alguien la confirme con la tarjeta.",
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
                        text = "Si le entregas efectivo de la caja, regístralo como retiro en " +
                            "Caja: el sistema no lo descuenta solo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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

                // 🔴 La pestaña de IMPORTE no vive aquí: su campo y su casilla de propina
                // son DINERO, y con el teclado abierto esta banda se encoge a nada. Se
                // pintan en el pie fijo, junto al motivo y al total. Aquí sólo queda el
                // aviso del cobro con tarjeta, que sí puede desplazarse.
                RefundTab.AMOUNT -> Unit
            }
            }
            // 🔴 Sin esta señal la banda MIENTE: una venta de 3 artículos enseñaba 2 y
            // nada decía que hubiera más, así que el cajero reembolsa lo que alcanza a
            // ver. Medido en la Sunmi el 2026-09-11.
            if (bandaScroll.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(spacing.xl)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                            ),
                        ),
                )
            }
            }
            }
            // === FIN DE LA BANDA DESPLAZABLE ===

            // === PIE FIJO — siempre visible ===
            //
            // 🔴 «Siempre visible» dejó de ser cierto con el teclado abierto, así que aquí
            // el pie gana su propio deslizamiento — y SÓLO cuando aprieta. Con espacio
            // sobrado el modificador es `Modifier` vacío y la pantalla queda byte a byte
            // como estaba; no se usan dos `weight` a la vez a propósito, porque repartir el
            // sobrante entre banda y pie deja hueco muerto cuando uno de los dos es corto,
            // que es justo el defecto que el founder vio en la tablet el 2026-09-11.
            //
            // La casilla «Incluir propina» sigue AQUÍ y no en la banda de artículos: para
            // llegar al botón hay que pasar por ella, porque el motivo —que `canSubmit`
            // exige— vive debajo. Deslizable no es lo mismo que escondido.
            Column(
                modifier = if (apretado) {
                    Modifier
                        .weight(1f)
                        .verticalScroll(pieScroll)
                } else {
                    Modifier
                },
            ) {
            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            // 🔴 La casilla vive en el PIE FIJO, no en la banda que se desliza: decide si
            // la propina del mesero se devuelve, o sea que es DINERO. Con el teclado
            // abierto la banda se encoge tanto que la casilla salía de la pantalla y el
            // cajero confirmaba «incluir propina» sin poder verla (Sunmi, 2026-09-11).
            if (tab == RefundTab.AMOUNT) {
                AmountBody(
                    amountStr = amountStr,
                    onAmountChange = { amountStr = it },
                    maxRefundable = maxRefundable,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }

            if (tab == RefundTab.AMOUNT && paymentTipAmount > 0) {
                FilaIncluirPropina(
                    paymentTipAmount = paymentTipAmount,
                    includeTip = includeTip,
                    onIncludeTipChange = { includeTip = it },
                    modifier = Modifier.padding(top = spacing.md),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md)
                    .clip(RoundedCornerShape(corners.lg))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "Motivo del reembolso",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    AvoqadoPillTextField(
                        // El campo se pinta sobre `surface`, igual que la hoja:
                        // dentro de este panel más oscuro se distingue solo, y el
                        // borde lo remata para que se lea como algo que se toca.
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(50),
                        ),
                        value = reason?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        placeholder = "Selecciona un motivo",
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(enabled = !submitting) { reasonMenuOpen = true },
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

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = spacing.xs),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Importe a reembolsar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMoney(amountToRefund),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // 🔴 EL REPARTO SE DICE JUNTO AL BOTÓN, no sólo en la casilla.
                        //
                        // La casilla «Incluir propina» vive más arriba y, con el teclado
                        // abierto en una pantalla corta, puede quedar fuera de vista en el
                        // momento de confirmar — lo señaló una auditoría adversarial (Codex
                        // gpt-6-astra, xhigh): que el cajero haya pasado por ella una vez no
                        // garantiza que la esté viendo al pulsar. Esta línea viaja pegada al
                        // importe y al botón, así que la decisión sobre el dinero del mesero
                        // se lee siempre, sin depender de dónde quedó el scroll.
                        if (tab == RefundTab.AMOUNT && paymentTipAmount > 0) {
                            Text(
                                text = if (includeTip) {
                                    "Incluye la propina del mesero"
                                } else {
                                    "Sin tocar la propina del mesero"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "Disponible ${transaction.remainingRefundableDisplay}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 🔴 Un botón apagado sin explicación se lee como una pantalla rota:
            // fue justo lo que pasó cuando el motivo quedaba fuera de cuadro.
            // Aquí se dice qué falta, en el orden en que hay que resolverlo.
            val faltaParaReembolsar = when {
                submitting -> null
                amountToRefund <= 0.0 -> if (tab == RefundTab.ITEMS) {
                    "Selecciona al menos un artículo."
                } else {
                    "Escribe el importe a reembolsar."
                }
                amountToRefund > maxRefundable + 0.001 ->
                    "El importe supera lo disponible (${transaction.remainingRefundableDisplay})."
                reason == null -> "Elige el motivo del reembolso."
                else -> null
            }
            faltaParaReembolsar?.let { falta ->
                Text(
                    text = falta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            // 🔴 El aviso completo del cobro con tarjeta ahora se desplaza, así
            // que puede quedar fuera de vista justo cuando se toca el botón. Este
            // renglón repite lo único que no se puede malentender: esto NO le
            // devuelve el dinero a la tarjeta.
            if (!transaction.method.equals("CASH", ignoreCase = true)) {
                Text(
                    text = "Esto no devuelve el dinero a la tarjeta: eso se hace en la terminal.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.sm, bottom = spacing.md),
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
                                refundRepository.issueAssociatedRefund(
                                    paymentId = transaction.id,
                                    reason = chosenReason.code,
                                    amountCents = centavosDelImporte(parsedAmount),
                                    tipRefundCents = tipRefundCentsParaEnvio(paymentTipAmount, includeTip),
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
            // 🔴 El conteo es la única señal HONESTA de que hay más abajo: el degradado
            // del borde es transparente→blanco sobre una lista que ya es blanca, así que
            // no se ve. Sin esto, una venta de 3 artículos enseña 2 y el cajero reembolsa
            // lo que alcanza a ver (medido en la Sunmi, 2026-09-11).
            Text(
                text = "${transaction.items.size} artículo${if (transaction.items.size == 1) "" else "s"} · " +
                    "Máximo reembolsable: ${formatMoney(maxRefundable)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corners.md))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corners.md)),
        ) {
            transaction.items.forEachIndexed { index, item ->
                val id = item.id ?: return@forEachIndexed
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
                        text = formatMoney(displayAmount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (fullyRefunded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
                // Sin separador tras el último: pegado al borde del recuadro se
                // veía como una raya suelta.
                if (index < transaction.items.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
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
            text = "Selección actual: ${formatMoney(selectedAmount)}",
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
    modifier: Modifier = Modifier,
) {
    val spacing = AvoqadoTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = "Selecciona el importe a reembolsar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Máximo reembolsable: ${formatMoney(maxRefundable)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 🔴 Componente de la casa, no `OutlinedTextField` crudo: su etiqueta
        // flotante "Importe" se montaba sobre el borde y se veía CORTADA, con
        // dos contornos encimados (medido en la D3, 2026-08-17). Aquí la
        // etiqueta vive fuera del campo y dentro sólo va el número.
        Text(
            text = "Importe",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AvoqadoPillTextField(
            value = amountStr,
            onValueChange = onAmountChange,
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            leading = {
                Text(
                    text = "$",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
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

/**
 * La casilla «Incluir propina», extraída para poder vivir en el PIE FIJO de la hoja.
 * Marcada ⇒ el servidor reparte proporcional; desmarcada ⇒ sólo se devuelve la venta.
 */
@Composable
private fun FilaIncluirPropina(
    paymentTipAmount: Double,
    includeTip: Boolean,
    onIncludeTipChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AvoqadoTheme.spacing
    Box(modifier = modifier) {
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
                text = formatMoney(paymentTipAmount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
