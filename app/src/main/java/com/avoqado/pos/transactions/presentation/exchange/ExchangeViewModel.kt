package com.avoqado.pos.transactions.presentation.exchange

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.transactions.data.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.inject.Inject

enum class ExchangeReason(val label: String) {
    SIZE(TextosCambio.TALLA), TASTE(TextosCambio.GUSTO), DEFECT(TextosCambio.DEFECTO),
}

data class ExchangeReturnedRow(val item: TransactionItem, val quantity: Int = 0,
    val reason: ExchangeReason? = null, val restock: Boolean = item.restockable == true,
    val blockedReason: String? = null)
data class ExchangeTakenRow(val product: Product, val inheritsFromOrderItemId: String? = null)
data class ExchangeBar(val label: String, val action: String, val isPayout: Boolean)
data class ExchangeUiState(
    val transaction: Transaction? = null,
    val returned: List<ExchangeReturnedRow> = emptyList(),
    val taken: List<ExchangeTakenRow> = emptyList(),
    val calculation: ExchangeCalculation = calculateExchange(emptyList(), emptyList()),
    val bar: ExchangeBar = exchangeBar(0),
    val blockedReason: String? = TextosCambio.ELIGE_REGRESA,
)

/** Selection body only. Task 5 adds actor/device, key/date and the selected settlement before persisting. */
@Serializable
data class ExchangeDraft(val originalPaymentId: String, val returned: List<ExchangeReturnedInput>,
    val taken: List<ExchangeTakenInput>, val settlement: ExchangeSettlementDraft)
@Serializable
data class ExchangeReturnedInput(val orderItemId: String, val quantity: Int, val restock: Boolean, val reason: String)
@Serializable
data class ExchangeTakenInput(val productId: String, val quantity: Int,
    val inheritsFromOrderItemId: String? = null, val displayedUnitPriceCents: Long, val catalogAt: String? = null)
@Serializable
data class ExchangeSettlementDraft(val kind: String, val expectedDifferenceCents: Long)

internal fun exchangeMoney(cents: Long): String {
    val amount = BigDecimal.valueOf(cents).movePointLeft(2)
    return (if (cents < 0) "-" else "") + "$" + String.format(Locale.US, "%,.2f", amount.abs())
}

/** The existing catalog DTO uses pesos; enter integer cents once, without the Int price helper's saturation. */
internal fun catalogPriceCents(product: Product): Long? = try {
    BigDecimal.valueOf(product.priceValue).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        .takeIf { it in 0L..9_007_199_254_740_991L }
} catch (_: NumberFormatException) { null }
catch (_: ArithmeticException) { null }

fun exchangeBar(difference: Long): ExchangeBar {
    val price = exchangeMoney(if (difference < 0) Math.negateExact(difference) else difference)
    return when {
        difference == 0L -> ExchangeBar(TextosCambio.CAMBIO_PAREJO, TextosCambio.HACER_CAMBIO_PAREJO, false)
        difference > 0 -> ExchangeBar(TextosCambio.CLIENTA_PAGA.replace("{precio}", price),
            TextosCambio.COBRAR.replace("{precio}", price), false)
        else -> ExchangeBar(TextosCambio.A_FAVOR_CLIENTA.replace("{precio}", price),
            TextosCambio.DEVOLVER.replace("{precio}", price), true)
    }
}

@HiltViewModel
class ExchangeViewModel @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {
    private val _state = MutableStateFlow(ExchangeUiState())
    val state: StateFlow<ExchangeUiState> = _state.asStateFlow()
    val products = productsRepository.products
    val catalogLoading = productsRepository.isLoading
    val catalogError = productsRepository.error
    private val _preparedExchange = MutableStateFlow<ExchangeDraft?>(null)
    val preparedExchange = _preparedExchange.asStateFlow()
    private var serverCalculation: com.avoqado.pos.transactions.data.ExchangeServerCalculation? = null
    var submissionLocked: Boolean = false
        private set

    fun lockSubmission(locked: Boolean) { submissionLocked = locked }

    fun applyServerCalculation(calculation: com.avoqado.pos.transactions.data.ExchangeServerCalculation) {
        serverCalculation = calculation
        recalculate()
    }

    init {
        viewModelScope.launch {
            combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { connected, reachable ->
                connected && reachable
            }.collect { recalculate() }
        }
    }

    fun start(transaction: Transaction) {
        if (_state.value.transaction?.id == transaction.id) return
        serverCalculation = null
        submissionLocked = false
        val rows = transaction.items.map { ExchangeReturnedRow(it, blockedReason = itemBlockedReason(it)) }
        val groups = rows.filter { it.item.isPromotionLine == true && it.item.orderPromotionId != null }
            .groupBy { it.item.orderPromotionId }
        _state.value = ExchangeUiState(transaction = transaction, returned = rows.map { row ->
            if (row.item.isPromotionLine == true && groups[row.item.orderPromotionId]?.any { it.blockedReason != null } == true)
                row.copy(blockedReason = row.blockedReason ?: TextosCambio.PROMOCION_COMPLETA) else row
        })
        recalculate()
        viewModelScope.launch { productsRepository.fetchProducts(enSegundoPlano = true) }
    }

