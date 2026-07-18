package com.avoqado.pos.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.round

sealed interface TableActionState {
    data object Idle : TableActionState
    data object Working : TableActionState
    data class Error(val message: String) : TableActionState
    data class Success(val message: String) : TableActionState
}

/**
 * TABLE_SERVICE (PRO) — floor plan + table lifecycle. Opening a table (or
 * adding to it) starts an ORDERING [TableSession] and opens the DEDICATED
 * TableOrderScreen (grid + check panel); paying uses the NORMAL payment flow
 * against the table's existing order (register PAYING seam). This ViewModel
 * owns the floor state, the session hand-offs and the pre-bill print.
 */
@HiltViewModel
class TablesViewModel @Inject constructor(
    val repository: TableServiceRepository,
    val planManager: com.avoqado.pos.core.domain.PlanManager,
    val tableSession: TableSession,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val tables: StateFlow<List<DiningTable>> = repository.tables
    val isLoading: StateFlow<Boolean> = repository.isLoading

    private val _selectedTableId = MutableStateFlow<String?>(null)
    val selectedTableId: StateFlow<String?> = _selectedTableId.asStateFlow()

    private val _actionState = MutableStateFlow<TableActionState>(TableActionState.Idle)
    val actionState: StateFlow<TableActionState> = _actionState.asStateFlow()

    private var pollJob: Job? = null

    private val venueId: String? get() = secureStorage.venueId

    // MARK: - Lifecycle

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                venueId?.let { repository.refresh(it) }
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch { venueId?.let { repository.refresh(it) } }
    }

    // MARK: - Selection

    fun selectTable(tableId: String?) {
        _selectedTableId.value = tableId
        _actionState.value = TableActionState.Idle
    }

    fun selectedTable(): DiningTable? = tables.value.firstOrNull { it.id == _selectedTableId.value }

    // MARK: - Session hand-offs (Square-style)

    /**
     * Free table → open it on the server, activate an ORDERING session and let
     * the caller navigate to the normal Cobrar screen (grid + modifiers). The
     * cart's CTA becomes "Enviar a cocina" while the session is active.
     */
    fun openTableAndStartOrdering(table: DiningTable, covers: Int, onReady: () -> Unit) {
        val vId = venueId ?: return
        // Double-tap guard: one open in flight at a time (avoids opening twice).
        if (_actionState.value is TableActionState.Working) return
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            repository.openTable(vId, table.id, covers).fold(
                onSuccess = { order ->
                    tableSession.start(
                        TableSession.Active(
                            tableId = table.id,
                            tableNumber = table.number,
                            areaName = table.areaName,
                            orderId = order.id,
                            orderNumber = order.orderNumber ?: order.id.takeLast(4),
                            version = order.version,
                            totalCents = 0,
                            mode = TableSession.Mode.ORDERING,
                        ),
                    )
                    repository.refresh(vId)
                    _actionState.value = TableActionState.Idle
                    _selectedTableId.value = null
                    onReady()
                },
                onFailure = { e ->
                    _actionState.value = TableActionState.Error(e.message ?: "No se pudo abrir la mesa")
                },
            )
        }
    }

    /** Occupied table → ORDERING session over its existing order (add a round). */
    fun startOrdering(table: DiningTable, onReady: () -> Unit) {
        val order = table.currentOrder ?: return
        tableSession.start(
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = order.id,
                orderNumber = order.orderNumber,
                version = order.version,
                totalCents = round(order.total * 100).toInt(),
                mode = TableSession.Mode.ORDERING,
            ),
        )
        _selectedTableId.value = null
        onReady()
    }

    /**
     * Occupied table → PAYING session. The Cobrar screen seeds the cart with
     * one "Cuenta Mesa N" line for the order total and the NORMAL payment flow
     * (tip screen, cash/terminal, split) pays the EXISTING order — see the
     * PaymentFlowViewModel seam. On completion the table is released.
     */
    fun startCobrar(table: DiningTable, onReady: () -> Unit) {
        val order = table.currentOrder ?: return

        // Mid-split re-entry guard: this table's PAYING session already tracks
        // the live remainder — restarting it from the floor plan must NOT reset
        // the charge target back to the original total (overcharge).
        val existing = tableSession.current()
        if (existing != null && existing.mode == TableSession.Mode.PAYING && existing.orderId == order.id) {
            _selectedTableId.value = null
            onReady()
            return
        }

        tableSession.start(
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = order.id,
                orderNumber = order.orderNumber,
                version = order.version,
                totalCents = round(order.total * 100).toInt(),
                mode = TableSession.Mode.PAYING,
            ),
        )
        _selectedTableId.value = null
        onReady()
    }

    /**
     * Split-the-bill: a PARTIAL payment against the table's order — remember
     * the remaining balance as the session's new charge target.
     */
    fun updateTableSessionRemaining(remainingCents: Int) {
        tableSession.updateRemaining(remainingCents)
    }

    /**
     * "Dar de cortesía" (Square's comp): the line stays on the check for the
     * kitchen/audit but stops costing money. Requires a reason; the server
     * rejects it once the order is paid.
     */
    fun compItem(table: DiningTable, itemId: String, reason: String) {
        val order = table.currentOrder ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            repository.compItem(vId, order.id, itemId, reason).fold(
                onSuccess = {
                    repository.refresh(vId)
                    _actionState.value = TableActionState.Success("Artículo de cortesía — $reason")
                },
                onFailure = { e ->
                    repository.refresh(vId)
                    _actionState.value = TableActionState.Error(e.message ?: "No se pudo dar de cortesía")
                },
            )
        }
    }

    /**
     * "Anular cuenta" (Square's void-check): cancels the table's open order
     * with the mandatory reason; the server releases the table. Rejected
     * server-side if the order is PAID or a terminal charge is in flight.
     */
    fun anularCuenta(table: DiningTable, reason: String) {
        val order = table.currentOrder ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            repository.cancelOrder(vId, order.id, reason).fold(
                onSuccess = {
                    // Belt & suspenders for servers without the release-on-cancel
                    // change: try clearing explicitly (no-op/failure is fine).
                    repository.clearTable(vId, table.id)
                    repository.refresh(vId)
                    // The cancelled order must never keep steering a session.
                    if (tableSession.current()?.orderId == order.id) tableSession.clear()
                    _actionState.value = TableActionState.Success("Cuenta anulada — Mesa ${table.number} liberada")
                    _selectedTableId.value = null
                },
                onFailure = { e ->
                    repository.refresh(vId)
                    _actionState.value = TableActionState.Error(e.message ?: "No se pudo anular la cuenta")
                },
            )
        }
    }

    fun exitTableMode() {
        tableSession.clear()
    }

    // MARK: - After payment (called from the Checkout completion in PAYING mode)

    /** Releases the table once its order was fully paid through the normal flow. */
    fun finishTableAfterPayment() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            // Server-driven settle check: clearTable succeeds ONLY when the
            // order is fully PAID. If it's still PARTIAL (split-the-bill),
            // keep the session alive so the next payment stays routed to it.
            val cleared = repository.clearTable(vId, session.tableId).isSuccess
            repository.refresh(vId)
            if (cleared) tableSession.clear()
        }
    }

    // MARK: - Pre-bill

    /** Prints the pre-cuenta (items + total, no payment info) on the RECEIPT printer. */
    fun printPreBill(table: DiningTable) {
        val order = table.currentOrder ?: return
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT) ?: run {
            _actionState.value = TableActionState.Error("No hay impresora de recibos configurada")
            return
        }
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            try {
                fun cents(v: Double) = round(v * 100).toInt()
                val receipt = ReceiptData(
                    orderNumber = order.orderNumber,
                    orderType = "Mesa ${table.number} — PRE-CUENTA",
                    items = order.items.map {
                        ReceiptItem(
                            name = it.productName,
                            quantity = it.quantity,
                            unitPrice = cents(it.unitPrice),
                            totalPrice = cents(it.total),
                        )
                    },
                    subtotal = cents(order.total),
                    taxAmount = 0,
                    total = cents(order.total),
                    venueName = secureStorage.venueName ?: "Avoqado",
                    cashierName = order.waiter?.name,
                )
                printerService.printReceipt(receipt, printer)
                _actionState.value = TableActionState.Success("Pre-cuenta impresa")
            } catch (e: Exception) {
                _actionState.value = TableActionState.Error("No se pudo imprimir: ${e.message ?: "desconocido"}")
            }
        }
    }

    companion object {
        private const val POLL_MS = 10_000L
    }
}
