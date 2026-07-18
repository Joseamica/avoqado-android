package com.avoqado.pos.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.round

/**
 * TABLE_SERVICE (PRO) — state for the dedicated table screen (TableOrderScreen).
 *
 * Square's check model, two halves:
 * - SENT (server truth): the open order fetched via getOrderDetail — items
 *   grouped by course with their send time (createdAt == fire moment).
 * - PENDING (local only): lines picked from the grid, each tagged with the
 *   course selected at add time. They only exist on the server after "Enviar".
 *
 * The quick-sale register knows nothing about any of this — table ordering
 * lives here; paying still rides the proven PAYING seam through the register.
 */
@HiltViewModel
class TableOrderViewModel @Inject constructor(
    private val repository: TableServiceRepository,
    val tableSession: TableSession,
    private val printConfigRepository: PrintConfigRepository,
    private val comandaPrinter: ComandaPrinter,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    /** A not-yet-sent line: the cart item + the course it will fire under. */
    data class PendingLine(val item: CartItem, val course: String?)

    /** Floor snapshot — the header reads covers/openedAt for the active table. */
    val floorTables: StateFlow<List<com.avoqado.pos.tables.data.DiningTable>> = repository.tables

    private val _check = MutableStateFlow<OrderDetail?>(null)
    val check: StateFlow<OrderDetail?> = _check.asStateFlow()

    private val _isLoadingCheck = MutableStateFlow(false)
    val isLoadingCheck: StateFlow<Boolean> = _isLoadingCheck.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingLine>>(emptyList())
    val pending: StateFlow<List<PendingLine>> = _pending.asStateFlow()

    /** Selected course slot (null = "Inmediato"). New grid picks land here. */
    private val _selectedCourse = MutableStateFlow<String?>(null)
    val selectedCourse: StateFlow<String?> = _selectedCourse.asStateFlow()

    /** "Más platos": extra slots beyond the base list ("Plato 5", "Plato 6"...). */
    private val _extraCourses = MutableStateFlow<List<String>>(emptyList())
    val extraCourses: StateFlow<List<String>> = _extraCourses.asStateFlow()

    private val _hideSent = MutableStateFlow(false)
    val hideSent: StateFlow<Boolean> = _hideSent.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val venueId: String? get() = secureStorage.venueId

    companion object {
        /** Base slots, always visible in the pending card (Square-style). */
        val BASE_COURSES: List<String?> = listOf(null, "Aperitivos", "Principales", "Postres")
    }

    // MARK: - Check (server truth)

    fun loadCheck() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            _isLoadingCheck.value = true
            repository.getOrderDetail(vId, session.orderId).onSuccess { _check.value = it }
            _isLoadingCheck.value = false
        }
    }

    fun toggleHideSent() {
        _hideSent.value = !_hideSent.value
    }

    // MARK: - Pending (local round being built)

    fun selectCourse(course: String?) {
        _selectedCourse.value = course
    }

    /** "Más platos": adds the next numbered slot and selects it. */
    fun addExtraCourse() {
        val next = "Plato ${BASE_COURSES.size + _extraCourses.value.size + 1}"
        _extraCourses.value = _extraCourses.value + next
        _selectedCourse.value = next
    }

    /** Grid tap without modifiers. Dedupe is COURSE-aware: the same product on
     *  two courses must stay two lines (they fire at different moments). */
    fun addProduct(product: Product) {
        val course = _selectedCourse.value
        val existing = _pending.value.firstOrNull { line ->
            line.course == course &&
                (line.item.type as? CartItemType.ProductItem)?.productId == product.id &&
                line.item.selectedModifiers.isEmpty()
        }
        _pending.value = if (existing != null) {
            _pending.value.map { line ->
                if (line === existing) line.copy(item = line.item.copy(quantity = line.item.quantity + 1)) else line
            }
        } else {
            _pending.value + PendingLine(
                item = CartItem(
                    type = CartItemType.ProductItem(product.id),
                    name = product.name,
                    unitPrice = product.priceInCents,
                    imageUrl = product.imageUrl,
                    colorHex = product.color,
                    categoryId = product.categoryId,
                ),
                course = course,
            )
        }
    }

    /** Detail-panel add (modifiers/notes). Always its own line. */
    fun addProductWithModifiers(
        product: Product,
        quantity: Int,
        modifiers: List<SelectedModifier>,
        note: String?,
    ) {
        _pending.value = _pending.value + PendingLine(
            item = CartItem(
                type = CartItemType.ProductItem(product.id),
                name = product.name,
                unitPrice = product.priceInCents,
                quantity = quantity,
                imageUrl = product.imageUrl,
                colorHex = product.color,
                categoryId = product.categoryId,
                selectedModifiers = modifiers,
                itemNote = note,
            ),
            course = _selectedCourse.value,
        )
    }

    fun removePending(lineItemId: String) {
        _pending.value = _pending.value.filterNot { it.item.id == lineItemId }
    }

    fun updatePendingQuantity(lineItemId: String, quantity: Int) {
        if (quantity < 1) return removePending(lineItemId)
        _pending.value = _pending.value.map { line ->
            if (line.item.id == lineItemId) line.copy(item = line.item.copy(quantity = quantity)) else line
        }
    }

    val pendingCount: Int get() = _pending.value.sumOf { it.item.quantity }
    val pendingTotalCents: Int get() = _pending.value.sumOf { it.item.totalPrice }

    // MARK: - Enviar (fire the round)

    /**
     * Sends ALL pending lines in ONE atomic round (each with its own course —
     * the server accepts course per line), then prints one comanda batch per
     * course. Success → session cleared, caller returns to the floor plan
     * (Square drops the waiter back on the plano after firing).
     */
    fun sendRound(onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: run { onDone(false, "No hay mesa activa"); return }
        val vId = venueId ?: return
        if (_isSending.value) return
        val lines = _pending.value
        if (lines.isEmpty()) {
            onDone(false, "Agrega productos para enviar")
            return
        }

        _isSending.value = true
        viewModelScope.launch {
            val requests = lines.map { line ->
                AddOrderItemRequest(
                    productId = (line.item.type as CartItemType.ProductItem).productId,
                    quantity = line.item.quantity,
                    notes = line.item.itemNote,
                    modifierIds = line.item.selectedModifiers.map { it.modifierId }.ifEmpty { null },
                    course = line.course,
                )
            }
            repository.addRound(vId, session.orderId, requests, session.version).fold(
                onSuccess = { updated ->
                    // Saved — hand control back IMMEDIATELY; printing and the
                    // floor refresh are slow network hops and must not block.
                    tableSession.updateVersion(updated.version)
                    tableSession.clear()
                    _pending.value = emptyList()
                    _isSending.value = false
                    onDone(true, "Ronda enviada a cocina — Mesa ${session.tableNumber}")

                    launch {
                        printConfigRepository.refresh(vId)
                        val config = printConfigRepository.getCurrentConfig()
                        if (config.stations.any { it.active }) {
                            // One comanda batch per course so each ticket reads
                            // "Mesa 8 · Aperitivos" like the single-course flow.
                            lines.groupBy { it.course }.forEach { (course, courseLines) ->
                                val routable = courseLines.map { line ->
                                    RoutableItem(
                                        orderItemId = line.item.id,
                                        productId = (line.item.type as? CartItemType.ProductItem)?.productId,
                                        categoryId = line.item.categoryId,
                                        productName = line.item.name,
                                        quantity = line.item.quantity,
                                        modifiers = line.item.selectedModifiers.map { it.modifierName },
                                        notes = line.item.itemNote,
                                    )
                                }
                                val plans = PrintRoutingMapper.buildComandas(routable, config)
                                comandaPrinter.printComandas(
                                    plans = plans,
                                    config = config,
                                    orderNumber = session.orderNumber,
                                    orderType = "Mesa ${session.tableNumber}" + (course?.let { " · $it" } ?: ""),
                                )
                            }
                        }
                        repository.refresh(vId)
                    }
                },
                onFailure = { e ->
                    _isSending.value = false
                    repository.refresh(vId)
                    val msg = if (e.message?.contains("409") == true) {
                        "La orden cambió en otro dispositivo — vuelve a abrir la mesa"
                    } else {
                        e.message ?: "No se pudo enviar la ronda"
                    }
                    onDone(false, msg)
                },
            )
        }
    }

    // MARK: - Sent-item actions

    /** "Dar de cortesía" on an already-sent line. */
    fun compSentItem(itemId: String, reason: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.compItem(vId, session.orderId, itemId, reason).fold(
                onSuccess = {
                    _actionMessage.value = "Artículo de cortesía — $reason"
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e ->
                    _actionMessage.value = e.message ?: "No se pudo dar de cortesía"
                    loadCheck()
                },
            )
        }
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    /** "Anular cuenta" — cancels the check; success means the table is free. */
    fun anularCuenta(reason: String, onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.cancelOrder(vId, session.orderId, reason).fold(
                onSuccess = {
                    repository.clearTable(vId, session.tableId)
                    repository.refresh(vId)
                    tableSession.clear()
                    onDone(true, "Cuenta anulada — Mesa ${session.tableNumber} liberada")
                },
                onFailure = { e ->
                    repository.refresh(vId)
                    onDone(false, e.message ?: "No se pudo anular la cuenta")
                },
            )
        }
    }

    /** Pre-cuenta on the RECEIPT printer, from the freshly loaded check. */
    fun printPreBill() {
        val session = tableSession.current() ?: return
        val order = _check.value ?: return
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT) ?: run {
            _actionMessage.value = "No hay impresora de recibos configurada"
            return
        }
        viewModelScope.launch {
            try {
                fun cents(v: Double) = round(v * 100).toInt()
                val receipt = ReceiptData(
                    orderNumber = order.orderNumber,
                    orderType = "Mesa ${session.tableNumber} — PRE-CUENTA",
                    items = order.items.map {
                        ReceiptItem(
                            name = it.productName ?: "Artículo",
                            quantity = it.quantity,
                            unitPrice = cents(it.unitPrice),
                            totalPrice = cents(it.total),
                        )
                    },
                    subtotal = cents(order.total),
                    taxAmount = 0,
                    total = cents(order.total),
                    venueName = secureStorage.venueName ?: "Avoqado",
                    cashierName = null,
                )
                printerService.printReceipt(receipt, printer)
                _actionMessage.value = "Pre-cuenta impresa"
            } catch (e: Exception) {
                _actionMessage.value = "No se pudo imprimir: ${e.message ?: "desconocido"}"
            }
        }
    }

    // MARK: - Pagar / salir

    /**
     * Flips the session to PAYING with the FRESH check total (the floor
     * snapshot could be stale if another device added items), so the register's
     * PAYING seam seeds and charges exactly what's owed. Returns false when
     * there's nothing to charge.
     */
    fun preparePagar(): Boolean {
        val session = tableSession.current() ?: return false
        val totalCents = _check.value?.let { round(it.total * 100).toInt() } ?: session.totalCents
        if (totalCents <= 0) return false
        tableSession.start(session.copy(mode = TableSession.Mode.PAYING, totalCents = totalCents))
        return true
    }

    /** Leaves the screen back to the floor plan. Pending lines die with it —
     *  the caller confirms with the user first when any exist. */
    fun exitToFloor() {
        tableSession.clear()
        _pending.value = emptyList()
    }
}
