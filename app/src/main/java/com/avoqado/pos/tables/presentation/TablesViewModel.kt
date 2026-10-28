package com.avoqado.pos.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.tables.data.ApiFuenteDeVersiones
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.FloorPlanRepository
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tables.data.TablesVersionPoller
import com.avoqado.pos.tables.domain.FloorLayout
import com.avoqado.pos.tables.domain.FloorLayoutBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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
    private val floorPlanRepository: FloorPlanRepository,
    private val roleManager: com.avoqado.pos.core.domain.RoleManager,
    versionSource: ApiFuenteDeVersiones,
) : ViewModel() {

    private var generacion = 0L
    private var mutacionesEnVuelo = 0
    private fun bloqueoDeSaldo(vId: String, ids: Set<String>, revisarSaldo: Boolean = true): String? {
        if (vId != venueId) return "La cuenta cambió. Vuelve a abrirla."
        return if (revisarSaldo && ids.any {
            com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.cuentaTrabada(secureStorage.giftCardRequestsJson, vId, it)
        }) com.avoqado.pos.giftcards.domain.TextosDeTarjetas.COMPROBANDO_COBRO else null
    }
    private suspend fun <T> mutarCuenta(vId: String, ids: Set<String>, revisarSaldo: Boolean = true,
        bloque: suspend () -> Result<T>): Result<T> {
        bloqueoDeSaldo(vId, ids, revisarSaldo)?.let { return Result.failure(IllegalStateException(it)) }
        return mutar { bloque() }
    }

    private suspend fun <T> mutar(bloque: suspend () -> T): T {
        generacion++; mutacionesEnVuelo++
        try { return bloque() }
        finally { mutacionesEnVuelo--; generacion++ }
    }

    internal suspend fun prepararPreCuenta(table: DiningTable): com.avoqado.pos.tables.data.OrderDetail? {
        val order = table.primaryCheck ?: run { _actionState.value = TableActionState.Error("La mesa no tiene cuenta abierta"); return null }
        val vId = venueId ?: return null
        val epoch = generacion
        val contextoAlSalir = tableSession.contextoActual()
        if (mutacionesEnVuelo > 0) { _actionState.value = TableActionState.Error("La cuenta se está actualizando. Intenta en un momento."); return null }
        val revisionAlSalir = tableSession.revisionParaPreCuenta(vId, setOf(order.id))
        val ids = tableSession.idsParaPreCuenta(vId, order.id)
        val workBefore = try { syncOutbox.trabajoSinResolverDeLaOrden(vId, ids) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        val initialReason = when {
            tableSession.revisionParaPreCuenta(vId, ids) != revisionAlSalir || tableSession.contextoActual() != contextoAlSalir || venueId != vId || generacion != epoch || mutacionesEnVuelo > 0 || repository.tables.value.firstOrNull { it.id == table.id }?.primaryCheck?.id != order.id -> "La cuenta se está actualizando. Intenta en un momento."
            workBefore == null -> "No se pudo revisar si esta mesa tiene cambios sin sincronizar. Intenta de nuevo."
            workBefore.intents > 0 -> "Esta mesa tiene cambios sin sincronizar: la pre-cuenta sale cuando se sincronicen."
            order.isProvisional || revisionAlSalir == null -> "Abre esta cuenta para revisar el saldo antes de imprimir la pre-cuenta."
            else -> null
        }
        if (initialReason != null) { _actionState.value = TableActionState.Error(initialReason); return null }
        val detail = repository.getOrderDetail(vId, order.id).getOrNull()
        if (detail == null || detail.id != order.id) {
            _actionState.value = TableActionState.Error("La cuenta no se pudo leer del servidor: la pre-cuenta sale cuando vuelva la conexión.")
            return null
        }
        val work = try { syncOutbox.trabajoSinResolverDeLaOrden(vId, ids) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        if (tableSession.revisionParaPreCuenta(vId, ids) != revisionAlSalir || tableSession.contextoActual() != contextoAlSalir || venueId != vId || generacion != epoch || mutacionesEnVuelo > 0 || repository.tables.value.firstOrNull { it.id == table.id }?.primaryCheck?.id != order.id) {
            _actionState.value = TableActionState.Error("La cuenta se está actualizando. Intenta en un momento.")
            return null
        }
        val motivo = motivoParaNoImprimirPreCuenta(true, detail, order.id, true, false, work)
        if (motivo != null) { _actionState.value = TableActionState.Error(motivo); return null }
        return detail
    }


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

    // MARK: - Plano (spec 2026-10-09)

    /** Uno por pantalla: el «servidor viejo» se recuerda sólo durante esta visita. Se maneja sólo desde el hilo principal. */
    private val poller = TablesVersionPoller(repository, floorPlanRepository, versionSource)

    /** Pestañas, mesas puestas, elementos, «sin acomodar» y la lista del teléfono, armados de mesas + plano. */
    // La versión del plano que anuncian las mesas se asigna ANTES de publicarlas (Task 5) y también se observa: con ella el
    // armador sabe si el plano guardado está atrasado (C6), y una lista de mesas IGUAL no re-emite, así que sin observarla la
    // vista provisional de un fallo parcial se quedaba aunque las versiones ya coincidieran (auditoría Codex, hallazgo 1).
    // Perezoso y sólo mientras alguien lo mira: Cobrar y la cuenta de la mesa también crean este ViewModel y no dibujan el
    // plano. Arranca armado con lo que ya hay en memoria (sin un primer cuadro vacío al volver a Mesas).
    val layout: StateFlow<FloorLayout> by lazy {
        combine(repository.tables, floorPlanRepository.plan, repository.floorPlanVersionFromTablesFlow) { t, p, v ->
            FloorLayoutBuilder.build(t, p, v)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                FloorLayoutBuilder.build(repository.tables.value, floorPlanRepository.plan.value, repository.floorPlanVersionFromTables),
            )
    }

    private val _rememberedTab = MutableStateFlow<String?>(null)

    /** La pestaña de área que este aparato dejó abierta en este venue (spec §4.2). */
    val rememberedTab: StateFlow<String?> = _rememberedTab.asStateFlow()

    fun selectTab(key: String) {
        _rememberedTab.value = key
        venueId?.let { secureStorage.setFloorAreaTab(it, key) }
    }

    /** Sólo decide si se ve el aviso «Arma el plano en tu computadora…» cuando no hay plano. */
    val canConfigureTables: Boolean
        get() = roleManager.canConfigureTables

    /**
     * Aviso para el mesero, por el Snackbar del plano.
     *
     * Las acciones masivas informan aquí su resultado ("3 anuladas, 1 falló").
     * Iban en Toast, que el sistema dibuja FUERA de la app: en una Sunmi con
     * pantalla de cliente no hay garantía de en cuál de las dos aparece, y un
     * resultado de dinero que nadie ve es peor que no informar.
     */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    fun showMessage(text: String) {
        _actionMessage.value = text
        _actionIsError.value = false
        _actionHint.value = null
    }

    /**
     * Un FALLO se ve como fallo (círculo rojo), no con el mismo aviso gris de
     * "Cuentas unidas". Espejo de `TableOrderViewModel.showError`.
     *
     * @param hint qué HACER. El motivo dice qué pasó; con fila en la caja lo que
     *   hace falta es el siguiente movimiento.
     */
    fun showError(text: String, hint: String? = null) {
        _actionMessage.value = text
        _actionIsError.value = true
        _actionHint.value = hint
    }

    private val _actionIsError = MutableStateFlow(false)
    val actionIsError: StateFlow<Boolean> = _actionIsError.asStateFlow()

    private val _actionHint = MutableStateFlow<String?>(null)
    val actionHint: StateFlow<String?> = _actionHint.asStateFlow()

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    private val _selectedTableId = MutableStateFlow<String?>(null)
    val selectedTableId: StateFlow<String?> = _selectedTableId.asStateFlow()

    private val _actionState = MutableStateFlow<TableActionState>(TableActionState.Idle)
    val actionState: StateFlow<TableActionState> = _actionState.asStateFlow()

    private var aperturaGeneration = 0L
    private var pollJob: Job? = null

    private val venueId: String? get() = secureStorage.venueId

    // MARK: - Lifecycle

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var enPantalla: String? = null
            while (isActive) {
                val vId = venueId
                if (vId != null && vId == enPantalla) {
                    // Cada 10 s, sólo «¿cambió algo?»; se baja sólo lo que cambió (spec §4.1).
                    poller.consultar(vId)
                } else if (vId != null) {
                    // Al entrar —o si cambió la sucursal—: lo guardado de inmediato, luego /tables y (si cambió o falta)
                    // /floor-plan. Nunca se sigue consultando la sucursal anterior (enmienda A8).
                    enPantalla = vId
                    _rememberedTab.value = secureStorage.floorAreaTab(vId)
                    poller.entrar(vId)
                }
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** El botón de refrescar: mesas y plano completos, sin mirar versiones. */
    fun refresh() {
        viewModelScope.launch { venueId?.let { poller.refrescarTodo(it) } }
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
        val original = tableSession.current()
        val sessionEpoch = tableSession.contextoActual()
        val apertura = ++aperturaGeneration
        fun vigente(expected: TableSession.Active?) = apertura == aperturaGeneration && venueId == vId &&
            tableSession.contextoActual() == sessionEpoch && tableSession.current() == expected
        fun vigenteAbierta(expected: TableSession.Active): Boolean {
            val current = tableSession.current() ?: return false
            return apertura == aperturaGeneration && venueId == vId && tableSession.contextoActual() == sessionEpoch &&
                current.tableId == expected.tableId && current.mode == expected.mode && current.idsDeLaOrden.any { it in expected.idsDeLaOrden }
        }
        fun desistir() {
            if (apertura == aperturaGeneration && _actionState.value is TableActionState.Working) _actionState.value = TableActionState.Idle
        }
        _actionState.value = TableActionState.Working
        viewModelScope.launch {
            if (!vigente(original)) { desistir(); return@launch }
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
            if (!vigente(original)) { desistir(); return@launch }
            if (lease is com.avoqado.pos.core.data.lan.LeaseOutcome.Taken) {
                _actionState.value = TableActionState.Error(
                    "${lease.holderName} está atendiendo esta mesa en otro dispositivo",
                )
                return@launch
            }

            mutar { repository.openTable(vId, table.id, covers) }.fold(
                onSuccess = { order ->
                    if (!vigente(original)) { desistir(); return@launch }
                    val opened = TableSession.Active(
                            tableId = table.id,
                            tableNumber = table.number,
                            areaName = table.areaName,
                            orderId = order.id,
                            orderNumber = order.orderNumber ?: order.id.takeLast(4),
                            version = order.version,
                            totalCents = 0,
                            mode = TableSession.Mode.ORDERING,
                    )
                    if (!tableSession.startIfCurrent(opened, original, vId, sessionEpoch)) { desistir(); return@launch }
                    repository.refresh(vId)
                    if (!vigenteAbierta(opened)) { desistir(); return@launch }
                    _actionState.value = TableActionState.Idle
                    _selectedTableId.value = null
                    onReady()
                },
                onFailure = { e ->
                    if (!vigente(original)) { desistir(); return@launch }
                    // Offline-first Corte B: sin red la mesa se abre PROVISIONAL —
                    // intent al outbox (write-ahead), folio particionado, sesión
                    // con UUID local que el ack promoverá al id del server.
                    // Errores de NEGOCIO (403/409/4xx) NO abren offline.
                    if (isNetworkError(e)) {
                        var aperturaLocal: TableSession.Active? = null
                        try {
                            val opened = openTableOffline(vId, table, covers, original, sessionEpoch,
                                onActivated = { aperturaLocal = it }, sigueOriginal = { vigente(original) })
                            if (opened == null || !vigenteAbierta(opened)) { desistir(); return@launch }
                            _actionState.value = TableActionState.Idle
                            _selectedTableId.value = null
                            onReady()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (_: Exception) {
                            if (!vigente(original) && aperturaLocal?.let { vigenteAbierta(it) } != true) { desistir(); return@launch }
                            _actionState.value = TableActionState.Error("No se pudo guardar la mesa en este aparato. Libera espacio e inténtalo de nuevo.")
                        }
                    } else {
                        _actionState.value = TableActionState.Error(friendlyTableError(e, "No se pudo abrir la mesa"))
                    }
                },
            )
        }
    }

    /** Apertura provisional sin red (el server la confirma en el replay). */
    private suspend fun openTableOffline(vId: String, table: DiningTable, covers: Int,
        original: TableSession.Active?, sessionEpoch: Long,
        onActivated: (TableSession.Active) -> Unit, sigueOriginal: () -> Boolean): TableSession.Active? {
        val localOrderId = java.util.UUID.randomUUID().toString()
        val folio = syncOutbox.nextLocalFolio(vId)
        if (!sigueOriginal()) return null
        syncOutbox.enqueue(
            vId,
            com.avoqado.pos.core.data.sync.SyncIntentTypes.OPEN_TABLE,
            kotlinx.serialization.json.buildJsonObject {
                put("tableId", kotlinx.serialization.json.JsonPrimitive(table.id))
                put("covers", kotlinx.serialization.json.JsonPrimitive(covers))
                put("localOrderId", kotlinx.serialization.json.JsonPrimitive(localOrderId))
            },
        )
        val opened = TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = localOrderId,
                orderNumber = folio,
                version = 1,
                totalCents = 0,
                mode = TableSession.Mode.ORDERING,
                isProvisional = true,
                localOrderId = localOrderId,
        )
        if (!tableSession.startIfCurrent(opened, original, vId, sessionEpoch)) return null
        onActivated(opened)
        mutar { repository.markTableOccupiedLocally(table.id, covers, vId) }
        android.util.Log.w("TablesViewModel", "📴 Mesa ${table.number} abierta OFFLINE (folio $folio)")
        return opened
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
        val scopedVenue = venueId ?: return
        val restored = tableSession.abrirDesdePiso(scopedVenue,
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = order.id,
                orderNumber = order.orderNumber,
                version = order.version,
                totalCents = round(order.total * 100).toInt(),
                mode = TableSession.Mode.ORDERING,
                isProvisional = order.isProvisional,
                meseroNombre = order.waiterName?.takeIf { it.isNotBlank() },
            ),
        ) ?: return
        _selectedTableId.value = null
        onReady()
    }

    /** Multi-cheque: abre la sesión sobre UNA cuenta específica de la mesa. */
    fun startOrderingCheck(table: DiningTable, check: com.avoqado.pos.tables.data.OpenCheckSummary, onReady: () -> Unit) {
        val scopedVenue = venueId ?: return
        val restored = tableSession.abrirDesdePiso(scopedVenue,
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = check.id,
                orderNumber = check.orderNumber,
                version = check.version,
                totalCents = round(check.total * 100).toInt(),
                mode = TableSession.Mode.ORDERING,
                isProvisional = check.isProvisional,
                meseroNombre = check.waiterName?.takeIf { it.isNotBlank() },
            ),
        ) ?: return
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
        val vId = venueId ?: return
        if (_actionState.value is TableActionState.Working) return
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            val epoch = generacion
            val sessionAlSalir = tableSession.current()
            val contextoAlSalir = tableSession.contextoActual()
            val existingForIds = sessionAlSalir?.takeIf { it.orderId == order.id }
            val work = try { syncOutbox.trabajoSinResolverDeLaOrden(vId, existingForIds?.idsDeLaOrden ?: setOf(order.id)) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            val reason = when {
                tableSession.contextoActual() != contextoAlSalir || venueId != vId || tableSession.current() != sessionAlSalir || generacion != epoch || mutacionesEnVuelo > 0 || repository.tables.value.firstOrNull { it.id == table.id }?.primaryCheck?.id != order.id -> "La cuenta se está actualizando. Intenta en un momento."
                work == null -> "No se pudo revisar si esta mesa tiene cambios sin sincronizar. Intenta de nuevo."
                work.intents > 0 -> "Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen."
                order.isProvisional || tableSession.requiereRevisarSaldo(vId, existingForIds?.idsDeLaOrden ?: setOf(order.id)) -> "Abre esta cuenta para revisar el saldo antes de cobrar."
                else -> null
            }
            if (reason != null) { _actionState.value = TableActionState.Error(reason); return@launch }
            // Mid-split re-entry guard: this table's PAYING session already tracks
            // the live remainder — restarting it from the floor plan must NOT reset
            // the charge target back to the original total (overcharge).
            val existing = tableSession.current()
            if (existing != null && existing.mode == TableSession.Mode.PAYING && existing.orderId == order.id) {
                // El piso conserva el saldo propio, pero no acredita si hubo abonos para separar el cargo.
                // D4 (ronda 2 de Codex): el resumen ES la lectura de esta cuenta; sin mesero ahí, no se conserva el viejo.
                tableSession.start(existing.copy(serviceChargeCents = 0,
                    meseroNombre = order.waiterName?.takeIf { it.isNotBlank() }))
                _selectedTableId.value = null
                _actionState.value = TableActionState.Idle
                onReady()
                return@launch
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
                    // Sin cargo: sólo el cheque abierto garantizado conoce sus abonos.
                    mode = TableSession.Mode.PAYING,
                    isProvisional = order.isProvisional,
                    // D4: el «Atendió» del ticket es el mesero dueño de la cuenta, también sin red (resumen del plano).
                    meseroNombre = order.waiterName?.takeIf { it.isNotBlank() },
                ),
            )
            _selectedTableId.value = null
            _actionState.value = TableActionState.Idle
            onReady()
        }
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
        bloqueoDeSaldo(vId, setOf(order.id))?.let { _actionState.value = TableActionState.Error(it); return }
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            mutarCuenta(vId, setOf(order.id)) { repository.compItem(vId, order.id, itemId, reason) }.fold(
                onSuccess = {
                    repository.refresh(vId)
                    _actionState.value = TableActionState.Success("Artículo de cortesía — $reason")
                },
                onFailure = { e ->
                    repository.refresh(vId)
                    _actionState.value = TableActionState.Error(com.avoqado.pos.core.data.network.ServerErrorText.humanize(e, "No se pudo dar de cortesía"))
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
        bloqueoDeSaldo(vId, setOf(order.id))?.let { _actionState.value = TableActionState.Error(it); return }
        val sessionEpoch = tableSession.contextoActual()
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            mutarCuenta(vId, setOf(order.id)) { repository.cancelOrder(vId, order.id, reason) }.fold(
                onSuccess = {
                    // Belt & suspenders for servers without the release-on-cancel
                    // change: try clearing explicitly (no-op/failure is fine).
                    mutarCuenta(vId, setOf(order.id)) { repository.clearTable(vId, table.id) }
                    repository.refresh(vId)
                    // The cancelled order must never keep steering a session.
                    tableSession.finalizar(vId, setOf(order.id), sessionEpoch)
                    lanHub.release(table.id)
                    _actionState.value = TableActionState.Success("Cuenta anulada — Mesa ${table.number} liberada")
                    _selectedTableId.value = null
                },
                onFailure = { e ->
                    repository.refresh(vId)
                    // Un cobro con tarjeta vivo tiene texto propio: dice QUÉ hacer (cancelarlo o
                    // esperar), no sólo qué pasó.
                    _actionState.value = TableActionState.Error(
                        com.avoqado.pos.core.data.network.ServerErrorText.humanizeCancelacionDeCuenta(e, "No se pudo anular la cuenta"),
                    )
                },
            )
        }
    }

    fun exitTableMode() {
        val current = tableSession.current()
        val tableId = current?.tableId
        val scopedVenue = venueId
        if (current != null && scopedVenue != null) tableSession.estacionar(scopedVenue, current)
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
        bloqueoDeSaldo(vId, session.idsDeLaOrden)?.let { _actionState.value = TableActionState.Error(it); return }
        val sessionEpoch = tableSession.contextoActual()
        viewModelScope.launch {
            // Server-driven settle check: clearTable succeeds ONLY when the
            // order is fully PAID. If it's still PARTIAL (split-the-bill),
            // keep the session alive so the next payment stays routed to it.
            val cleared = mutarCuenta(vId, session.idsDeLaOrden) { repository.clearTable(vId, session.tableId) }.isSuccess
            repository.refresh(vId)
            if (cleared) {
                tableSession.finalizar(vId, session.idsDeLaOrden, sessionEpoch)
                // 🛰️ Soltar el lease: si no, la mesa queda "de este mesero"
                // hasta que caduque el TTL y nadie más la puede abrir en 30s.
                lanHub.release(session.tableId)
            }
        }
    }

    // MARK: - Acciones masivas del plano (Square: "Seleccionar cheques")

    /** Anula la cuenta de CADA mesa seleccionada (ocupadas) con una razón. */
    fun bulkAnular(tables: List<DiningTable>, reason: String, onDone: (Int, Int, String?) -> Unit) {
        val vId = venueId ?: return
        val sessionEpoch = tableSession.contextoActual()
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            // El MOTIVO de la primera que falló: "3 anuladas, 1 falló" sin decir por qué deja al
            // mesero sin su siguiente movimiento — y el motivo más probable es un cobro vivo.
            var primerMotivo: String? = null
            tables.forEach { table ->
                val order = table.primaryCheck ?: return@forEach
                bloqueoDeSaldo(vId, setOf(order.id))?.let { if (primerMotivo == null) primerMotivo = it; fail++; return@forEach }
                mutarCuenta(vId, setOf(order.id)) { repository.cancelOrder(vId, order.id, reason) }.fold(
                    onSuccess = {
                        mutarCuenta(vId, setOf(order.id)) { repository.clearTable(vId, table.id) }
                        tableSession.finalizar(vId, setOf(order.id), sessionEpoch)
                        lanHub.release(table.id)
                        ok++
                    },
                    onFailure = { e ->
                        fail++
                        if (primerMotivo == null) {
                            primerMotivo = com.avoqado.pos.core.data.network.ServerErrorText
                                .humanizeCancelacionDeCuenta(e, "No se pudo anular la cuenta")
                        }
                    },
                )
            }
            repository.refresh(vId)
            onDone(ok, fail, primerMotivo)
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
                mutarCuenta(vId, setOf(order.id), revisarSaldo = false) { repository.assignOrder(vId, order.id, staffId) }.fold(
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
                bloqueoDeSaldo(vId, setOf(order.id))?.let { fail++; return@forEach }
                mutarCuenta(vId, setOf(order.id)) { repository.compWholeOrder(vId, order.id, reason) }.fold(
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
        val sessionEpoch = tableSession.contextoActual()
        viewModelScope.launch {
            mutarCuenta(vId, setOf(order.id), revisarSaldo = false) { repository.moveOrder(vId, order.id, targetTableId) }.fold(
                onSuccess = {
                    tableSession.finalizar(vId, setOf(order.id), sessionEpoch)
                    repository.refresh(vId)
                    onDone(true, "Cuenta movida")
                },
                onFailure = { e -> onDone(false, com.avoqado.pos.core.data.network.ServerErrorText.humanize(e, "No se pudo mover")) },
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
        val sessionEpoch = tableSession.contextoActual()
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
                mutarCuenta(vId, setOf(targetOrder.id, sourceOrderId)) { repository.mergeOrders(vId, targetOrder.id, sourceOrderId) }.fold(
                    onSuccess = {
                        tableSession.finalizar(vId, setOf(sourceOrderId), sessionEpoch)
                        tableSession.marcarSaldoDesconocido(vId, setOf(targetOrder.id), sessionEpoch)
                        ok++
                    },
                    onFailure = { e ->
                        fail++
                        if (firstError == null) {
                            firstError = com.avoqado.pos.core.data.network.ServerErrorText
                                .humanizeCancelacionDeCuenta(e, "No se pudo fusionar")
                        }
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
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT) ?: run {
            _actionState.value = TableActionState.Error("No hay impresora de recibos en esta caja. Agrégala en Más › Impresoras y cajón.")
            return
        }
        viewModelScope.launch {
            _actionState.value = TableActionState.Working
            val order = prepararPreCuenta(table) ?: return@launch
            try {
                fun cents(v: Double) = round(v * 100).toInt()
                val subtotal = cents(order.subtotal)
                val balance = order.preBillBalance
                val receipt = ReceiptData(
                    orderNumber = order.orderNumber,
                    orderType = "Mesa ${table.number} - PRE-CUENTA",
                    items = renglonesDePreCuenta(order.items),
                    subtotal = subtotal,
                    taxAmount = cents(order.addedTaxAmount),
                    discountAmount = cents(order.discountAmount).coerceIn(0, subtotal.coerceAtLeast(0)).takeIf { it > 0 },
                    total = balance?.orderTotalCents ?: cents(order.total),
                    tipAmount = balance?.tipAmountCents?.takeIf { it > 0 },
                    serviceChargeAmount = cents(order.serviceChargeAmount).takeIf { it > 0 },
                    venueName = secureStorage.venueName ?: "Avoqado",
                    cashierName = order.waiter?.name,
                    isPreBill = true,
                    paidAmount = balance?.totalPaidCents,
                    remainingAmount = balance?.remainingBalanceCents,
                )
                printerService.printReceipt(receipt, printer)
                _actionState.value = TableActionState.Success("Pre-cuenta impresa")
            } catch (e: Exception) {
                _actionState.value = TableActionState.Error("No se pudo imprimir: ${com.avoqado.pos.core.data.network.ServerErrorText.humanize(e, "desconocido")}")
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
