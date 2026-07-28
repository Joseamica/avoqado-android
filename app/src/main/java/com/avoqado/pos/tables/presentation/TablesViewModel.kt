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
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val lanHub: com.avoqado.pos.core.data.lan.LanHubService,
) : ViewModel() {

    init {
        // Hub LAN (PREMIUM OFFLINE_LAN_HUB): se enciende al entrar al plano,
        // que es el único lugar donde reparte algo. Si el plan no lo incluye o
        // no hay venue todavía, no arranca y el POS trabaja como isla — que es
        // el comportamiento de siempre, no una degradación.
        if (planManager.hasFeature("OFFLINE_LAN_HUB")) {
            lanHub.start()
        }
    }

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

            // 🛰️ Hub LAN: pedir la mesa ANTES de abrirla. Sin internet esto es
            // lo único que impide que dos meseros abran la misma mesa a la vez
            // — el server no se entera hasta el replay. Si no hay hub (isla),
            // se sigue igual que siempre: el hub PREVIENE conflictos, no
            // autoriza ventas, así que jamás puede frenar un cobro.
            val lease = lanHub.acquire(
                tableId = table.id,
                staffId = secureStorage.selectedStaffIdForCurrentVenue.orEmpty(),
                staffName = secureStorage.selectedStaffNameForCurrentVenue
                    ?: secureStorage.userFirstName.orEmpty(),
            )
            if (lease is com.avoqado.pos.core.data.lan.LeaseOutcome.Taken) {
                _actionState.value = TableActionState.Error(
                    "${lease.holderName} está atendiendo esta mesa en otro dispositivo",
                )
                return@launch
            }

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
                    // Offline-first Corte B: sin red la mesa se abre PROVISIONAL —
                    // intent al outbox (write-ahead), folio particionado, sesión
                    // con UUID local que el ack promoverá al id del server.
                    // Errores de NEGOCIO (403/409/4xx) NO abren offline.
                    if (isNetworkError(e)) {
                        openTableOffline(vId, table, covers)
                        _actionState.value = TableActionState.Idle
                        _selectedTableId.value = null
                        onReady()
                    } else {
                        _actionState.value = TableActionState.Error(friendlyTableError(e, "No se pudo abrir la mesa"))
                    }
                },
            )
        }
    }

    /** Apertura provisional sin red (el server la confirma en el replay). */
    private suspend fun openTableOffline(vId: String, table: DiningTable, covers: Int) {
        val localOrderId = java.util.UUID.randomUUID().toString()
        val folio = syncOutbox.nextLocalFolio(vId)
        syncOutbox.enqueue(
            vId,
            com.avoqado.pos.core.data.sync.SyncIntentTypes.OPEN_TABLE,
            kotlinx.serialization.json.buildJsonObject {
                put("tableId", kotlinx.serialization.json.JsonPrimitive(table.id))
                put("covers", kotlinx.serialization.json.JsonPrimitive(covers))
                put("localOrderId", kotlinx.serialization.json.JsonPrimitive(localOrderId))
            },
        )
        tableSession.start(
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = localOrderId,
                orderNumber = folio,
                version = 1,
                totalCents = 0,
                mode = TableSession.Mode.ORDERING,
                isProvisional = true,
            ),
        )
        repository.markTableOccupiedLocally(table.id)
        android.util.Log.w("TablesViewModel", "📴 Mesa ${table.number} abierta OFFLINE (folio $folio)")
    }

    /** Red caída/timeout — nunca un rechazo de negocio del server. */
    private fun isNetworkError(e: Throwable): Boolean = when (e) {
        is java.io.IOException -> true // ConnectException, SocketTimeout, UnknownHost...
        is retrofit2.HttpException -> e.code() in 502..504
        else -> false
    }

    /** Occupied table → ORDERING session over its existing order (add a round). */
    fun startOrdering(table: DiningTable, onReady: () -> Unit) {
        // primaryCheck, NO currentOrder: una mesa con el puntero desincronizado
        // se ve ocupada y debe abrirse igual (ver DiningTable.primaryCheck).
        val order = table.primaryCheck ?: return
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

    /** Multi-cheque: abre la sesión sobre UNA cuenta específica de la mesa. */
    fun startOrderingCheck(table: DiningTable, check: com.avoqado.pos.tables.data.OpenCheckSummary, onReady: () -> Unit) {
        tableSession.start(
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = check.id,
                orderNumber = check.orderNumber,
                version = check.version,
                totalCents = round(check.total * 100).toInt(),
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
        val order = table.primaryCheck ?: return

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
        val order = table.primaryCheck ?: return
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
        val order = table.primaryCheck ?: return
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
                    lanHub.release(table.id)
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
        val tableId = tableSession.current()?.tableId
        tableSession.clear()
        // Salir del panel sin cobrar NO debe dejar la mesa retenida: se suelta
        // ya, en vez de hacer esperar 30s al TTL. La cuenta sigue abierta en el
        // server; lo que se libera es el permiso de edición en la LAN.
        if (tableId != null) viewModelScope.launch { lanHub.release(tableId) }
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
            if (cleared) {
                tableSession.clear()
                // 🛰️ Soltar el lease: si no, la mesa queda "de este mesero"
                // hasta que caduque el TTL y nadie más la puede abrir en 30s.
                lanHub.release(session.tableId)
            }
        }
    }

    // MARK: - Acciones masivas del plano (Square: "Seleccionar cheques")

    /** Anula la cuenta de CADA mesa seleccionada (ocupadas) con una razón. */
    fun bulkAnular(tables: List<DiningTable>, reason: String, onDone: (Int, Int) -> Unit) {
        val vId = venueId ?: return
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            tables.forEach { table ->
                val order = table.primaryCheck ?: return@forEach
                repository.cancelOrder(vId, order.id, reason).fold(
                    onSuccess = {
                        repository.clearTable(vId, table.id)
                        if (tableSession.current()?.orderId == order.id) tableSession.clear()
                        lanHub.release(table.id)
                        ok++
                    },
                    onFailure = { fail++ },
                )
            }
            repository.refresh(vId)
            onDone(ok, fail)
        }
    }

    /** Asigna las cuentas de las mesas seleccionadas a otro mesero. */
    fun bulkAsignar(tables: List<DiningTable>, staffId: String, onDone: (Int, Int) -> Unit) {
        val vId = venueId ?: return
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            tables.forEach { table ->
                val order = table.primaryCheck ?: return@forEach
                repository.assignOrder(vId, order.id, staffId).fold(
                    onSuccess = { ok++ },
                    onFailure = { fail++ },
                )
            }
            repository.refresh(vId)
            onDone(ok, fail)
        }
    }

    /** Cortesía de la cuenta COMPLETA de cada mesa seleccionada. */
    fun bulkCortesia(tables: List<DiningTable>, reason: String, onDone: (Int, Int) -> Unit) {
        val vId = venueId ?: return
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            tables.forEach { table ->
                val order = table.primaryCheck ?: return@forEach
                repository.compWholeOrder(vId, order.id, reason).fold(
                    onSuccess = { ok++ },
                    onFailure = { fail++ },
                )
            }
            repository.refresh(vId)
            onDone(ok, fail)
        }
    }

    /** Mueve la cuenta de UNA mesa seleccionada a una mesa libre. */
    fun bulkMover(source: DiningTable, targetTableId: String, onDone: (Boolean, String) -> Unit) {
        val vId = venueId ?: return
        val order = source.primaryCheck ?: return
        viewModelScope.launch {
            repository.moveOrder(vId, order.id, targetTableId).fold(
                onSuccess = {
                    repository.refresh(vId)
                    onDone(true, "Cuenta movida")
                },
                onFailure = { e -> onDone(false, e.message ?: "No se pudo mover") },
            )
        }
    }

    /**
     * "Unir mesas": vuelca las cuentas de las otras mesas seleccionadas en la
     * de [target], y el server cierra las de origen. Sirve para el caso real de
     * dos mesas que se juntan y piden una sola cuenta.
     *
     * 🔴 Secuencial y no en paralelo A PROPÓSITO: el server revalida dentro de
     * la transacción y rechaza fusiones cruzadas concurrentes. En paralelo, dos
     * merges sobre la misma cuenta destino se pisarían y una fallaría con
     * "la cuenta cambió mientras se fusionaba".
     *
     * Se informa cuántas entraron y cuántas no: si una falla (p. ej. traía un
     * descuento), las demás YA se fusionaron y decir solo "no se pudo" seria
     * mentira sobre el estado de las cuentas.
     */
    fun bulkUnir(target: DiningTable, sources: List<DiningTable>, onDone: (Int, Int, String?) -> Unit) {
        val vId = venueId ?: return
        val targetOrder = target.primaryCheck ?: return
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            var firstError: String? = null
            // 🔴 TODAS las cuentas abiertas, no solo la "actual" de cada mesa.
            // Unir el cheque actual dejaba la mesa origen OCUPADA con sus otros
            // cheques —el server la re-apunta a una cuenta hermana— y entonces
            // "unir mesas" no unía nada visible. Se incluyen también las
            // hermanas del DESTINO para que quede UNA sola cuenta.
            val orderIds = (sources + target)
                .flatMap { openOrderIds(it) }
                .distinct()
                .filter { it != targetOrder.id }
            orderIds.forEach { sourceOrderId ->
                repository.mergeOrders(vId, targetOrder.id, sourceOrderId).fold(
                    onSuccess = { ok++ },
                    onFailure = { e ->
                        fail++
                        if (firstError == null) firstError = e.message
                    },
                )
            }
            repository.refresh(vId)
            onDone(ok, fail, firstError)
        }
    }

    /** Cuentas abiertas de la mesa; cae a la actual si el server no mandó la lista. */
    private fun openOrderIds(table: DiningTable): List<String> =
        if (table.openOrders.isNotEmpty()) table.openOrders.map { it.id }
        else listOfNotNull(table.primaryCheck?.id)

    // MARK: - Pre-bill

    /** Prints the pre-cuenta (items + total, no payment info) on the RECEIPT printer. */
    fun printPreBill(table: DiningTable) {
        // Único sitio que SÍ necesita la orden completa: una pre-cuenta sin
        // renglones es un documento de dinero mentiroso, así que no se imprime
        // desde el resumen. Pero se dice POR QUÉ — el `?: return` mudo dejaba
        // al mesero picando un botón muerto.
        val order = table.currentOrder?.takeIf { it.items.isNotEmpty() } ?: run {
            _actionState.value = TableActionState.Error(
                if (table.hasOpenCheck) "Abre la cuenta para imprimir la pre-cuenta"
                else "La mesa no tiene cuenta abierta",
            )
            return
        }
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

    /**
     * Propiedad de mesa: el server responde 403 TABLE_OWNED_BY_OTHER con un
     * mensaje ya humano ("Solo Juan Pérez puede modificar esta mesa") — úsalo
     * tal cual en vez del texto genérico de HttpException.
     */
    private fun friendlyTableError(e: Throwable, fallback: String): String {
        val http = e as? retrofit2.HttpException ?: return e.message ?: fallback
        if (http.code() == 403) {
            val serverMessage = runCatching {
                val body = http.response()?.errorBody()?.string()
                if (body.isNullOrBlank()) null else org.json.JSONObject(body).optString("message").takeIf { it.isNotBlank() }
            }.getOrNull()
            return serverMessage ?: "Esta mesa pertenece a otro mesero"
        }
        return e.message ?: fallback
    }

    companion object {
        private const val POLL_MS = 10_000L
    }
}
