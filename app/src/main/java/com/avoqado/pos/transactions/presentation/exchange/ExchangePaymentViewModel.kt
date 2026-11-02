package com.avoqado.pos.transactions.presentation.exchange

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.customers.data.RechazoAlEditarCliente
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.UpdateCustomerRequest
import com.avoqado.pos.customers.domain.ContactoEditado
import com.avoqado.pos.customers.domain.cambiosDeContacto
import com.avoqado.pos.customers.domain.erroresDeContacto
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Date
import javax.inject.Inject

/**
 * El pedido para guardarle el teléfono al cliente del saldo a favor con la edición de cliente de siempre (`PUT customers/:id`);
 * `null` si el teléfono no sirve. Lo usan el Cambio y el Reembolso.
 */
internal fun pedidoDeTelefonoDelSaldo(c: ExchangeStoreCreditCustomer, ficha: Customer?, phone: String): Pair<Customer, UpdateCustomerRequest>? {
    val original = ficha?.takeIf { it.id == c.id } ?: Customer(c.id, firstName = c.name.takeIf { it.isNotBlank() })
    val edited = ContactoEditado(original.firstName.orEmpty(), original.lastName.orEmpty(), phone, original.email.orEmpty())
    return cambiosDeContacto(original, edited)?.takeIf { it.phone != null && erroresDeContacto(original, edited).isEmpty() }
        ?.let { original to it }
}

/**
 * O-3 (A5): la venta trae el nombre y el id de su clienta, no su teléfono. Se toma de lo que contesta el SERVIDOR al buscarla (la
 * misma ficha que sale al escogerla de la lista), para que la destinataria por default se vea igual que una escogida. `null` = sin
 * clienta, sin coincidencia o sin respuesta: se queda como venía y el servidor revisa el teléfono al mandar. Lo usan el Cambio y el
 * Reembolso.
 */
