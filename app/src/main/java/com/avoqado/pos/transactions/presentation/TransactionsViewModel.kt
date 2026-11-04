package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.avoqado.pos.core.domain.refresh.CargasDeLaPantalla
import com.avoqado.pos.core.domain.refresh.EstadoParaRecargar
import com.avoqado.pos.core.domain.refresh.PantallaQueSeRecarga
import com.avoqado.pos.core.domain.refresh.ReglaDeRecarga
import com.avoqado.pos.core.domain.refresh.RefreshOutcome
import com.avoqado.pos.transactions.data.model.ConsultaDeVentas
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.transactions.data.model.PendingCustomerAssignment
import kotlinx.coroutines.CancellationException
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.transactions.data.ExchangeOrderNotCharged
import com.avoqado.pos.transactions.data.RefundItem
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.ExchangePendingStore
import com.avoqado.pos.transactions.data.DesenlaceDeSaldo
import com.avoqado.pos.transactions.data.PendingRefund
import com.avoqado.pos.transactions.data.RefundPendingStore
import com.avoqado.pos.transactions.data.SaldoAFavorEnvio
import com.avoqado.pos.transactions.data.enviarReembolsoPendiente
import com.avoqado.pos.transactions.presentation.exchange.exchangeStoreCreditNotice
import com.avoqado.pos.transactions.data.model.AmountOperator
import com.avoqado.pos.transactions.data.model.ResultadoDeReimpresion
import com.avoqado.pos.transactions.data.model.ResultadoLigaRecibo
import com.avoqado.pos.transactions.data.model.TonoDelAviso
import com.avoqado.pos.transactions.data.model.PaymentMethodDisplay
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.avisoDeReimpresion
import com.avoqado.pos.transactions.data.model.tonoDeReimpresion
import com.avoqado.pos.transactions.data.model.TransactionActiveFilter
import com.avoqado.pos.transactions.data.model.TransactionFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import javax.inject.Inject
import com.avoqado.pos.core.util.aCentavos
import com.avoqado.pos.transactions.presentation.exchange.ExchangeEntry
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.core.domain.ActionVisibility

// MARK: - Refund UI State

sealed interface RefundUiState {
    data object Idle : RefundUiState
    data object Loading : RefundUiState
    data class Success(val message: String) : RefundUiState
    data class Error(val message: String) : RefundUiState
}