    fun restart(transaction: Transaction) {
        submissionLocked = false
        serverCalculation = null
        _state.value = ExchangeUiState()
        start(transaction)
    }

    private fun itemBlockedReason(item: TransactionItem): String? {
        item.notExchangeableReason?.let { return ExchangeEntry.reason(it) }
        if (item.isPromotionLine == true && item.orderPromotionId.isNullOrBlank()) return TextosCambio.PROMOCION_COMPLETA
        if (item.productType in setOf("APPOINTMENTS_SERVICE", "CLASS", "EVENT", "DIGITAL", "DONATION", "SERVICE"))
            return TextosCambio.NOT_MERCHANDISE
        if (item.remainingQtyOrder == null) return TextosCambio.NOT_ATTRIBUTABLE
        if (item.remainingQtyOrder <= 0) return TextosCambio.ALREADY_RETURNED
        if (item.id.isNullOrBlank() || item.productId.isNullOrBlank() || item.quantity <= 0 ||
            item.chargedTotalCents == null || item.returnedCentsOrder == null || item.returnedQtyOrder == null ||
            item.isPromotionLine == null || item.restockable == null || item.productType == null ||
            item.chargedTotalCents < 0 || item.returnedCentsOrder < 0 || item.returnedQtyOrder < 0 ||
            item.returnedQtyOrder.toLong() + item.remainingQtyOrder!! > item.quantity) return TextosCambio.NOT_ATTRIBUTABLE
        return try {
            Math.toIntExact(item.chargedTotalCents)
            Math.toIntExact(item.returnedCentsOrder)
            null
        } catch (_: ArithmeticException) { TextosCambio.NOT_ATTRIBUTABLE }
    }

    fun toggleReturned(id: String) {
        if (submissionLocked) return
        serverCalculation = null
        val row = _state.value.returned.firstOrNull { it.item.id == id } ?: return
        if (row.blockedReason != null) return
        val selected = row.quantity == 0
        _state.value = _state.value.copy(returned = _state.value.returned.map {
            val remaining = it.item.remainingQtyOrder
            if (it.blockedReason != null || remaining == null) return@map it
            val same = it.item.id == id || (row.item.isPromotionLine == true &&
                it.item.isPromotionLine == true && it.item.orderPromotionId == row.item.orderPromotionId)
            if (same) it.copy(quantity = if (!selected) 0 else if (it.item.isPromotionLine == true)
                remaining else 1) else it
        })
        recalculate()
    }

    fun setReturnedQuantity(id: String, quantity: Int) {
        changeRow(id) { row ->
            if (row.quantity == 0 || row.item.isPromotionLine == true) row
            else row.copy(quantity = quantity.coerceIn(1, row.item.remainingQtyOrder!!))
        }
    }
    fun setReason(id: String, reason: ExchangeReason) = changeRow(id) {
        it.copy(reason = reason, restock = if (reason == ExchangeReason.DEFECT) false else it.restock)
    }
    fun setRestock(id: String, restock: Boolean) = changeRow(id) { it.copy(restock = restock && it.item.restockable == true) }
    private fun changeRow(id: String, change: (ExchangeReturnedRow) -> ExchangeReturnedRow) {
        if (submissionLocked) return
        serverCalculation = null
        _state.value = _state.value.copy(returned = _state.value.returned.map {
            if (it.item.id == id && it.blockedReason == null) change(it) else it
        })
        recalculate()
    }

    fun searchProducts(query: String): List<Product> =
        (productsRepository.searchProducts(query) + products.value.filter { query.isNotBlank() && it.barcode == query })
            .distinctBy { it.id }.filter(::sellable)
    private fun sellable(p: Product) = p.active != false && !p.soldByWeight && catalogPriceCents(p) != null &&
        p.type !in setOf("APPOINTMENTS_SERVICE", "CLASS", "EVENT", "DIGITAL", "GIFT_CARD", "DONATION", "SERVICE")

    fun addTaken(product: Product, inheritsFromOrderItemId: String? = null) {
        if (submissionLocked) return
        serverCalculation = null
        val current = products.value.firstOrNull { it.id == product.id } ?: return
        if (!sellable(current) || current.isOutOfStock) return
        val returnedIds = _state.value.returned.filter { it.quantity > 0 }.mapNotNull { it.item.productId }
        if (current.productGroupId != null && current.id in returnedIds) return
        if (_state.value.taken.size >= 100) return
        _state.value = _state.value.copy(taken = _state.value.taken + ExchangeTakenRow(current, inheritsFromOrderItemId))
        recalculate()
    }
    fun removeTaken(index: Int) {
        if (submissionLocked) return
        serverCalculation = null
        _state.value = _state.value.copy(taken = _state.value.taken.filterIndexed { i, _ -> i != index })
        recalculate()
    }

