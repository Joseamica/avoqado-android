package com.avoqado.pos.tables.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TABLE_SERVICE (PRO) — the active table context that wraps the NORMAL selling
 * flow (Square-style: a table is not a separate register, it's a check the
 * regular register is working on).
 *
 * Mirrors the [com.avoqado.pos.pos.data.ClassCheckoutSeed] hand-off pattern:
 * a singleton the Mesas screen writes and the Checkout/Payment screens read.
 * When no session is active every existing flow is byte-identical.
 *
 * Modes:
 * - [Mode.ORDERING]: the cart's CTA becomes "Enviar a cocina" — items picked
 *   with the normal grid/modifiers are appended to the table's open order and
 *   fired as per-station comandas.
 * - [Mode.PAYING]: the payment flow pays the EXISTING table order (no new
 *   order is created) and the table is released on completion.
 */
@Singleton
class TableSession @Inject constructor() {

    enum class Mode { ORDERING, PAYING }

    data class Active(
        val tableId: String,
        val tableNumber: String,
        val areaName: String?,
        val orderId: String,
        val orderNumber: String,
        /** Order.version at session start — optimistic concurrency for add-round. */
        val version: Int,
        /** Order total in cents at session start (PAYING mode seeds the cart with it). */
        val totalCents: Int,
        val mode: Mode,
        /** "Dividir la cuenta" from the check panel: the register auto-opens
         *  the split sheet on arrival (consumed once by CheckoutScreen). */
        val openSplitOnArrival: Boolean = false,
        /** Offline-first: la mesa se abrió SIN red — orderId es un UUID local
         *  hasta que el ack de OPEN_TABLE lo promueva al id real del server. */
        val isProvisional: Boolean = false,
        /** UUID de apertura sin red: el outbox puede seguir usándolo tras la promoción. */
        val localOrderId: String? = null,
        /** Valor de las rondas sin red, compartido aunque se recree el ViewModel. */
        val valorRondasSinRedCents: Int = 0,
        /** Una apertura nueva tiene cero conocido; un resumen provisional reabierto no lo acredita. */
        val saldoSinRedConocido: Boolean = true,
        /** La cuenta cambió al estar en el piso: ningún cache acredita el saldo del servidor. */
        val requiereLecturaServidor: Boolean = false,
        /** Cargo de la cuenta garantizada sin abonos; un cambio de saldo o contexto lo invalida. */
        val serviceChargeCents: Int = 0,
        /** D4 (mesas40): «Atendió» del ticket = el mesero dueño de la cuenta, aunque cobre otra persona. Null ⇒ la sesión. */
        val meseroNombre: String? = null,
        /** D4: platillos y totales de la cuenta garantizada sin abonos; se invalida junto con [serviceChargeCents]. */
        val cuentaDelTicket: CuentaDelTicket? = null,
    ) {
        val label: String get() = "Mesa $tableNumber" + (areaName?.let { " · $it" } ?: "")
        val idsDeLaOrden: Set<String> get() = setOfNotNull(orderId, localOrderId)
    }

    /**
     * D4 (mesas40): la cuenta tal como se leyó del servidor al tocar «Pagar», en centavos. El ticket del cobro imprime sus
     * platillos SÓLO si el cobro completo coincide EXACTO con [baseCents]; si no, el renglón «Cuenta Mesa N» de siempre.
     */
    data class CuentaDelTicket(
        val renglones: List<com.avoqado.pos.printing.data.model.ReceiptItem>,
        val subtotalBrutoCents: Int,
        val descuentoCents: Int,
        val cargoCents: Int,
        val impuestoAparteCents: Int,
    ) {
        /** Lo que la cuenta debe sin propina: la misma suma que el servidor (`ticketTotals.ts`, nunca `Order.total`). */
        val baseCents: Int get() = (subtotalBrutoCents - descuentoCents).coerceAtLeast(0) + cargoCents + impuestoAparteCents
    }