internal suspend fun fichaDeLaClientaDeLaVenta(customers: CustomersRepository, sale: Transaction): Customer? {
    val id = sale.customerId ?: return null
    val nombre = sale.customerName?.takeIf { it.isNotBlank() } ?: return null
    return try {
        customers.buscarClientes(nombre).getOrNull()?.clientes?.firstOrNull { it.id == id }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Por qué no se guardó el teléfono: los textos de la edición de cliente de siempre (espejo de `EdicionDeCliente.mensajeDeError` en iOS). */
internal fun motivoAlGuardarTelefono(e: Throwable): String = when {
    e is RechazoAlEditarCliente -> e.message
    e is java.io.IOException || ServerErrorText.isOffline(e) -> TextosCambio.SALDO_SIN_RED
    else -> null
} ?: "No se pudo guardar el cliente. Intenta de nuevo."

/** Screen lifecycle only; submission and terminal service retain ownership of financial outcomes. */
@HiltViewModel
class ExchangePaymentViewModel @Inject constructor(repository: ExchangeRepository, private val store: ExchangePendingStore,
    private val secure: SecureStorage, private val outbox: SyncOutbox, private val drawer: CashDrawerRepository,
    private val terminal: TerminalPaymentService, private val role: RoleManager, private val printer: PrinterService,
    private val monitor: ConnectivityMonitor, private val settings: TpvSettingsRepository,
    private val customers: CustomersRepository) : ViewModel() {
    private val submission = ExchangeSubmission(repository, store)
    private val card = ExchangeCardCharge(terminal, submission)
    val state = submission.state
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _draft = MutableStateFlow<ExchangeDraft?>(null)
    val draft = _draft.asStateFlow()
    private val _terminals = MutableStateFlow<List<OnlineTerminal>>(emptyList())
    val terminals = _terminals.asStateFlow()
    /** Saldo a favor: a quién se abona. Por default, el cliente de la venta; «Cambiar» lo reemplaza (spec §4). */
    private val _storeCreditCustomer = MutableStateFlow<ExchangeStoreCreditCustomer?>(null)
    val storeCreditCustomer = _storeCreditCustomer.asStateFlow()
    val canCreateCustomer: Boolean get() = role.hasVenuePermission("customers:create")
    /** La ficha completa del cliente escogido con «Cambiar» (para guardarle el teléfono sin perder sus otros datos). */
    private var storeCreditRecord: Customer? = null
    private var venue: String? = null
    private var actor: String? = null
    private var transaction: Transaction? = null
    private var actorName = ""
    private var openJob: Job? = null
    private var generation = 0L

    fun open(sale: Transaction, force: Boolean = false) {
        if (!force && transaction?.id == sale.id && venue == secure.venueId && actor == secure.userId) return
        openJob?.cancel()
        val turn = ++generation
        val capturedVenue = secure.venueId
        transaction = sale; venue = secure.venueId; actor = secure.userId
        actorName = listOfNotNull(secure.userFirstName, secure.userLastName).joinToString(" ")
        _ready.value = false
        _message.value = null; _draft.value = null; _terminals.value = emptyList()
        _storeCreditCustomer.value = sale.customerId?.let { ExchangeStoreCreditCustomer(it, sale.customerName.orEmpty()) }
        storeCreditRecord = null
        // O-3 (A5): su teléfono, de lo que manda el servidor; si mientras tanto se escogió a otra o se abrió otra venta, se tira.
        if (sale.customerId != null && monitor.isOnline()) viewModelScope.launch {
            val ficha = fichaDeLaClientaDeLaVenta(customers, sale) ?: return@launch
            if (turn != generation || storeCreditRecord != null || _busy.value || _storeCreditCustomer.value?.id != ficha.id) return@launch
            storeCreditRecord = ficha
            _storeCreditCustomer.value = ExchangeStoreCreditCustomer(ficha.id, ficha.fullName, ficha.phone?.takeIf { it.isNotBlank() },
                phoneChecked = true)
        }
        openJob = viewModelScope.launch {
            try { submission.open(requireNotNull(capturedVenue), sale.id) { turn == generation } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (turn == generation) _message.value = TextosCambio.RECUPERACION_FALLIDA }
            finally { if (turn == generation) _ready.value = true }
        }
    }

    private fun current(): Boolean = venue != null && venue == secure.venueId && actor != null && actor == secure.userId
    private fun allowed(): Boolean {
        if (!current()) { _message.value = TextosCambio.SESION_CAMBIO; return false }
        if (!monitor.isOnline()) { _message.value = TextosCambio.SIN_CONEXION; return false }
        val p = submission.pending
        val pendingActor = p?.let { jsonReembolsos.parseToJsonElement(it.body).jsonObject["staffId"]?.jsonPrimitive?.content }
        if (p != null && pendingActor != actor) {
            _message.value = TextosCambio.ACTOR_MISMATCH.replace("{nombre}", p.actorName)
            return false
        }
        return true
    }

    /** [online] llega del observador de red de la pantalla, para que la opción reaccione al volver la red. */
    fun options(value: ExchangeDraft, online: Boolean = monitor.isOnline()): List<ExchangeSettlementOption> =
        if (value.settlement.kind == "COLLECT") ExchangeSettlement.collectOptions()
        else ExchangeSettlement.payoutOptions(transaction?.payoutOptions,
            -value.settlement.expectedDifferenceCents, role.canRefundToCash, settings.managerPinOverrideEnabled.value,
            settings.storeCreditOnly.value, value.returned.any { it.reason == ExchangeReason.DEFECT.name },
            settings.storeCreditAvailable.value, online, _storeCreditCustomer.value,
            transaction?.let(ExchangeSettlement::storeCreditApplies) ?: false)

    fun chooseCustomer(customer: Customer) {
        if (_busy.value) return
        storeCreditRecord = customer
        _storeCreditCustomer.value = ExchangeStoreCreditCustomer(customer.id, customer.fullName,
            customer.phone?.takeIf { it.isNotBlank() }, phoneChecked = true)
    }

    /**
     * Teléfono obligatorio (spec §4): el de un cliente que no lo tiene se pide aquí y se guarda en su ficha con la edición de
     * siempre (`PUT customers/:id`, con su permiso o el PIN de gerente). Sin red no se guarda ni se encola. Si falla, el diálogo
     * se queda con lo capturado y el motivo.
     */
    fun saveStoreCreditPhone(phone: String) {
        val c = _storeCreditCustomer.value ?: return
        if (_busy.value) return
        if (!monitor.isOnline()) { _message.value = TextosCambio.SALDO_SIN_RED; return }
        val (original, request) = pedidoDeTelefonoDelSaldo(c, storeCreditRecord, phone)
            ?: run { _message.value = TextosCambio.SALDO_TELEFONO_INVALIDO; return }
        _busy.value = true
        viewModelScope.launch {
            try {
                customers.updateCustomer(original, request).fold(
                    onSuccess = { updated ->
                        storeCreditRecord = updated
                        _storeCreditCustomer.value = c.copy(phone = updated.phone?.takeIf { it.isNotBlank() }, phoneChecked = true)
                        _message.value = null
                    },
                    onFailure = { e -> _message.value = motivoAlGuardarTelefono(e) },
                )
            } finally { _busy.value = false }
        }
    }

    /** El servidor pidió el teléfono de la clienta (la venta no lo trae): ahora la app lo pide antes de volver a abonar. */
    private fun askPhoneIfServerAsked() {
        if (state.value.code == "STORE_CREDIT_CUSTOMER_PHONE_REQUIRED")
            _storeCreditCustomer.value = _storeCreditCustomer.value?.copy(phone = null, phoneChecked = true)
    }

    fun begin(value: ExchangeDraft) {
        if (!_ready.value || _busy.value || state.value.locked || !allowed()) return
        _message.value = null
        _draft.value = value
        if (value.settlement.kind == "EVEN") perform { send(value, null) }
    }

    fun choose(option: ExchangeSettlementOption) {
        val value = _draft.value ?: return
        if (!allowed() || option.disabledReason != null || option !in options(value)) return
        perform {
            if (option.method == "CARD_TERMINAL") {
                when (val list = terminal.fetchOnlineTerminals()) {
                    is TerminalListResult.Error -> _message.value = list.message
                    is TerminalListResult.Success -> when (list.terminals.size) {
                        0 -> _message.value = TextosCambio.SIN_TERMINALES
                        1 -> send(value, option, list.terminals.single().terminalId)
                        else -> _terminals.value = list.terminals
                    }
                }
            } else send(value, option)
        }
    }

    fun chooseTerminal(id: String) {
        val value = _draft.value ?: return
        if (_terminals.value.none { it.terminalId == id } || !allowed()) return
        perform { send(value, ExchangeSettlement.collectOptions().last(), id) }
    }

    fun dismissChoice() { if (!_busy.value) { _draft.value = null; _terminals.value = emptyList() } }

    private suspend fun send(value: ExchangeDraft, option: ExchangeSettlementOption?, terminalId: String? = null) {
        if (!allowed()) return
        val session = if (option?.method == "CASH") {
            val opened = try { drawer.getOpenSession() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            opened?.takeIf { it.venueId == venue && it.status == "OPEN" && it.id.matches(Regex("c[a-z0-9]{24}")) }?.id
        } else null
        if (!allowed()) return
        val settlement = ExchangeSettlementInput(value.settlement.kind, option?.method,
            value.settlement.expectedDifferenceCents, session, terminalId, option?.cashFromNonCash,
            _storeCreditCustomer.value?.id?.takeIf { option?.method == "STORE_CREDIT" })
        val pending = PendingExchange(requireNotNull(venue), value.originalPaymentId, transaction!!.orderNumber.orEmpty(), actorName,
            freezeExchange(value, settlement, requireNotNull(actor), outbox.deviceId, Instant.now().toString()))
        _draft.value = null; _terminals.value = emptyList()
        submission.submit(pending)
        askPhoneIfServerAsked()
        if (state.value.phase == ExchangeSendPhase.READY_CARD && allowed()) card.run()
    }

    fun retry() {
        if (!_ready.value || !allowed()) return
        perform {
            _message.value = null
            if (submission.pending == null) {
                try { submission.open(requireNotNull(venue), transaction!!.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { _message.value = TextosCambio.RECUPERACION_FALLIDA; return@perform }
            }
            submission.retry()
            askPhoneIfServerAsked()
            if (state.value.phase == ExchangeSendPhase.READY_CARD && allowed()) card.run()
        }
    }

    fun print() {
        val result = state.value.result?.takeIf { it.status == "COMPLETED" } ?: return
        if (!current()) { _message.value = TextosCambio.SESION_CAMBIO; return }
        perform {
            try {
            val outcome = printer.manualPrintReceipt(exchangeReceipt(result, secure.venueName.orEmpty(), actorName, Date()))
            _message.value = when (outcome) {
                is PrinterService.PrintOutcome.Printed -> TextosCambio.TICKET_IMPRESO
                is PrinterService.PrintOutcome.OutOfPaper -> TextosCambio.IMPRESORA_SIN_PAPEL
                is PrinterService.PrintOutcome.NoPrinter -> TextosCambio.SIN_IMPRESORA
                is PrinterService.PrintOutcome.Failed -> TextosCambio.IMPRESION_FALLIDA
            }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _message.value = TextosCambio.IMPRESION_FALLIDA }
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _message.value = if (submission.pending == null) TextosCambio.GUARDADO_FALLIDO else TextosCambio.ENVIO_SIN_CONFIRMAR }
            finally { _busy.value = false }
        }
    }
}