    private fun pieces(row: ExchangeReturnedRow): List<ReturnedPiece> {
        if (row.quantity == 0) return emptyList()
        val item = row.item
        return pieceValuesCents(Math.toIntExact(item.chargedTotalCents!!), item.quantity,
            item.returnedQtyOrder!!, Math.toIntExact(item.returnedCentsOrder!!), row.quantity).map {
            ReturnedPiece(item.id!!, item.productId, item.productGroupId, it.toLong(), item.currentListPriceCents)
        }
    }
    fun returnedValue(row: ExchangeReturnedRow): Long = serverCalculation?.lines?.returned
        ?.firstOrNull { it.orderItemId == row.item.id }?.valueCents ?: pieces(row).sumOf { it.paidCents }

    private fun recalculate() {
        _preparedExchange.value = null
        val s = _state.value
        val selected = s.returned.filter { it.quantity > 0 }
        val calculation = try {
            calculateExchange(selected.flatMap(::pieces), s.taken.map { row ->
                TakenLine(row.product.id, row.product.productGroupId, catalogPriceCents(row.product)!!, 1, row.inheritsFromOrderItemId)
            }).let { local ->
                serverCalculation?.let { server ->
                    ExchangeCalculation(server.returnedCents, server.takenCents, server.differenceCents,
                        ExchangeKind.valueOf(server.kind), server.lines.taken.flatMapIndexed { index, line ->
                            line.pieces.map { piece -> TakenPiecePrice(index, piece.priceCents, line.listCents,
                                local.takenPieces.getOrNull(index)?.inheritedFrom) }
                        })
                } ?: local
            }.also { value ->
                require(value.takenPieces.all { it.priceCents in 0L..9_007_199_254_740_991L })
            }
        } catch (_: ArithmeticException) {
            _state.value = s.copy(calculation = calculateExchange(emptyList(), emptyList()),
                bar = ExchangeBar(TextosCambio.NOT_ATTRIBUTABLE, TextosCambio.CAMBIO, false),
                blockedReason = TextosCambio.NOT_ATTRIBUTABLE)
            return
        } catch (_: IllegalArgumentException) {
            _state.value = s.copy(calculation = calculateExchange(emptyList(), emptyList()),
                bar = ExchangeBar(TextosCambio.NOT_ATTRIBUTABLE, TextosCambio.CAMBIO, false),
                blockedReason = TextosCambio.NOT_ATTRIBUTABLE)
            return
        }
        val reason = when {
            !connectivityMonitor.isOnline() -> TextosCambio.SIN_CONEXION
            s.transaction?.exchangeable != true -> TextosCambio.NOT_A_SALE
            selected.isEmpty() -> TextosCambio.ELIGE_REGRESA
            selected.size > 100 || s.taken.size > 100 || selected.any { it.quantity > 999 } -> TextosCambio.LIMITE_ARTICULOS
            selected.any { it.reason == null } -> TextosCambio.ELIGE_MOTIVO
            s.taken.isEmpty() -> TextosCambio.ELIGE_SE_LLEVA
            s.taken.any { taken -> taken.product.productGroupId != null &&
                selected.any { it.item.productId == taken.product.id } } -> TextosCambio.TALLA_QUE_REGRESA
            calculation.kind == ExchangeKind.PAYOUT && s.transaction?.payoutOptions.orEmpty().none {
                (it.maxCents ?: 0) >= -calculation.differenceCents
            } -> TextosCambio.SIN_OPCIONES_DEVOLUCION
            else -> null
        }
        _state.value = s.copy(calculation = calculation, bar = exchangeBar(calculation.differenceCents), blockedReason = reason)
    }

    fun inheritedLabel(index: Int): String? {
        val piece = _state.value.calculation.takenPieces.getOrNull(index) ?: return null
        if (piece.priceCents >= piece.listCents) return null
        val from = piece.inheritedFrom ?: return null
        val item = _state.value.returned.firstOrNull { it.item.id == from.orderItemId }?.item ?: return null
        val size = item.variantOptions?.firstOrNull { it.name.equals("Talla", true) }?.value
        return if (size != null) TextosCambio.CONSERVA_REBAJA.replace("{talla}", size)
            else TextosCambio.CONSERVA_REBAJA_PRODUCTO.replace("{producto}", item.productName)
    }

    /** Task 5 entry point: validate, expose selection body; no POST, local stock write or outbox. */
    fun prepareExchange(): ExchangeDraft? {
        if (submissionLocked) return null
        recalculate()
        val s = _state.value
        if (s.blockedReason != null) return null
        return ExchangeDraft(s.transaction!!.id, s.returned.filter { it.quantity > 0 }.map {
            ExchangeReturnedInput(it.item.id!!, it.quantity, it.restock, it.reason!!.name)
        }, s.taken.map { row ->
            ExchangeTakenInput(row.product.id, 1,
                row.inheritsFromOrderItemId,
                catalogPriceCents(row.product)!!, row.product.catalogAt)
        }, ExchangeSettlementDraft(s.calculation.kind.name, s.calculation.differenceCents)).also { _preparedExchange.value = it }
    }
}