    private val lock = Any()
    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()
    // Sólo memoria del proceso. No reconstruye dinero desde drafts, payloads o catálogo.
    private val estacionadas = mutableMapOf<Pair<String, String>, Active>()
    private val finalizadas = mutableSetOf<Pair<String, String>>()
    private var activeVenue: String? = null
    private var contexto: Pair<String?, String?>? = null
    private var epoch = 0L
    // Un ACK idéntico también invalida una lectura anterior; epoch sigue siendo identidad.
    private var revision = 0L
    fun contextoActual(): Long = synchronized(lock) { epoch }
    fun revisionParaPreCuenta(venueId: String, ids: Set<String>): Long? = synchronized(lock) {
        if (!acepta(venueId) || (activeVenue != null && activeVenue != venueId) || ids.any { (venueId to it) in finalizadas }) null else revision
    }
    fun idsParaPreCuenta(venueId: String, orderId: String): Set<String> = synchronized(lock) {
        val requested = setOf(orderId)
        if (!acepta(venueId)) return@synchronized requested
        val ids = requested.toMutableSet()
        _active.value?.takeIf { (activeVenue == null || activeVenue == venueId) && misma(it, requested) }
            ?.let { ids += it.idsDeLaOrden }
        estacionadas.forEach { (key, saved) ->
            if (key.first == venueId && misma(saved, requested)) ids += saved.idsDeLaOrden
        }
        ids.toSet()
    }
    private fun acepta(venueId: String) = contexto == null || contexto?.second == venueId
    private fun misma(session: Active, ids: Set<String>) = session.idsDeLaOrden.any { it in ids }

    fun sincronizarContexto(userId: String?, venueId: String?) = synchronized(lock) {
        val next = userId to venueId
        if (contexto != next) { clear(); contexto = next }
    }

    fun start(session: Active, venueId: String? = null) = synchronized(lock) {
        if (venueId != null && !acepta(venueId)) return@synchronized
        val previous = _active.value
        val owner = venueId ?: activeVenue?.takeIf { previous != null && misma(previous, session.idsDeLaOrden) }
            ?: contexto?.second
        revision++
        _active.value = session
        activeVenue = owner
        if (owner != null) estacionadas.entries.removeAll { it.key.first == owner && misma(it.value, session.idsDeLaOrden) }
    }

    /** La respuesta de apertura no puede sustituir una cuenta activada mientras esperaba. */
    fun startIfCurrent(session: Active, expected: Active?, venueId: String, expectedEpoch: Long): Boolean = synchronized(lock) {
        if(epoch != expectedEpoch || !acepta(venueId) || (activeVenue != null && activeVenue != venueId) || _active.value != expected) return@synchronized false
        start(session, venueId)
        true
    }

    fun current(): Active? = synchronized(lock) { _active.value }
    fun sumarRondaSinRed(cents: Int) = synchronized(lock) {
        revision++
        _active.update { it?.copy(valorRondasSinRedCents = Math.addExact(it.valorRondasSinRedCents, cents.coerceAtLeast(0))) }
    }
    fun updateVersion(version: Int) = synchronized(lock) { revision++; _active.update { it?.copy(version = version) } }
    fun updateRemaining(remainingCents: Int) = synchronized(lock) { revision++; _active.update { it?.copy(totalCents = remainingCents, serviceChargeCents = 0, cuentaDelTicket = null) } }

    /** Reset de identidad/logout, no navegación. No borra el outbox ni el draft durable. */
    fun clear() = synchronized(lock) {
        epoch++; revision++
        _active.value = null; activeVenue = null; contexto = null
        estacionadas.clear(); finalizadas.clear()
    }

    fun estacionar(venueId: String, expected: Active): Boolean = synchronized(lock) {
        if (!acepta(venueId) || (activeVenue != null && activeVenue != venueId) || _active.value != expected) return@synchronized false
        revision++
        val saved = expected.copy(mode = Mode.ORDERING, serviceChargeCents = 0, cuentaDelTicket = null,
            requiereLecturaServidor = expected.requiereLecturaServidor || !expected.isProvisional)
        estacionadas.entries.removeAll { it.key.first == venueId && misma(it.value, saved.idsDeLaOrden) }
        estacionadas[venueId to saved.orderId] = saved
        _active.value = null; activeVenue = null
        true
    }

    /** Sólo los callers del piso/selector llaman este método; start nunca restaura automáticamente. */
    fun abrirDesdePiso(venueId: String, resumen: Active): Active? = synchronized(lock) {
        if (!acepta(venueId)) return@synchronized null
        revision++
        val live = _active.value?.takeIf { (activeVenue == null || activeVenue == venueId) && it.tableId == resumen.tableId && misma(it, resumen.idsDeLaOrden) }
        val entry = estacionadas.entries.firstOrNull { it.key.first == venueId && it.value.tableId == resumen.tableId && misma(it.value, resumen.idsDeLaOrden) }
        val wasFinalized = resumen.idsDeLaOrden.any { (venueId to it) in finalizadas }
        val restored = (live ?: entry?.value ?: resumen.copy(saldoSinRedConocido = !resumen.isProvisional,
            requiereLecturaServidor = resumen.requiereLecturaServidor || wasFinalized)).copy(mode = Mode.ORDERING, serviceChargeCents = 0, cuentaDelTicket = null)
        if (entry != null) estacionadas.remove(entry.key)
        _active.value = restored; activeVenue = venueId
        restored
    }