data class PendingExchangeNotice(val paymentId: String, val message: String)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    val refundRepository: RefundRepository,
    /**
     * 🔴 Inyectado a propósito aunque el flujo de reembolso ya NO lo use.
     *
     * Es el ancla del test que verifica que nadie vuelva a escribir el egreso desde
     * el cliente (`RefundCashDrawerOwnershipTest`): sin la dependencia inyectada, el
     * `coVerify(exactly = 0)` no vigilaría nada. Si algún día se quita de aquí, hay
     * que quitar también ese test — no al revés.
     */
    val cashDrawerRepository: CashDrawerRepository,
    /** Para abrir la devolución de un cobro con tarjeta en una terminal física. */
    val terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService,
    val roleManager: RoleManager,
    /**
     * Sólo para saber si el local activó el PIN de autorización de gerente: es
     * lo que decide si "Emitir reembolso" se esconde (como hoy) o se ve con
     * candado para que el 403 pueda abrir el teclado.
     */
    val tpvSettingsRepository: com.avoqado.pos.tpvsettings.data.TpvSettingsRepository,
    /**
     * Para pedir el PIN de gerente AL TOCAR el botón bloqueado, en vez de
     * esperar al 403 —que llega cuando el formulario ya está lleno—.
     */
    private val managerOverrideCoordinator: com.avoqado.pos.core.data.network.ManagerOverrideCoordinator,
    private val orderRepository: OrderRepository,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    refreshGateFactory: RefreshGateFactory,
    private val connectivityMonitor: ConnectivityMonitor,
    private val exchangePendingStore: ExchangePendingStore,
    /** Reembolso a saldo a favor sin confirmar (A3). `null` sólo en pruebas que no lo usan: no se reenvía nada. */
    private val refundPendingStore: RefundPendingStore? = null,
    /** D18 r3: la regla única de recarga automática (T1, T2, T3). `null` sólo en pruebas que no la usan. */
    private val reglaDeRecarga: ReglaDeRecarga? = null,
) : ViewModel() {

    val transactions = repository.transactions
    val isLoading = repository.isLoading
    val isLoadingMore = repository.isLoadingMore

    /** D18: por qué falló la última carga (`null` si salió bien). Sin lista de la consulta vigente, la pantalla lo dice. */
    val falloDeCarga = repository.falloDeCarga

    /** D18 (Codex r1 #1 y #3): de qué consulta es la lista guardada. */
    val consultaDeLaLista = repository.consultaDeLaLista

    private val _pendingExchangeNotices = MutableStateFlow<List<PendingExchangeNotice>>(emptyList())
    val pendingExchangeNotices = _pendingExchangeNotices.asStateFlow()
    private var pendingExchangeScope: Pair<String, String>? = null
    private var pendingExchangeGeneration = 0L

    fun refreshPendingExchanges() = viewModelScope.launch {
        val generation = ++pendingExchangeGeneration
        _pendingExchangeNotices.value = emptyList()
        pendingExchangeScope = null
        val venue = secureStorage.venueId ?: return@launch
        val actor = secureStorage.userId ?: return@launch
        try {
            val pending = exchangePendingStore.forActor(venue, actor)
            if (generation != pendingExchangeGeneration || venue != secureStorage.venueId || actor != secureStorage.userId)
                return@launch
            pendingExchangeScope = venue to actor
            _pendingExchangeNotices.value = pending.map {
                PendingExchangeNotice(it.originalPaymentId,
                    TextosCambio.CAMBIO_SIN_CONFIRMAR.replace("{folio}", it.originalOrderNumber))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("Transactions", "Could not read pending exchange notices", e) }
    }

    fun openPendingExchange(paymentId: String) {
        if (pendingExchangeScope != (secureStorage.venueId to secureStorage.userId) ||
            _pendingExchangeNotices.value.none { it.paymentId == paymentId }) return
        selectTransaction(paymentId)
    }

    // Search
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _consultaVigente = MutableStateFlow(repository.consultaPara(null))

    /**
     * D18 (Codex r1 #1 y #3): la consulta de esta pantalla —sucursal, quién y búsqueda—. La lista sólo se enseña si es de
     * ESTA consulta: cambia al pedir la primera página y en cuanto sale una búsqueda, aunque el gate no la pida.
     */
    val consultaVigente: StateFlow<ConsultaDeVentas> = _consultaVigente.asStateFlow()

    /** La consulta de la última primera página que salió (para saber si la que se unió en vuelo era otra búsqueda). */
    private var ultimaPedida: ConsultaDeVentas? = null

    /** La lista guardada, sólo si es de la consulta vigente (D18). */
    private fun listaVigente(): List<Transaction> =
        if (repository.consultaDeLaLista.value == _consultaVigente.value) transactions.value else emptyList()

    // Filters
    private val _filters = MutableStateFlow(TransactionFilters())
    val filters: StateFlow<TransactionFilters> = _filters.asStateFlow()

    private val _activeFilter = MutableStateFlow<TransactionActiveFilter?>(null)
    val activeFilter: StateFlow<TransactionActiveFilter?> = _activeFilter.asStateFlow()

    // Detail
    private val _selectedTransactionId = MutableStateFlow<String?>(null)
    val selectedTransactionId: StateFlow<String?> = _selectedTransactionId.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    private data class DetailScope(val actorId: String?, val venueId: String?, val paymentId: String, val generation: Long)
    private var detailGeneration = 0L
    private var detailScope: DetailScope? = null
    private fun current(scope: DetailScope) = detailScope == scope &&
        secureStorage.userId == scope.actorId && secureStorage.venueId == scope.venueId &&
        _selectedTransactionId.value == scope.paymentId

    private val _customerCandidate = MutableStateFlow<Customer?>(null)
    val customerCandidate = _customerCandidate.asStateFlow()
    private val _customerPickerVisible = MutableStateFlow(false)
    val customerPickerVisible = _customerPickerVisible.asStateFlow()
    private val _isAssigningCustomer = MutableStateFlow(false)
    val isAssigningCustomer = _isAssigningCustomer.asStateFlow()
    private val _customerAssignmentMessage = MutableStateFlow<String?>(null)
    val customerAssignmentMessage = _customerAssignmentMessage.asStateFlow()
    private val _customerAssignmentSucceeded = MutableStateFlow(false)
    val customerAssignmentSucceeded = _customerAssignmentSucceeded.asStateFlow()

    val canAssignCustomer: Boolean
        get() = detailScope?.let { !it.actorId.isNullOrBlank() && !it.venueId.isNullOrBlank() && current(it) } == true &&
            roleManager.canAttachCustomer &&
            roleManager.hasVenuePermission("customers:read") &&
            _selectedTransaction.value?.let { it.canAssignCustomer == true && it.customerId == null } == true

    val canCreateCustomerForAssignment: Boolean
        get() = canAssignCustomer && roleManager.hasVenuePermission("customers:create")

    val customerConnection = connectivityMonitor.isConnected
    val customerServerReachable = connectivityMonitor.isServerReachable
    val customerAssignmentOnline: Boolean get() = connectivityMonitor.isOnline()

    private val _exchangeTransaction = MutableStateFlow<Transaction?>(null)
    val exchangeTransaction = _exchangeTransaction.asStateFlow()
    private val _exchangeMessage = MutableStateFlow<String?>(null)
    val exchangeMessage = _exchangeMessage.asStateFlow()
    private var exchangeAuthorizing = false

    fun onExchangeTapped() {
        val transaction = _selectedTransaction.value ?: return
        val scope = detailScope ?: return
        val decision = ExchangeEntry.decide(transaction, tpvSettingsRepository.exchangesEnabled.value,
            connectivityMonitor.isOnline(), roleManager.canIssueRefund, tpvSettingsRepository.managerPinOverrideEnabled.value)
        val visibility = roleManager.visibilityOf(roleManager.canIssueRefund, tpvSettingsRepository.managerPinOverrideEnabled.value)
        if (!decision.visible || visibility == ActionVisibility.HIDDEN || !current(scope) || exchangeAuthorizing) return
        if (decision.blockedReason != null) { _exchangeMessage.value = decision.blockedReason; return }
        if (visibility == ActionVisibility.ALLOWED) { _exchangeTransaction.value = transaction; return }
        exchangeAuthorizing = true
        viewModelScope.launch {
            try {
                val authorized = managerOverrideCoordinator.preauthorize(REFUND_PERMISSION)
                if (authorized && current(scope) &&
                    _selectedTransaction.value == transaction && connectivityMonitor.isOnline() &&
                    tpvSettingsRepository.exchangesEnabled.value) _exchangeTransaction.value = transaction
                else if (authorized) managerOverrideCoordinator.discardPreauthorized(REFUND_PERMISSION)
            } finally { exchangeAuthorizing = false }
        }
    }

    fun dismissExchange() {
        managerOverrideCoordinator.discardPreauthorized(REFUND_PERMISSION)
        _exchangeTransaction.value = null
    }
    fun clearExchangeMessage() { _exchangeMessage.value = null }

    // MARK: - Reembolso a saldo a favor sin confirmar (spec 2026-10-30 §7; A3)
    //
    // 🔴 Se recupera al ABRIR LA VENTA, no la hoja: con todo devuelto el botón de Reembolso sale apagado y la hoja no se abre
    // (Codex plan r3 h3). Mientras haya un pendiente, «Emitir reembolso» no deja empezar otro con una llave nueva.

    private val _reembolsoPendiente = MutableStateFlow<PendingRefund?>(null)
    val reembolsoPendiente = _reembolsoPendiente.asStateFlow()
    /** Por qué la venta que se tocó no abrió (sin red o el detalle falló). `null` si abrió o aún no se sabe. */
    private val _detalleNoDisponible = MutableStateFlow<String?>(null)
    val detalleNoDisponible = _detalleNoDisponible.asStateFlow()
    private val _avisoDeReembolso = MutableStateFlow<String?>(null)
    val avisoDeReembolso = _avisoDeReembolso.asStateFlow()
    /** Ventas con un envío o reenvío a saldo en curso: no se manda otro de esa venta mientras tanto (M4: por venta, no global). */
    private val enVuelo = MutableStateFlow<Set<String>>(emptySet())
    private fun marcarEnVuelo(paymentId: String): Boolean {
        while (true) {
            val antes = enVuelo.value
            if (paymentId in antes) return false
            if (enVuelo.compareAndSet(antes, antes + paymentId)) return true
        }
    }
    private fun soltarEnVuelo(paymentId: String) = enVuelo.update { it - paymentId }

    /**
     * «Emitir reembolso» se apaga con un saldo a favor de la venta abierta sin confirmar en el aparato O en vuelo (ronda 2): al cerrar la
     * hoja justo al tocar «Reembolsar», la relectura puede llegar antes de que DataStore termine de escribir.
     */
    val reembolsoBloqueado: StateFlow<Boolean> = combine(_reembolsoPendiente, enVuelo, _selectedTransactionId) { pendiente, vuelo, id ->
        pendiente != null || (id != null && id in vuelo)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 🔴 La guarda de TODA devolución que manda la hoja (efectivo, transferencia, tarjeta, terminal y saldo a favor): con un saldo a favor
     * de esa venta sin confirmar (en el aparato) o en vuelo, no se manda otro; ése se confirma con su misma llave («Confirmar estado»),
     * que no pasa por aquí. Si había uno, el detalle lo muestra. `null` = se puede mandar.
     */
    suspend fun motivoParaNoReembolsar(paymentId: String): String? {
        if (paymentId in enVuelo.value) return TextosCambio.REEMBOLSO_SIN_CONFIRMAR
        val store = refundPendingStore ?: return null
        val venue = secureStorage.venueId ?: return null
        val pending = try {
            store.pendiente(venue, paymentId)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return TextosCambio.REEMBOLSO_RECUPERACION_FALLIDA }
        if (pending == null) return null
        detalleAbierto(paymentId)?.let { versionDelPendiente.incrementAndGet(); _reembolsoPendiente.value = pending }
        return TextosCambio.REEMBOLSO_SIN_CONFIRMAR
    }
    /** Cada lectura del almacén y cada desenlace avanza esto: una lectura que llega tarde no pisa un desenlace más nuevo. */
    private val versionDelPendiente = java.util.concurrent.atomic.AtomicLong()

    /** Lee lo pendiente de la venta abierta y, si [reenviar] y hay red, lo reenvía con su MISMA llave. */
    private suspend fun recuperarReembolsoPendiente(scope: DetailScope, reenviar: Boolean) {
        val selloDelToque = secureStorage.generacion // Antes de leer el disco: el envío sale con la sesión de quien tocó, o no sale.
        val store = refundPendingStore ?: return
        val venue = scope.venueId ?: return
        val version = versionDelPendiente.incrementAndGet()
        val pending = try {
            store.pendiente(venue, scope.paymentId)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (current(scope)) _avisoDeReembolso.value = TextosCambio.REEMBOLSO_RECUPERACION_FALLIDA
            return
        }
        if (!current(scope) || version != versionDelPendiente.get()) return
        _reembolsoPendiente.value = pending
        if (pending == null || !reenviar) return
        if (!marcarEnVuelo(scope.paymentId)) return
        try {
            if (!connectivityMonitor.isOnline()) { _avisoDeReembolso.value = TextosCambio.SALDO_SIN_RED; return }
            _avisoDeReembolso.value = null
            val desenlace = enviarReembolsoPendiente(refundRepository, store, pending, selloDelToque)
            detalleAbierto(scope.paymentId)?.let { aplicarDesenlace(it, desenlace, reenvio = true) }
        } finally { soltarEnVuelo(scope.paymentId) }
    }

    /**
     * 🔴 El envío a saldo de la hoja corre AQUÍ, no en la hoja (I2): si la cajera la cierra con el POST en vuelo, el envío sigue, el
     * detalle relee lo pendiente al cerrarla (`dismissIssueRefundSheet`) y, al terminar, muestra el desenlace. La hoja sólo espera el
     * resultado para lo suyo (un rechazo con lo capturado).
     */
    fun enviarReembolsoASaldo(paymentId: String, body: String, idempotencyKey: String): Deferred<DesenlaceDeSaldo> =
        viewModelScope.async {
            val selloDelToque = secureStorage.generacion // Antes de escribir en disco: sale con la sesión de quien tocó, o no sale.
            val store = refundPendingStore ?: return@async DesenlaceDeSaldo.GuardadoFallido
            val venue = secureStorage.venueId ?: return@async DesenlaceDeSaldo.GuardadoFallido
            if (!marcarEnVuelo(paymentId)) return@async DesenlaceDeSaldo.OtroPendiente
            val desenlace = try {
                // «Quién lo pidió» (para el aviso): el nombre de la sesión de ahora, al tocar «Reembolsar».
                SaldoAFavorEnvio(refundRepository, store).enviar(venue, paymentId, body, idempotencyKey,
                    listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName).joinToString(" ").trim(), selloDelToque)
            } finally { soltarEnVuelo(paymentId) }
            detalleAbierto(paymentId)?.let { aplicarDesenlace(it, desenlace, reenvio = false) }
            desenlace
        }

    /** El detalle abierto si es ESA venta (M5): el desenlace de otra venta no se escribe en ésta. */
    private fun detalleAbierto(paymentId: String): DetailScope? = detailScope?.takeIf { it.paymentId == paymentId && current(it) }

    /** Lo que el detalle muestra con el desenlace, venga de la hoja o de la recuperación: el abono con su aviso de teléfono (I1). */
    private suspend fun aplicarDesenlace(scope: DetailScope, desenlace: DesenlaceDeSaldo, reenvio: Boolean) {
        versionDelPendiente.incrementAndGet()
        when (desenlace) {
            is DesenlaceDeSaldo.Hecho -> {
                _reembolsoPendiente.value = null
                val abono = desenlace.data.storeCredit?.let(::exchangeStoreCreditNotice).orEmpty()
                _avisoDeReembolso.value = ((if (reenvio) listOf(TextosCambio.REEMBOLSO_CONFIRMADO) else emptyList()) + abono)
                    .joinToString("\n").ifEmpty { null }
                repository.fetchTransactionDetail(scope.paymentId, scope.actorId, scope.venueId)
                    ?.takeIf { current(scope) && it.id == scope.paymentId }?.let {
                        _selectedTransaction.value = it
                        _detalleNoDisponible.value = null
                    }
                invalidateAndRefresh()
            }
            is DesenlaceDeSaldo.Rechazado -> {
                _reembolsoPendiente.value = null
                // Con la hoja abierta el rechazo lo dice ella, junto a lo capturado; si ya se cerró, lo dice el detalle.
                if (reenvio || !_issueRefundSheetVisible.value) _avisoDeReembolso.value = desenlace.mensaje
            }
            is DesenlaceDeSaldo.Pendiente -> {
                recuperarReembolsoPendiente(scope, reenviar = false)
                if (current(scope)) _avisoDeReembolso.value = desenlace.mensaje
            }
            DesenlaceDeSaldo.OtroPendiente -> recuperarReembolsoPendiente(scope, reenviar = false)
            DesenlaceDeSaldo.GuardadoFallido -> Unit
        }
    }

    /** «Confirmar estado»: el ÚNICO reenvío de lo pendiente (un toque humano, con la sesión de quien toca y la misma llave). */
    fun confirmarReembolsoPendiente() {
        val scope = detailScope ?: return
        viewModelScope.launch { recuperarReembolsoPendiente(scope, reenviar = true) }
    }

    /** Recovery opens a saved operation, even if its completed return made the original sale ineligible. */
    fun resumePendingExchange(transaction: Transaction) {
        val scope = detailScope ?: return
        if (current(scope) && _selectedTransaction.value?.id == transaction.id) _exchangeTransaction.value = transaction
    }

    suspend fun refreshForExchange(): Transaction? {
        val scope = detailScope ?: return null
        val detail = repository.fetchTransactionDetail(scope.paymentId, scope.actorId, scope.venueId)
        if (!current(scope) || detail?.id != scope.paymentId) return null
        _selectedTransaction.value = detail
        return detail
    }

    fun openExchangeOrder(orderId: String) {
        val scope = detailScope ?: return
        if (!current(scope)) return
        if (!connectivityMonitor.isOnline()) { _exchangeMessage.value = TextosCambio.SIN_CONEXION; return }
        viewModelScope.launch {
            val paymentId = try {
                repository.paymentIdForExchangeOrder(orderId, scope.actorId, scope.venueId)
            } catch (_: SecurityException) {
                if (current(scope)) _exchangeMessage.value = TextosCambio.VINCULO_SIN_PERMISO
                return@launch
            } catch (e: ExchangeOrderNotCharged) {
                if (current(scope)) _exchangeMessage.value =
                    if (e.cancelled) TextosCambio.VINCULO_CANCELADO else TextosCambio.VINCULO_ESPERA_COBRO
                return@launch
            }
            if (!current(scope)) return@launch
            if (paymentId != null) selectTransaction(paymentId)
            else _exchangeMessage.value = TextosCambio.VINCULO_NO_DISPONIBLE
        }
    }

    fun openCustomerPicker() {
        if (!canAssignCustomer || _isAssigningCustomer.value) return
        if (!customerAssignmentOnline) {
            _customerAssignmentMessage.value = CUSTOMER_ASSIGNMENT_OFFLINE
            return
        }
        _customerAssignmentMessage.value = null
        _customerPickerVisible.value = true
    }

    fun chooseCustomer(customer: Customer) {
        if (!canAssignCustomer || _isAssigningCustomer.value) return
        val scope = detailScope ?: return
        val old = try { readPending(scope) } catch (e: Exception) {
            _customerAssignmentMessage.value = "No se pudo recuperar la selección guardada. Inténtalo de nuevo."
            return
        }
        if (old != null && old.customerId != customer.id) {
            _customerAssignmentMessage.value = "Hay una asignación pendiente. Reinténtala antes de elegir otro cliente."
            return
        }
        _customerCandidate.value = customer
        _customerPickerVisible.value = false
        _customerAssignmentMessage.value = null
        _customerAssignmentSucceeded.value = false
    }

    fun cancelCustomerAssignment() {
        if (_isAssigningCustomer.value) return
        _customerPickerVisible.value = false
        _customerCandidate.value = null
        _customerAssignmentMessage.value = null
    }

    fun clearCustomerAssignmentSuccess() { _customerAssignmentSucceeded.value = false }

    private fun readPending(scope: DetailScope): PendingCustomerAssignment? {
        val actor = scope.actorId ?: return null
        val venue = scope.venueId ?: return null
        return secureStorage.readPendingCustomerAssignment(actor, venue, scope.paymentId)
            ?.takeIf { it.actorId == actor && it.venueId == venue && it.paymentId == scope.paymentId }
    }

    private fun verifiedCustomer(pending: PendingCustomerAssignment, detail: Transaction): Boolean {
        if (detail.customerId == null) return false
        _selectedTransaction.value = detail
        if (detail.customerId == pending.customerId) {
            if (secureStorage.clearPendingCustomerAssignment(pending)) {
                _customerCandidate.value = null
                _customerAssignmentMessage.value = null
                _customerAssignmentSucceeded.value = true
            } else {
                _customerAssignmentMessage.value = "Cliente asignado. Vuelve a abrir esta venta para confirmar la recuperación."
            }
        } else {
            secureStorage.clearPendingCustomerAssignment(pending)
            _customerCandidate.value = null
            _customerAssignmentMessage.value = "Esta venta ya tiene otro cliente asignado"
        }
        return true
    }

    fun confirmCustomerAssignment() {
        if (_isAssigningCustomer.value || !canAssignCustomer) return
        val scope = detailScope ?: return
        val customer = _customerCandidate.value ?: return
        if (!customerAssignmentOnline) {
            _customerAssignmentMessage.value = CUSTOMER_ASSIGNMENT_OFFLINE
            return
        }
        val pending = PendingCustomerAssignment(scope.actorId ?: return, scope.venueId ?: return,
            scope.paymentId, customer.id, customer.fullName)
        _isAssigningCustomer.value = true
        _customerAssignmentMessage.value = null
        _customerAssignmentSucceeded.value = false
        viewModelScope.launch {
            try {
                if (!secureStorage.writePendingCustomerAssignment(pending)) {
                    _customerAssignmentMessage.value = "No se pudo guardar la selección en el aparato. Inténtalo de nuevo."
                    return@launch
                }
                // También antes de un reintento: el POST anterior pudo haber llegado aunque no recibimos su respuesta.
                val before = repository.fetchTransactionDetail(scope.paymentId, scope.actorId, scope.venueId)
                if (!current(scope)) return@launch
                if (before == null || before.id != scope.paymentId) {
                    _customerAssignmentMessage.value = CUSTOMER_ASSIGNMENT_PENDING
                    return@launch
                }
                if (verifiedCustomer(pending, before)) return@launch
                _selectedTransaction.value = before
                if (!canAssignCustomer) {
                    _customerAssignmentMessage.value = "Esta venta ya no permite asignar un cliente"
                    return@launch
                }
                val result = repository.assignCustomer(pending)
                if (!current(scope)) return@launch
                val after = repository.fetchTransactionDetail(scope.paymentId, scope.actorId, scope.venueId)
                if (!current(scope)) return@launch
                if (after != null && after.id == scope.paymentId) {
                    if (verifiedCustomer(pending, after)) return@launch
                    _selectedTransaction.value = after
                }
                val error = result.exceptionOrNull()
                _customerAssignmentMessage.value = if (error != null && error !is java.io.IOException)
                    error.message ?: CUSTOMER_ASSIGNMENT_PENDING else CUSTOMER_ASSIGNMENT_PENDING
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current(scope)) _customerAssignmentMessage.value = CUSTOMER_ASSIGNMENT_PENDING
            } finally {
                _isAssigningCustomer.value = false
            }
        }
    }

    // Print receipt (Ventas → detalle → "Imprimir")
    private val _isPrintingReceipt = MutableStateFlow(false)
    val isPrintingReceipt: StateFlow<Boolean> = _isPrintingReceipt.asStateFlow()

    // Mensaje + tono: la pantalla pinta por el TONO, nunca comparando el texto.
    private val _printReceiptResult = MutableStateFlow<ResultadoDeReimpresion?>(null)
    val printReceiptResult: StateFlow<ResultadoDeReimpresion?> = _printReceiptResult.asStateFlow()

    // Refund
    private val _showRefundSheet = MutableStateFlow(false)
    val showRefundSheet: StateFlow<Boolean> = _showRefundSheet.asStateFlow()

    private val _refundState = MutableStateFlow<RefundUiState>(RefundUiState.Idle)
    val refundState: StateFlow<RefundUiState> = _refundState.asStateFlow()

    private val gate = refreshGateFactory.create(viewModelScope)

    /** D18 r4: lo que la regla de recarga necesita de cada carga (con qué cola se pidió, cuántas fallaron), junto a los datos. */
    private val cargas = CargasDeLaPantalla(reglaDeRecarga)

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    private val _detailRefundActive = MutableStateFlow(false)

    /** Espejo del refundFlowActive de iOS: la vista del detalle reporta aquí su sheet local. */
    fun setRefundFlowActive(active: Boolean) {
        _detailRefundActive.value = active
    }

    // MARK: - "Emitir reembolso" del detalle: el PIN va ANTES del formulario

    private val _issueRefundSheetVisible = MutableStateFlow(false)

    /** ¿Está abierto el formulario de reembolso del detalle? */
    val issueRefundSheetVisible: StateFlow<Boolean> = _issueRefundSheetVisible.asStateFlow()

    /**
     * Tocaron "Emitir reembolso".
     *
     * 🔴 Cuando la app YA SABE que la acción está bloqueada —el candado— el PIN
     * se pide AQUÍ, antes de abrir nada. Antes salía cuando el server rechazaba,
     * o sea después de llenar importe, motivo y propina: si el encargado no
     * estaba cerca, todo ese trabajo se perdía.
     *
     * Sin candado NO se toca nada: la petición sale como siempre y, si el server
     * dice que no, `ForbiddenInterceptor` abre el teclado. Esa red de seguridad
     * cubre todo lo que el cliente NO puede anticipar (permiso movido en el
     * server, cache vieja, Permission Set), y adelantar el PIN no la reemplaza.
     */
    fun onIssueRefundTapped() {
        if (roleManager.canIssueRefund) {
            _issueRefundSheetVisible.value = true
            return
        }
        viewModelScope.launch {
            if (managerOverrideCoordinator.preauthorize(REFUND_PERMISSION)) {
                _issueRefundSheetVisible.value = true
            }
            // Canceló: no se abre nada. El teclado ya le dijo por qué.
        }
    }

    fun dismissIssueRefundSheet() {
        _issueRefundSheetVisible.value = false
        // I2: si se cerró con un envío a saldo en vuelo, lo pendiente ya está en disco: el detalle lo muestra y apaga «Emitir».
        val scope = detailScope ?: return
        viewModelScope.launch { recuperarReembolsoPendiente(scope, reenviar = false) }
    }

    /** Guard §4.5 — dinero: con la devolución abierta (lista o detalle) o corriendo, ni el gesto refresca. */
    private fun workInProgress(): Boolean =
        _showRefundSheet.value || _refundState.value is RefundUiState.Loading || _detailRefundActive.value ||
            _exchangeTransaction.value != null || exchangeAuthorizing ||
            _customerPickerVisible.value || _customerCandidate.value != null || _isAssigningCustomer.value

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> {
        val busqueda = _searchText.value.ifBlank { null }
        val consulta = repository.consultaPara(busqueda)
        _consultaVigente.value = consulta
        ultimaPedida = consulta
        return cargas.cargar { repository.fetchTransactions(page = 1, search = busqueda) }
    }

    fun autoRefresh() {
        viewModelScope.launch {
            gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            _isManualRefreshing.value = true
            try {
                gate.run(workInProgress = ::workInProgress, manual = true, block = ::refreshNow)
            } finally {
                _isManualRefreshing.value = false
            }
        }
    }

    /** D18 r4: la lista, vista desde la regla de recarga ([ReglaDeRecarga]). */
    private val pantallaParaLaRegla = object : PantallaQueSeRecarga {
        override val estadoParaRecargar: Flow<EstadoParaRecargar> = combine(
            combine(
                repository.transactions, repository.consultaDeLaLista, _consultaVigente, repository.isLoading, repository.falloDeCarga,
            ) { lista, deLaLista, vigente, cargando, fallo ->
                estadoDeLaListaDeVentas(deLaLista == vigente, lista.size, lista.size, cargando, fallo) to cargando
            },
            cargas.registro,
        ) { (estado, cargando), registro -> estado.paraLaRegla(cargando, registro) }

        override suspend fun recargar(): RefreshOutcome {
            gate.invalidate()
            gate.resetCooldown()
            return gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
        }

        override fun marcarConPendientes() = cargas.marcarConPendientes()
    }

    /** D18 r4: sólo mientras la lista se ve (RESUMED), la regla de recarga por nivel. */
    internal suspend fun vigilarLaRedMientrasSeVe(owner: LifecycleOwner) {
        val regla = reglaDeRecarga ?: return
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { regla.mientrasSeVe(pantallaParaLaRegla) }
    }

    /** Mutación local (devolución, etc.) = identidad nueva: invalida el TTL y re-pide (spec §4.4).
     *  Sin guard de busy: es post-mutación — recargar la lista no pisa ningún borrador. */
    fun invalidateAndRefresh() {
        gate.invalidate()
        viewModelScope.launch {
            gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
        }
    }

    init {
        observeSearch()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchText
                .drop(1) // la emisión inicial cruda no es una búsqueda del usuario
                .debounce(400)
                .distinctUntilChanged()
                .collect {
                    // D18 (Codex r1 #3): la búsqueda nueva es la consulta YA. Si no se puede pedir (sin red), no se queda
                    // a la vista lo de la búsqueda anterior bajo el texto nuevo.
                    _consultaVigente.value = repository.consultaPara(it.ifBlank { null })
                    gate.invalidate()
                    do {
                        val salida = gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
                        // Si iba en camino otra búsqueda, el gate se unió a ella: al terminar, se pide la vigente.
                    } while (salida == RefreshOutcome.Joined && ultimaPedida != _consultaVigente.value)
                }
        }
    }

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun loadMoreIfNeeded(currentItem: Transaction) {
        val list = listaVigente()
        if (list.isEmpty()) return
        if (currentItem.id != list.last().id) return
        if (!repository.canLoadMore()) return

        viewModelScope.launch {
            repository.fetchTransactions(
                page = repository.getNextPage(),
                search = _searchText.value.ifBlank { null },
            )
        }
    }

    // MARK: - Filtered + grouped transactions

    /** Apply client-side filters to the loaded list */
    val filteredTransactions: List<Transaction>
        get() {
            val list = listaVigente()
            return if (_filters.value.hasActiveFilters) {
                _filters.value.apply(list)
            } else {
                list
            }
        }

    /** Grouped by date for display */
    val filteredGroupedTransactions: List<Pair<String, List<Transaction>>>
        get() = filteredTransactions
            .groupBy { it.dateGroup }
            .toList()

    // MARK: - Filters

    fun setActiveFilter(filter: TransactionActiveFilter?) {
        _activeFilter.value = filter
    }

    fun updateFilters(newFilters: TransactionFilters) {
        _filters.value = newFilters
    }

    fun clearAllFilters() {
        _filters.value = TransactionFilters()
    }

    // MARK: - Dynamic filter options (built from loaded data)

    fun methodOptions(): List<Pair<String, String>> {
        val methods = listaVigente().mapNotNull { it.method }.toSet()
        return methods.sorted().map { m ->
            when (m) {
                "CASH" -> m to "Efectivo"
                "CARD" -> m to "Tarjeta"
                "CREDIT_CARD" -> m to "Tarjeta de crédito"
                "DEBIT_CARD" -> m to "Tarjeta de débito"
                "TRANSFER" -> m to "Transferencia"
                else -> m to m.replace("_", " ")
                    .replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun staffOptions(): List<Pair<String, String>> {
        val names = listaVigente()
            .mapNotNull { it.staffName }
            .filter { it.isNotEmpty() }
            .toSet()
        return names.sorted().map { it to it }
    }

    // MARK: - Transaction Selection

    fun selectTransaction(transactionId: String) {
        if (_exchangeTransaction.value != null) dismissExchange()
        _exchangeMessage.value = null
        val scope = DetailScope(secureStorage.userId, secureStorage.venueId, transactionId, ++detailGeneration)
        detailScope = scope
        _selectedTransactionId.value = transactionId
        _isLoadingDetail.value = true
        _selectedTransaction.value = null
        _printReceiptResult.value = null
        _customerCandidate.value = null
        _customerPickerVisible.value = false
        _customerAssignmentMessage.value = null
        _customerAssignmentSucceeded.value = false
        _reembolsoPendiente.value = null
        _avisoDeReembolso.value = null
        _detalleNoDisponible.value = null
        viewModelScope.launch {
            val lectura = repository.leerDetalle(transactionId, scope.actorId, scope.venueId)
            val detail = lectura.transaccion
            if (!current(scope)) return@launch
            val abierta = detail?.takeIf { it.id == scope.paymentId }
            _selectedTransaction.value = abierta
            // Una venta que no abre lo dice, y lo pendiente se lee igual (está en el aparato, no en la red) (D3).
            if (abierta == null) {
                _detalleNoDisponible.value = if (lectura.sinRespuesta) TextosCambio.VENTA_SIN_RED else TextosCambio.VENTA_NO_ABRIO
            }
            _isLoadingDetail.value = false
            // Opción A (founder, 2-nov): al abrir la venta sólo se LEE lo pendiente; nada se reenvía solo, para nadie.
            launch { recuperarReembolsoPendiente(scope, reenviar = false) }
            if (detail != null && detail.id == scope.paymentId && roleManager.canAttachCustomer && roleManager.hasVenuePermission("customers:read")) {
                try {
                    readPending(scope)?.let { pending ->
                        if (!verifiedCustomer(pending, detail) && canAssignCustomer) {
                            _customerCandidate.value = Customer(pending.customerId, firstName = pending.customerName)
                            _customerAssignmentMessage.value = CUSTOMER_ASSIGNMENT_PENDING
                        }
                    }
                } catch (e: Exception) {
                    _customerAssignmentMessage.value = "No se pudo recuperar la selección guardada. Inténtalo de nuevo."
                }
            }
        }
    }

    fun clearSelection() {
        if (_exchangeTransaction.value != null) dismissExchange()
        _exchangeMessage.value = null
        detailScope = null
        _selectedTransactionId.value = null
        _selectedTransaction.value = null
        _printReceiptResult.value = null
        _customerCandidate.value = null
        _customerPickerVisible.value = false
        _customerAssignmentMessage.value = null
        _customerAssignmentSucceeded.value = false
        _reembolsoPendiente.value = null
        _avisoDeReembolso.value = null
        _detalleNoDisponible.value = null
    }

    // MARK: - Print Receipt

    /** Reprints a past sale's receipt on the configured RECEIPT printer(s) —
     *  same manualPrintReceipt path the payment-success screen uses. */
    fun printTransactionReceipt(transaction: Transaction) {
        viewModelScope.launch {
            _isPrintingReceipt.value = true
            _printReceiptResult.value = null
            try {
                // 🔴 La liga del recibo NO viaja en el historial: se pide aquí para poder dibujar
                // el QR de facturación en la reimpresión. Tiene tope de 5 s y nunca lanza — si no
                // se puede traer, el ticket sale igual, sólo que sin QR, y el aviso lo dice.
                val liga = repository.fetchReceiptLink(transaction.id)
                val ligaObtenida = (liga as? ResultadoLigaRecibo.Obtenida)?.liga

                val outcome = printerService.manualPrintReceipt(
                    transaction.toReceiptData(
                        venueName = venueName,
                        receiptUrl = ligaObtenida?.receiptUrl,
                        autofacturaAvailable = ligaObtenida?.autofacturaAvailable ?: false,
                    ),
                )

                val mensaje = when (outcome) {
                    is PrinterService.PrintOutcome.Printed -> "Recibo impreso"
                    is PrinterService.PrintOutcome.OutOfPaper -> "La impresora no tiene papel"
                    is PrinterService.PrintOutcome.Failed -> "No se pudo imprimir: ${outcome.reason}"
                    is PrinterService.PrintOutcome.NoPrinter -> "No hay impresora de recibos en esta caja. Agrégala en Más › Impresoras y cajón."
                }

                val imprimio = outcome is PrinterService.PrintOutcome.Printed
                _printReceiptResult.value = ResultadoDeReimpresion(
                    mensaje = avisoDeReimpresion(mensajeDeImpresion = mensaje, imprimio = imprimio, liga = liga),
                    tono = tonoDeReimpresion(imprimio = imprimio, liga = liga),
                )
            } catch (e: Exception) {
                _printReceiptResult.value = ResultadoDeReimpresion(
                    mensaje = "Error al imprimir: ${e.message ?: "desconocido"}",
                    tono = TonoDelAviso.ERROR,
                )
            } finally {
                _isPrintingReceipt.value = false
            }
        }
    }

    fun clearPrintReceiptResult() {
        _printReceiptResult.value = null
    }

    private val venueName: String
        get() = secureStorage.venueName ?: "Avoqado"

    // MARK: - Unassociated Refund

    fun showRefundSheet() {
        _showRefundSheet.value = true
    }

    fun hideRefundSheet() {
        _showRefundSheet.value = false
        _refundState.value = RefundUiState.Idle
    }

    fun resetRefundState() {
        _refundState.value = RefundUiState.Idle
    }

    fun processUnassociatedRefund(
        amountText: String,
        reason: String,
        items: List<RefundItem> = emptyList(),
    ) {
        val amountDouble = amountText.toDoubleOrNull()
        if (amountDouble == null || amountDouble <= 0) {
            _refundState.value = RefundUiState.Error("Ingresa un monto valido")
            return
        }
        if (reason.isBlank()) {
            _refundState.value = RefundUiState.Error("Ingresa una razón para el reembolso")
            return
        }

        val amountCents = amountDouble.aCentavos()

        _refundState.value = RefundUiState.Loading
        viewModelScope.launch {
            val result = refundRepository.createUnassociatedRefund(amountCents, reason, items)
            result.fold(
                onSuccess = { refundResult ->
                    Log.d("💸", "✅ Refund processed: ${refundResult.message}")

                    // 🔴 AQUÍ NO SE ESCRIBE EN EL CAJÓN. El servidor ya lo hace.
                    //
                    // Esta ruta (`POST /mobile/venues/:id/refunds` →
                    // `refund.mobile.service.createRefund`) SIEMPRE creó su PAY_OUT, y este
                    // cliente manda `method = "CASH"` fijo (`RefundRepository:214`), así que
                    // el movimiento del servidor está garantizado. O sea que esto llevaba
                    // tiempo restando DOS VECES, sólo que nadie lo había medido: el defecto
                    // que se midió el 2026-08-16 fue el de la OTRA ruta, la del sheet.
                    //
                    // Además el mensaje mentía: "✅ Cash drawer PAY_OUT recorded" se escribía
                    // aunque no hubiera caja abierta (`addPayOut` devuelve null) y aunque el
                    // POST al servidor fallara (`fireApiPayOut` se traga el error).
                    //
                    // El movimiento aparece en la tablet en cuanto se entra a Caja: CADA entrada
                    // (`CashDrawerViewModel.alEntrar` → `syncFromApi()`), no sólo la primera — antes
                    // era sólo el `init`, el VM vivía días y en Testarudo (28-sep-2026) el reembolso
                    // nunca llegó al corte. Vigilado por `RefundCashDrawerOwnershipTest` y
                    // `CashDrawerCorteConfirmadoPorElServidorTest`.

                    _refundState.value = RefundUiState.Success(
                        refundResult.message ?: "Reembolso procesado",
                    )

                    // Refresh transactions list — mutación local = identidad nueva.
                    invalidateAndRefresh()
                },
                onFailure = { error ->
                    Log.e("💸", "❌ Refund failed: ${error.message}")
                    _refundState.value = RefundUiState.Error(
                        error.message ?: "Error al procesar el reembolso",
                    )
                },
            )
        }
    }

    // MARK: - Receipt Sending

    private val _isSendingReceipt = MutableStateFlow(false)
    val isSendingReceipt: StateFlow<Boolean> = _isSendingReceipt.asStateFlow()

    private val _receiptResultMessage = MutableStateFlow<String?>(null)
    val receiptResultMessage: StateFlow<String?> = _receiptResultMessage.asStateFlow()

    fun clearReceiptResult() {
        _receiptResultMessage.value = null
    }

    fun sendReceiptEmail(paymentId: String, email: String) {
        _isSendingReceipt.value = true
        _receiptResultMessage.value = null
        viewModelScope.launch {
            val result = orderRepository.sendReceiptEmail(paymentId, email)
            result.fold(
                onSuccess = {
                    Log.d("📧", "✅ Email receipt sent for transaction $paymentId")
                    _receiptResultMessage.value = "Recibo enviado por correo"
                },
                onFailure = { error ->
                    Log.e("📧", "❌ Email receipt failed: ${error.message}")
                    _receiptResultMessage.value = error.message ?: "Error al enviar recibo"
                },
            )
            _isSendingReceipt.value = false
        }
    }

    fun sendReceiptWhatsApp(paymentId: String, phone: String) {
        _isSendingReceipt.value = true
        _receiptResultMessage.value = null
        viewModelScope.launch {
            val result = orderRepository.sendReceiptWhatsApp(paymentId, phone)
            result.fold(
                onSuccess = {
                    Log.d("📨", "✅ WhatsApp receipt sent for transaction $paymentId")
                    _receiptResultMessage.value = "Recibo enviado por WhatsApp"
                },
                onFailure = { error ->
                    Log.e("📨", "❌ WhatsApp receipt failed: ${error.message}")
                    _receiptResultMessage.value = error.message ?: "Error al enviar recibo"
                },
            )
            _isSendingReceipt.value = false
        }
    }

    private companion object {
        /**
         * 🔴 Espejado por nombre EXACTO desde
         * `avoqado-server/src/lib/permissions.ts`. Con el nombre mal escrito, el
         * server nunca reconoce el permiso y el PIN de un gerente legítimo se
         * rechaza sin que nadie entienda por qué.
         */
        const val REFUND_PERMISSION = "payments:refund"
    }
}

// MARK: - Transaction → ReceiptData

/** Maps a past sale to the printable receipt shape used by PrinterService.
 *  Amounts arrive in pesos (Double) and ReceiptData wants cents. */
internal fun Transaction.toReceiptData(
    venueName: String,
    receiptUrl: String? = null,
    autofacturaAvailable: Boolean = false,
    reprintedAt: java.util.Date = java.util.Date(),
): ReceiptData {
    fun cents(value: Double): Int = kotlin.math.round(value * 100).toInt()

    // Tarjeta: «Pago: Tarjeta» + «Tarjeta: VISA **** 4242». Antes la marca y el PAN iban pegados en
    // la etiqueta del pago y el ticket los repetía.
    val isCard = method == "CARD" || method == "CREDIT_CARD" || method == "DEBIT_CARD"
    val lastFour = maskedPan?.takeIf { it.length >= 4 }?.takeLast(4)
    // Devolución a saldo a favor (spec §7, Codex plan r1 h9): «Devuelto con: Saldo a favor (destinataria)», con el nombre que manda el
    // SERVIDOR (a quién se abonó), no el de la clienta de la venta.
    val saldoPara = storeCredit?.customerName?.takeIf { it.isNotBlank() }

    // D3 (IVA por producto, plan 7a; r1 #2 y #9; r2 R2-6 y R2-9; r3 R3-11; ruling A7-R6): con el comprobante del servidor, TODAS
    // las cifras salen de él —el recibo emitido no cambia aunque el pago se haya corregido después—. Sólo con un servidor anterior
    // se cae a importe y propina del pago, en magnitud, con la devolución deducida de los signos.
    val comprobante = ticketTotals
    val esDevolucion = comprobante?.let { it.kind == "DEVOLUCION" } ?: (amount < 0 || (amount == 0.0 && tipAmount < 0))
    // D1 «fotocopia del ticket»: la copia del papel de ESTA parte manda (ya cuadró al guardarse, no depende de `parcial`). Se toman
    // JUNTOS sus renglones, su subtotal y su «Descuento»; sin cargo ni IVA aparte, porque la copia ya es lo cobrado sin propina. La
    // propina y el total siguen siendo los del comprobante. Sin copia, todo como hoy.
    val copia = lineasDelTicket?.takeIf { !esDevolucion }
    val subtotal = copia?.subtotalCents ?: comprobante?.let { cents(it.subtotal) } ?: cents(kotlin.math.abs(amount))
    // Subtotal BRUTO + su descuento cuando el servidor desglosa (A7-R6): el mismo papel que el del cobro.
    val descuento = (copia?.descuentoCents ?: comprobante?.discount?.let { cents(it) })?.takeIf { it > 0 } ?: 0
    val cargo = if (esDevolucion || copia != null) 0 else comprobante?.let { cents(it.serviceCharge) }?.takeIf { it > 0 } ?: 0
    val impuestoAgregado = if (esDevolucion || copia != null) 0 else comprobante?.let { cents(it.addedTax) }?.takeIf { it > 0 } ?: 0
    val propina = comprobante?.let { cents(it.tip) } ?: cents(kotlin.math.abs(tipAmount))
    val total = comprobante?.let { cents(it.total) } ?: (cents(kotlin.math.abs(amount)) + propina)

    val receiptItems = if (copia != null) {
        // Los renglones del papel de la parte, con el peso, la nota y el área donde el papel los pinta. La pieza de un combo va en $0
        // y el renglón que la precede es el del combo (en negritas, como en el papel: `ComboPrintLines`).
        copia.lineas.mapIndexed { i, l ->
            ReceiptItem(
                name = l.nombre,
                quantity = kotlin.math.round(l.cantidad).toInt().coerceAtLeast(1),
                unitPrice = l.unitarioCents,
                totalPrice = l.totalCents,
                modifiers = l.modificadores?.filter { it.isNotEmpty() }?.ifEmpty { null },
                note = l.nota,
                isCortesia = l.cortesia == true,
                weightSummary = l.peso,
                areaSourceLabel = l.area,
                isComboHeader = l.pieza != true && copia.lineas.getOrNull(i + 1)?.pieza == true,
                isComboComponent = l.pieza == true,
            )
        }
    } else if (comprobante?.parcial == true && items.isNotEmpty()) {
        // D15: UNA parte de una venta dividida (lo marca el servidor): un renglón «Pago parcial» con lo que cobró, como el papel
        // del cobro (`PaymentFlowViewModel.buildReceiptSnapshot`). Enumerar toda la venta daba renglones que no suman el total.
        listOf(ReceiptItem(name = "Pago parcial", quantity = 1, unitPrice = subtotal, totalPrice = subtotal))
    } else if (items.isNotEmpty()) {
        items.map { item ->
            ReceiptItem(
                name = item.productName.ifEmpty { "Artículo" },
                quantity = item.quantity,
                unitPrice = cents(item.unitPrice),
                totalPrice = cents(item.amount),
                modifiers = item.modifiers.mapNotNull { it.name }.filter { it.isNotEmpty() }.ifEmpty { null },
            )
        }
    } else {
        // Sales without line items (quick amounts / older records): one summary line.
        listOf(ReceiptItem(name = "Venta", quantity = 1, unitPrice = subtotal, totalPrice = subtotal))
    }

    return ReceiptData(
        orderNumber = orderNumber ?: id.takeLast(4),
        // D4: el mismo «Tipo» que el ticket del cobro, si el servidor lo manda (hoy no: «En tienda»).
        orderType = com.avoqado.pos.tables.presentation.etiquetaDelTipoDeOrden(tableNumber, orderType),
        items = receiptItems,
        subtotal = subtotal,
        taxAmount = impuestoAgregado,
        tipAmount = propina.takeIf { it > 0 },
        discountAmount = descuento.takeIf { it > 0 },
        total = total,
        serviceChargeAmount = cargo.takeIf { it > 0 },
        isRefund = esDevolucion,
        paymentMethod = when {
            saldoPara != null -> TextosCambio.REIMPRESION_SALDO.replace("{nombre}", saldoPara)
            isCard -> PaymentMethodDisplay.label(method)
            else -> methodDescription
        },
        paymentRowLabel = TextosCambio.TICKET_DEVUELTO_CON.takeIf { saldoPara != null },
        cardLastFour = if (isCard && saldoPara == null) lastFour else null,
        cardBrand = if (isCard && saldoPara == null) cardBrand else null,
        venueName = venueName,
        cashierName = staffName,
        customerName = customerName,
        // Print the SALE's timestamp, not "now" — this is a reprint of a past sale.
        date = parsedDateTime?.toInstant()?.let { java.util.Date.from(it) } ?: java.util.Date(),
        transactionId = referenceNumber ?: id,
        // 🔴 El QR de facturación de la REIMPRESIÓN. Sin esto el ticket reimpreso sale sin QR,
        // que es el defecto que reportó el cliente: el mismo ticket, impreso dos veces, distinto.
        receiptUrl = receiptUrl,
        autofacturaAvailable = autofacturaAvailable,
        reprintedAt = reprintedAt,
    )
}

private const val CUSTOMER_ASSIGNMENT_OFFLINE = "Necesitas conexión para asignar un cliente a esta venta"
private const val CUSTOMER_ASSIGNMENT_PENDING = "No se pudo confirmar la asignación. Revisa la conexión y reintenta."
