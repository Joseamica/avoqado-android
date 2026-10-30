package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.transactions.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class ExchangeSendPhase { EDITING, SENDING, UNCERTAIN, READY_CARD, PROCESSING_CARD, RECONFIRM, COMPLETED, CANCELLED, REVIEW }
enum class ExchangeCardEvidence { STARTED, CHARGED, NOT_CHARGED }
data class ExchangeSendState(val phase: ExchangeSendPhase = ExchangeSendPhase.EDITING, val message: String? = null,
    val action: String? = null, val locked: Boolean = false, val calculation: ExchangeServerCalculation? = null,
    val result: ExchangeResult? = null)

/** Owns a frozen financial operation, independently of the editable selection. */
class ExchangeSubmission(private val repository: ExchangeRepository, private val store: ExchangePendingStore) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ExchangeSendState())
    val state = _state.asStateFlow()
    var pending: PendingExchange? = null
        private set

    suspend fun open(venue: String, payment: String, isCurrent: () -> Boolean = { true }) = mutex.withLock {
        pending = null
        _state.value = ExchangeSendState(ExchangeSendPhase.UNCERTAIN, TextosCambio.ENVIO_SIN_CONFIRMAR,
            TextosCambio.CONFIRMAR_ESTADO, true)
        val loaded = store.load(venue, payment)
        if (!isCurrent()) return@withLock
        pending = loaded
        _state.value = pending?.let { ExchangeSendState(ExchangeSendPhase.UNCERTAIN,
            TextosCambio.CAMBIO_SIN_CONFIRMAR.replace("{folio}", it.originalOrderNumber),
            if (it.result?.status == "PENDING_PAYMENT" && !it.cardStarted) TextosCambio.COBRAR_DIFERENCIA
                else TextosCambio.CONFIRMAR_ESTADO, true) }
            ?: ExchangeSendState()
    }

    suspend fun submit(value: PendingExchange) = mutex.withLock {
        if (pending != null) return@withLock
        // Saving failure cannot reach the network; an interrupted response leaves this exact body recoverable.
        pending = store.save(value)
        sendCreate()
    }

    suspend fun retry() = mutex.withLock { if (pending != null) sendCreate() }

    private suspend fun sendCreate() {
        val p = pending ?: return
        _state.value = ExchangeSendState(ExchangeSendPhase.SENDING, locked = true)
        try { accept(repository.create(p.body, p.venueId)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(e, creation = true) }
    }

    suspend fun markCardStarted(requestId: String? = null): PendingExchange = mutex.withLock {
        val p = requireNotNull(pending).let { it.copy(cardStarted = true, cardRequestId = requestId ?: it.cardRequestId) }
        pending = store.save(p)
        _state.value = ExchangeSendState(ExchangeSendPhase.PROCESSING_CARD, TextosCambio.COBRANDO_DIFERENCIA, locked = true)
        requireNotNull(pending)
    }

    suspend fun cardUncertain(requestId: String? = null) {
        val p = pending ?: return
        val saved = p.copy(cardRequestId = requestId ?: p.cardRequestId)
        pending = store.save(saved)
        _state.value = ExchangeSendState(ExchangeSendPhase.UNCERTAIN, TextosCambio.COBRO_EN_REVISION,
            TextosCambio.CONFIRMAR_ESTADO, true, result = p.result)
    }

    suspend fun finishCard(evidence: ExchangeCardEvidence, requestId: String? = null) {
        if (pending == null) return
        val p = if (evidence == ExchangeCardEvidence.CHARGED) markCardStarted(requestId) else requireNotNull(pending)
        try {
            val id = requireNotNull(p.result).id
            accept(if (evidence != ExchangeCardEvidence.NOT_CHARGED) repository.complete(id, p.venueId)
                else repository.cancel(id, venueId = p.venueId))
        } catch (e: CancellationException) { throw e }
        catch (e: ExchangeApiException) {
            when (e.code) {
                "EXCHANGE_CHARGE_EXISTS", "EXCHANGE_ALREADY_SETTLED" -> sendCreate()
                "EXCHANGE_NOT_CHARGED" -> if (evidence == ExchangeCardEvidence.STARTED)
                    finishCard(ExchangeCardEvidence.NOT_CHARGED) else cardUncertain(requestId)
                "EXCHANGE_CHARGE_UNRESOLVED" -> cardUncertain()
                else -> failure(e, creation = false)
            }
        } catch (e: Exception) { failure(e, creation = false) }
    }

    private suspend fun accept(result: ExchangeResult) {
        val p = requireNotNull(pending)
        check(p.result == null || p.result!!.id == result.id)
        val final = result.status in setOf("COMPLETED", "CANCELLED", "NEEDS_REVIEW")
        if (final) { store.remove(p); pending = null }
        else pending = store.save(p.copy(result = result))
        _state.value = when (result.status) {
            "COMPLETED" -> ExchangeSendState(ExchangeSendPhase.COMPLETED, TextosCambio.CAMBIO_HECHO, result = result, locked = true)
            "CANCELLED" -> ExchangeSendState(ExchangeSendPhase.CANCELLED,
                if (p.cardStarted) TextosCambio.NO_COBRADO_CANCELADO else TextosCambio.CANCELADO,
                TextosCambio.PROBAR_OTRA_VEZ, true, result = result)
            "PENDING_PAYMENT" -> ExchangeSendState(ExchangeSendPhase.READY_CARD, TextosCambio.COBRANDO_DIFERENCIA,
                if (!p.cardStarted) TextosCambio.COBRAR_DIFERENCIA else TextosCambio.CONFIRMAR_ESTADO, true, result = result)
            else -> ExchangeSendState(ExchangeSendPhase.REVIEW, TextosCambio.POR_REVISAR, locked = true, result = result)
        }
    }

    private suspend fun failure(error: Exception, creation: Boolean) {
        val e = error as? ExchangeApiException
        val definitive = creation && e != null && e.code != "ACTOR_MISMATCH" && (
            e.statusCode in setOf(400, 404) ||
            (e.statusCode == 422 && e.code in setOf("EXCHANGES_DISABLED", "EXCHANGE_ITEM_NOT_RETURNABLE",
                "EXCHANGE_TAKEN_NOT_SELLABLE", "EXCHANGE_SETTLEMENT_UNAVAILABLE")) ||
            (e.statusCode == 409 && e.code in setOf("EXCHANGE_DIFFERENCE_CHANGED", "IDEMPOTENCY_KEY_REUSED")))
        val calculation = if (e?.code == "EXCHANGE_DIFFERENCE_CHANGED") runCatching {
            jsonReembolsos.decodeFromJsonElement<ExchangeServerCalculation>(e.details!!.jsonObject.getValue("calculation"))
        }.getOrNull() else null
        val message = when (e?.code) {
            "ACTOR_MISMATCH" -> TextosCambio.ACTOR_MISMATCH.replace("{nombre}", pending?.actorName.orEmpty())
            "IDEMPOTENCY_KEY_REUSED" -> TextosCambio.IDEMPOTENCY_KEY_REUSED
            else -> e?.message ?: TextosCambio.ENVIO_SIN_CONFIRMAR
        }
        if (definitive) { pending?.let { store.remove(it) }; pending = null }
        _state.value = ExchangeSendState(if (calculation != null) ExchangeSendPhase.RECONFIRM
            else if (definitive) ExchangeSendPhase.EDITING else ExchangeSendPhase.UNCERTAIN,
            message, if (definitive) null else TextosCambio.REINTENTAR, !definitive, calculation)
    }
}