    fun requiereRevisarSaldo(venueId: String, ids: Set<String>): Boolean = synchronized(lock) {
        if (!acepta(venueId)) return@synchronized true
        val current = _active.value
        (current != null && (activeVenue == null || activeVenue == venueId) && misma(current, ids) &&
            current.mode != Mode.PAYING && (current.requiereLecturaServidor || !current.saldoSinRedConocido)) ||
            estacionadas.any { it.key.first == venueId && misma(it.value, ids) && (it.value.requiereLecturaServidor || !it.value.saldoSinRedConocido) } ||
            ids.any { (venueId to it) in finalizadas }
    }

    fun finalizar(venueId: String, ids: Set<String>, expectedEpoch: Long = contextoActual()) = synchronized(lock) {
        if (!acepta(venueId) || epoch != expectedEpoch) return@synchronized
        revision++
        val related = estacionadas.filter { it.key.first == venueId && misma(it.value, ids) }
        val allIds = ids + related.values.flatMap { it.idsDeLaOrden }
        related.keys.forEach { estacionadas.remove(it) }
        val current = _active.value
        if ((activeVenue == null || activeVenue == venueId) && current != null && misma(current, allIds)) {
            finalizadas.addAll(current.idsDeLaOrden.map { venueId to it })
            _active.value = null; activeVenue = null
        }
        finalizadas.addAll(allIds.map { venueId to it })
    }

    fun marcarSaldoDesconocido(venueId: String, ids: Set<String>, expectedEpoch: Long) = synchronized(lock) {
        if (!acepta(venueId) || epoch != expectedEpoch) return@synchronized
        revision++
        estacionadas.replaceAll { key, value -> if (key.first == venueId && misma(value, ids)) value.copy(saldoSinRedConocido = false, requiereLecturaServidor = true, serviceChargeCents = 0, cuentaDelTicket = null) else value }
        _active.update { if ((activeVenue == null || activeVenue == venueId) && it != null && misma(it, ids)) it.copy(saldoSinRedConocido = false, requiereLecturaServidor = true, serviceChargeCents = 0, cuentaDelTicket = null) else it }
    }

    /** Compatibilidad del contrato anterior: sólo la Active actual. */
    fun promoteProvisional(localOrderId: String, orderId: String, orderNumber: String?, version: Int) = synchronized(lock) {
        revision++
        _active.update { current -> promover(current, localOrderId, orderId, orderNumber, version) }
    }
    private fun promover(current: Active?, local: String, real: String, number: String?, version: Int): Active? =
        if (current?.isProvisional != true || current.orderId != local) current
        else current.copy(orderId = real, orderNumber = number ?: current.orderNumber, version = version,
            isProvisional = false, localOrderId = current.localOrderId ?: local, valorRondasSinRedCents = 0,
            requiereLecturaServidor = true, serviceChargeCents = 0, cuentaDelTicket = null)

    fun promoteProvisional(venueId: String, localOrderId: String, orderId: String, orderNumber: String?, version: Int,
        expectedEpoch: Long) = synchronized(lock) {
        if (!acepta(venueId) || epoch != expectedEpoch) return@synchronized
        revision++
        if (activeVenue == null || activeVenue == venueId) _active.update { promover(it, localOrderId, orderId, orderNumber, version) }
        val entry = estacionadas.entries.firstOrNull { it.key.first == venueId && it.value.isProvisional && it.value.orderId == localOrderId }
        if (entry != null) {
            val promoted = promover(entry.value, localOrderId, orderId, orderNumber, version)!!
            estacionadas.remove(entry.key)
            estacionadas[venueId to promoted.orderId] = promoted
        }
    }

    fun confirmarCambio(venueId: String, ids: Set<String>, version: Int?, remainingCents: Int?, expectedEpoch: Long) = synchronized(lock) {
        if (!acepta(venueId) || epoch != expectedEpoch) return@synchronized
        revision++
        fun changed(s: Active) = s.copy(version = version ?: s.version,
            totalCents = remainingCents?.takeIf { it >= 0 } ?: s.totalCents,
            saldoSinRedConocido = s.saldoSinRedConocido && !(s.isProvisional && remainingCents != null), requiereLecturaServidor = true, serviceChargeCents = 0, cuentaDelTicket = null)
        estacionadas.replaceAll { key, value -> if (key.first == venueId && misma(value, ids)) changed(value) else value }
        _active.update { if ((activeVenue == null || activeVenue == venueId) && it != null && misma(it, ids)) changed(it) else it }
    }
}
