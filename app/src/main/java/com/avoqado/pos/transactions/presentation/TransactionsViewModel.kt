package com.avoqado.pos.transactions.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
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
import com.avoqado.pos.transactions.data.RefundItem
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.TransactionRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.avoqado.pos.core.util.aCentavos

// MARK: - Refund UI State

sealed interface RefundUiState {
    data object Idle : RefundUiState
    data object Loading : RefundUiState
    data class Success(val message: String) : RefundUiState
    data class Error(val message: String) : RefundUiState
}

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
) : ViewModel() {

    val transactions = repository.transactions
    val isLoading = repository.isLoading
    val isLoadingMore = repository.isLoadingMore

    // Search
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

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
    }

    /** Guard §4.5 — dinero: con la devolución abierta (lista o detalle) o corriendo, ni el gesto refresca. */
    private fun workInProgress(): Boolean =
        _showRefundSheet.value || _refundState.value is RefundUiState.Loading || _detailRefundActive.value ||
            _customerPickerVisible.value || _customerCandidate.value != null || _isAssigningCustomer.value

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> =
        repository.fetchTransactions(page = 1, search = _searchText.value.ifBlank { null })

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
                    gate.invalidate()
                    gate.run(workInProgress = ::workInProgress, manual = false, block = ::refreshNow)
                }
        }
    }

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun loadMoreIfNeeded(currentItem: Transaction) {
        val list = transactions.value
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
            val list = transactions.value
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
        val methods = transactions.value.mapNotNull { it.method }.toSet()
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
        val names = transactions.value
            .mapNotNull { it.staffName }
            .filter { it.isNotEmpty() }
            .toSet()
        return names.sorted().map { it to it }
    }

    // MARK: - Transaction Selection

    fun selectTransaction(transactionId: String) {
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
        viewModelScope.launch {
            val detail = repository.fetchTransactionDetail(transactionId, scope.actorId, scope.venueId)
            if (!current(scope)) return@launch
            _selectedTransaction.value = detail?.takeIf { it.id == scope.paymentId }
            _isLoadingDetail.value = false
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
        detailScope = null
        _selectedTransactionId.value = null
        _selectedTransaction.value = null
        _printReceiptResult.value = null
        _customerCandidate.value = null
        _customerPickerVisible.value = false
        _customerAssignmentMessage.value = null
        _customerAssignmentSucceeded.value = false
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

    // D3 (IVA por producto, plan 7a; r1 #2 y #9; r2 R2-6 y R2-9; r3 R3-11; ruling A7-R6): con el comprobante del servidor, TODAS
    // las cifras salen de él —el recibo emitido no cambia aunque el pago se haya corregido después—. Sólo con un servidor anterior
    // se cae a importe y propina del pago, en magnitud, con la devolución deducida de los signos.
    val comprobante = ticketTotals
    val esDevolucion = comprobante?.let { it.kind == "DEVOLUCION" } ?: (amount < 0 || (amount == 0.0 && tipAmount < 0))
    val subtotal = comprobante?.let { cents(it.subtotal) } ?: cents(kotlin.math.abs(amount))
    // Subtotal BRUTO + su descuento cuando el servidor desglosa (A7-R6): el mismo papel que el del cobro.
    val descuento = comprobante?.discount?.let { cents(it) }?.takeIf { it > 0 } ?: 0
    val cargo = if (esDevolucion) 0 else comprobante?.let { cents(it.serviceCharge) }?.takeIf { it > 0 } ?: 0
    val impuestoAgregado = if (esDevolucion) 0 else comprobante?.let { cents(it.addedTax) }?.takeIf { it > 0 } ?: 0
    val propina = comprobante?.let { cents(it.tip) } ?: cents(kotlin.math.abs(tipAmount))
    val total = comprobante?.let { cents(it.total) } ?: (cents(kotlin.math.abs(amount)) + propina)

    val receiptItems = if (items.isNotEmpty()) {
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
        paymentMethod = if (isCard) PaymentMethodDisplay.label(method) else methodDescription,
        cardLastFour = if (isCard) lastFour else null,
        cardBrand = if (isCard) cardBrand else null,
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
