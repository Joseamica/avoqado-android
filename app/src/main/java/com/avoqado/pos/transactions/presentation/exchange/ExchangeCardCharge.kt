package com.avoqado.pos.transactions.presentation.exchange

import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.transactions.data.jsonReembolsos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** TerminalPaymentService remains the sole owner of request ids and financial evidence. */
class ExchangeCardCharge(private val terminal: TerminalPaymentService, private val submission: ExchangeSubmission) {
    private val mutex = Mutex()

    /**
     * 🔴 F1 bloque 4 (r2 #12): la consulta de la diferencia en duda — al entrar, al reconectar y cada 5 s mientras se vea; una en
     * vuelo (el candado de [run]). Sólo si la tarjeta YA salió: así nunca manda otra. El desenlace va a `finishCard`.
     */
    suspend fun consultar() {
        val p = submission.pending ?: return
        if (!p.cardStarted || p.result?.status != "PENDING_PAYMENT") return
        run()
    }

    suspend fun run() {
        if (!mutex.tryLock()) return
        try {
            val p = submission.pending ?: return
            val result = p.result?.takeIf { it.status == "PENDING_PAYMENT" } ?: return
            val existing = p.cardRequestId ?: terminal.pendienteDeLaVenta(result.newOrderId)
            val outcome = when {
                existing != null -> {
                    // F1 bloque 4: el sondeo de una duda ya guardada no la vuelve «Cobrando…» cada 5 s (la escena de la duda se queda).
                    if (!p.cardStarted || p.cardRequestId != existing) submission.markCardStarted(existing)
                    terminal.resolveOutcome(existing)
                }
                p.cardStarted -> { submission.finishCard(ExchangeCardEvidence.STARTED); return }
                else -> {
                    val body = jsonReembolsos.parseToJsonElement(p.body).jsonObject
                    val terminalId = body.getValue("settlement").jsonObject.getValue("terminalId").jsonPrimitive.content
                    val cents = Math.toIntExact(requireNotNull(result.amountToChargeCents))
                    require(cents > 0)
                    submission.markCardStarted()
                    terminal.sendPaymentToTerminal(terminalId, amountCents = cents, tipCents = 0,
                        orderId = result.newOrderId, processedByStaffId = body.getValue("staffId").jsonPrimitive.content)
                }
            }
            when (outcome) {
                is TerminalPaymentResult.Success -> {
                    submission.finishCard(ExchangeCardEvidence.CHARGED, outcome.requestId)
                    if (submission.pending == null) outcome.requestId?.let(terminal::reconocerCobro)
                }
                is TerminalPaymentResult.Error -> submission.finishCard(ExchangeCardEvidence.NOT_CHARGED)
                is TerminalPaymentResult.Undetermined -> {
                    submission.cardUncertain(outcome.requestId)
                    if (existing != null) return
                    when (val resolved = terminal.resolveOutcome(outcome.requestId)) {
                        is TerminalPaymentResult.Success -> {
                            submission.finishCard(ExchangeCardEvidence.CHARGED, resolved.requestId)
                            if (submission.pending == null) resolved.requestId?.let(terminal::reconocerCobro)
                        }
                        is TerminalPaymentResult.Error -> submission.finishCard(ExchangeCardEvidence.NOT_CHARGED)
                        is TerminalPaymentResult.Undetermined -> submission.cardUncertain(resolved.requestId)
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { submission.cardUncertain() }
        finally { mutex.unlock() }
    }
}
