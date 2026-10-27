package com.avoqado.pos.transactions.presentation.exchange

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.Transaction
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

/** Screen lifecycle only; submission and terminal service retain ownership of financial outcomes. */
@HiltViewModel
class ExchangePaymentViewModel @Inject constructor(repository: ExchangeRepository, private val store: ExchangePendingStore,
    private val secure: SecureStorage, private val outbox: SyncOutbox, private val drawer: CashDrawerRepository,
    private val terminal: TerminalPaymentService, private val role: RoleManager, private val printer: PrinterService,
    private val monitor: ConnectivityMonitor) : ViewModel() {
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

    fun options(value: ExchangeDraft): List<ExchangeSettlementOption> = if (value.settlement.kind == "COLLECT")
        ExchangeSettlement.collectOptions() else ExchangeSettlement.payoutOptions(transaction?.payoutOptions,
            -value.settlement.expectedDifferenceCents, role.canRefundToCash)

    fun begin(value: ExchangeDraft) {
        if (!_ready.value || _busy.value || state.value.locked || !allowed()) return
        _message.value = null
        _draft.value = value
        if (value.settlement.kind == "EVEN") perform { send(value, null) }
    }

    fun choose(option: ExchangeSettlementOption) {
        val value = _draft.value ?: return
        if (!allowed() || option !in options(value)) return
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
            val opened = drawer.getOpenSession()
            if (opened == null || opened.venueId != venue || opened.status != "OPEN") {
                _message.value = TextosCambio.SIN_CAJA; return
            }
            if (!opened.id.matches(Regex("c[a-z0-9]{24}"))) {
                _message.value = TextosCambio.CAJA_SIN_CONFIRMAR; return
            }
            opened.id
        } else null
        if (!allowed()) return
        val settlement = ExchangeSettlementInput(value.settlement.kind, option?.method,
            value.settlement.expectedDifferenceCents, session, terminalId, option?.cashFromNonCash)
        val pending = PendingExchange(requireNotNull(venue), value.originalPaymentId, transaction!!.orderNumber.orEmpty(), actorName,
            freezeExchange(value, settlement, requireNotNull(actor), outbox.deviceId, Instant.now().toString()))
        _draft.value = null; _terminals.value = emptyList()
        submission.submit(pending)
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
