package com.avoqado.pos.core.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import kotlinx.serialization.json.*

@Dao
interface PendingPaymentDao {
    @Query(Q16_RECOVER_ORDER_SQL)
    suspend fun recuperarOrdenSqlQ16(venueId: String, id: String, originalOrderId: String?, recoveredOrderId: String): Int
    suspend fun recuperarOrdenQ16(venueId: String, id: String, originalOrderId: String?, recoveredOrderId: String): Int =
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(venueId) {
            recuperarOrdenSqlQ16(venueId,id,originalOrderId,recoveredOrderId)
        }
    @Query(Q16_RELEASE_SALE_SQL)
    suspend fun soltarVentaSinRedSqlQ16(venueId: String, id: String): Int
    suspend fun soltarVentaSinRedQ16(venueId: String, id: String): Int =
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(venueId) { soltarVentaSinRedSqlQ16(venueId,id) }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawSqlQ16(payment: PendingPaymentEntity)
    suspend fun insertRaw(payment: PendingPaymentEntity) = com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(payment.venueId) {
        insertRawSqlQ16(payment)
    }


    suspend fun insert(payment: PendingPaymentEntity) = com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(payment.venueId) {
        insertTransactionQ16(payment)
    }
    @androidx.room.Transaction
    suspend fun insertTransactionQ16(payment: PendingPaymentEntity) {
        val old = findByIdQ15(payment.id)
        require(old == null || old.venueId == payment.venueId) { "La reserva pertenece a otro negocio" }
        require(old==null || payment.q15CaptureJson==null || (old.q15CaptureJson!=null && old.q15Reason==null)) {
            "Falta comprobar la evidencia original de este pago"
        }
        require(payment.orderId?.isBlank() != true &&
            (old?.orderId == null || payment.orderId == null || old.orderId == payment.orderId)) {
            "La misma llave no puede cambiar de cuenta"
        }
        val capturaOriginal = old?.let { ConciliacionDelCobro.leerCaptura(it.q15CaptureJson, it.venueId, it.id) }
        // La captura inicial decide el alcance; un precio positivo que luego bajó a cero sigue fuera.
        // Sin captura legible, una reserva cero se conserva cerrada antes de admitir otra evidencia.
        val protegerCeroOriginal = old != null && old.paymentType == "ORDER" &&
            if (capturaOriginal != null) {
                capturaOriginal.amountCents == 0 && capturaOriginal.tipCents == 0 && capturaOriginal.method == "CASH" &&
                    capturaOriginal.tenderTypeId == null && capturaOriginal.tenderRevision == null
            } else old.amountCents == 0 && old.tipCents == 0 && old.method == "CASH" &&
                old.tenderTypeId == null && old.tenderRevision == null
        if (old != null && protegerCeroOriginal) {
            require(old.amountCents == payment.amountCents && old.tipCents == payment.tipCents &&
                old.method == payment.method && old.paymentType == payment.paymentType &&
                old.tenderTypeId == payment.tenderTypeId && old.tenderRevision == payment.tenderRevision &&
                old.splitType == payment.splitType && old.customerId == payment.customerId &&
                old.itemsJson == payment.itemsJson &&
                (payment.cashTenderedCents == null || old.cashTenderedCents == payment.cashTenderedCents) &&
                (payment.changeCents == null || old.changeCents == payment.changeCents)) {
                "La misma llave debe conservar el importe y los datos originales del cobro"
            }
            if (old.q15CaptureJson != null) {
                val original = requireNotNull(capturaOriginal) {
                    "Falta comprobar la evidencia original de este pago"
                }
                val incoming = requireNotNull(ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson, payment.venueId, payment.id))
                // Un intento real cambia su reloj, no el pedido: el resto del cuerpo se valida antes de conservarlo.
                val mismoPedido = original.orderRequestJson == incoming.orderRequestJson || runCatching {
                    val antes = Json.parseToJsonElement(requireNotNull(original.orderRequestJson)).jsonObject
                    val ahora = Json.parseToJsonElement(requireNotNull(incoming.orderRequestJson)).jsonObject
                    val fechaAntes = antes["createdAtLocal"] as? JsonPrimitive
                    val fechaAhora = ahora["createdAtLocal"] as? JsonPrimitive
                    fechaAntes?.takeIf { !it.isString }?.longOrNull != null &&
                        fechaAhora?.takeIf { !it.isString }?.longOrNull == payment.createdAt &&
                        antes.filterKeys { it != "createdAtLocal" } == ahora.filterKeys { it != "createdAtLocal" }
                }.getOrDefault(false)
                require(original.amountCents == old.amountCents && original.tipCents == old.tipCents &&
                    (original.orderId == null || original.orderId == old.orderId) &&
                    original.cashTenderedCents == old.cashTenderedCents && original.changeCents == old.changeCents &&
                    original.orderRequestJson == old.orderRequestJson && mismoPedido &&
                    incoming.orderRequestJson == payment.orderRequestJson && incoming.copy(
                        orderId = original.orderId,
                        orderRequestJson = original.orderRequestJson,
                        daPorCobradaAlAbandonar = original.daPorCobradaAlAbandonar,
                        primeraCapturaEn = original.primeraCapturaEn,
                        ventasYaEnLaCaja = original.ventasYaEnLaCaja,
                        cashTenderedCents = incoming.cashTenderedCents ?: original.cashTenderedCents,
                        changeCents = incoming.changeCents ?: original.changeCents) == original) {
                    "La misma llave debe conservar la captura y el pedido originales"
                }
            } else require(old.orderRequestJson == payment.orderRequestJson) {
                "La misma llave debe conservar el pedido original"
            }
        }
        if (old?.syncStatus == "SYNCED") return
        insertRaw(filaAlReemplazar(old, payment, protegerCeroOriginal))
    }

    @Query("SELECT * FROM pending_payments WHERE id=:id")
    suspend fun findByIdQ15(id: String): PendingPaymentEntity?
    @Query("SELECT * FROM pending_payments WHERE venueId=:venueId AND id=:id")
    suspend fun findForVenue(venueId: String, id: String): PendingPaymentEntity?
    /** La confirmación canónica y la autoridad actual pertenecen al servicio; aquí se conserva la fuente completa. */
    suspend fun reanudarCortesiaAntiguaQ4(original: PendingPaymentEntity, sigueVigente: () -> Boolean): Int =
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(original.venueId) {
            reanudarCortesiaAntiguaTxQ4(original, sigueVigente)
        }
    @androidx.room.Transaction
    suspend fun reanudarCortesiaAntiguaTxQ4(original: PendingPaymentEntity, sigueVigente: () -> Boolean): Int {
        val current = findForVenue(original.venueId, original.id)
        // El writer de Room ya se obtuvo; comparar los 33 campos incluye todos los JSON/NULL y el diagnóstico actual.
        if (!sigueVigente() || current != original || original.syncStatus != "FAILED" || original.q15Reason != null ||
            original.paymentType != "ORDER" || original.method != "CASH" || original.amountCents != 0 || original.tipCents != 0 ||
            original.tenderTypeId != null || original.tenderRevision != null) return 0
        if (!sigueVigente()) return 0
        return reanudarCortesiaAntiguaSqlQ4(original.venueId, original.id)
    }
    @Query(Q4_COURTESY_RETRY_SQL)
    suspend fun reanudarCortesiaAntiguaSqlQ4(venueId: String, id: String): Int

    @Query(Q15_PAYMENT_RESULT_SQL)
    suspend fun registrarResultadoQ15(venueId: String, id: String, aplicado: Boolean, motivo: String?, resultadoJson: String?, ahora: Long): Int
    suspend fun recuperarResultadoPorLlaveQ15(original: PendingPaymentEntity, motivo: String?, resultadoJson: String,
        ahora: Long, sigueVigente: () -> Boolean): Int = com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(original.venueId) {
        recuperarResultadoPorLlaveTxQ15(original, motivo, resultadoJson, ahora, sigueVigente)
    }
    @androidx.room.Transaction
    suspend fun recuperarResultadoPorLlaveTxQ15(original: PendingPaymentEntity, motivo: String?, resultadoJson: String,
        ahora: Long, sigueVigente: () -> Boolean): Int {
        // El writer ya está tomado: la igualdad completa protege también autor, caja, captura, historia y ticket.
        if (!sigueVigente() || findForVenue(original.venueId, original.id) != original ||
            com.avoqado.pos.payment.data.ResultadoDeEfectivoPorLlave.captura(original) == null || !sigueVigente()) return 0
        return registrarResultadoQ15(original.venueId, original.id, true, motivo, resultadoJson, ahora)
    }
    @Query(Q15_PAYMENT_HOLD_SQL)
    suspend fun retenerPagoQ15(venueId: String, id: String, motivo: String, ahora: Long): Int
    @Query(Q15_PAYMENT_REVIEW_SQL)
    suspend fun revisarPagoQ15(venueId: String, id: String, actor: String, rol: String, ahora: Long): Int
    @Query(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL)
    suspend fun revisarCanceladoSinCobroQ15(
        venueId: String, id: String, originalOrderId: String?, originalOrderRequestJson: String?,
        originalResultJson: String?, originalSyncStatus: String, originalCaptureJson: String?,
        originalCreatedAt: Long, originalCapturedOrderRef: String?, originalSplitType: String,
        originalCustomerId: String?, originalCashTenderedCents: Int?, originalChangeCents: Int?, originalReason: String?,
        resultadoJson: String, actor: String, rol: String, ahora: Long,
    ): Int
    @Query(Q15_INTENT_REVIEW_SQL)
    suspend fun revisarIntentQ15(venueId: String, id: String, actor: String, rol: String, ahora: Long): Int
    @Query(Q15_HISTORY_COUNT_SQL)
    suspend fun contarRevisionesQ15(venueId: String, revisadas: Boolean, busqueda: String): Int
    @Query(Q15_HISTORY_PAGE_SQL)
    suspend fun paginaRevisionesQ15(venueId: String, revisadas: Boolean, busqueda: String, cursorFecha: Long?, cursorOrigen: String?, cursorId: String?): List<RevisionDelCobroRecord>
    @Query(Q15_CAPTURED_ORDER_EXISTS_SQL)
    suspend fun hayCapturaDeOrdenQ15(venueId: String, orderRef: String): Boolean

    @Query("SELECT * FROM pending_payments WHERE syncStatus = 'PENDING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingPayments(limit: Int = 10): List<PendingPaymentEntity>

    @Query("SELECT * FROM pending_payments WHERE syncStatus IN ('PENDING', 'SYNCING') ORDER BY createdAt ASC")
    suspend fun getUnsyncedPayments(): List<PendingPaymentEntity>

    @Query(Q15_FAILED_ORDINARY_SQL)
    suspend fun fallidosOrdinariosQ15(): List<PendingPaymentEntity>
    @Query(Q15_CANCELLED_STATE_PAGE_SQL)
    suspend fun canceladosCandidatosQ15(cursor: String, estado: String = "FAILED"): List<PendingPaymentEntity>
    suspend fun getFailedPayments(): List<PendingPaymentEntity> {
        val result = fallidosOrdinariosQ15().toMutableList()
        var cursor = ""
        do {
            val page = canceladosCandidatosQ15(cursor)
            result.addAll(page.filterNot(RevisionCanceladoCero::revisado))
            if (page.size < 50) break
            cursor = page.last().id
        } while (true)
        return result
    }

    /**
     * Los cobros encolados de UN local. Lo usa el cajón (`PendingCashSales`) para no
     * borrar de la pantalla una venta que sí está en el cajón pero que el server aún
     * no puede conocer.
     *
     * 🔴 Devuelve la fila ENTERA y no filtra por estado ni por método a propósito:
     * ésas son decisiones de DINERO y viven en `PendingCashSales`, en Kotlin, donde
     * los tests las fijan. Un filtro escondido en este `@Query` no lo ejercita ninguna
     * prueba de la suite unitaria — se podría aflojar y todo seguiría verde.
     */
    @Query("SELECT * FROM pending_payments WHERE venueId = :venueId")
    suspend fun forVenue(venueId: String): List<PendingPaymentEntity>

    @Query("UPDATE pending_payments SET syncStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE pending_payments SET syncStatus = :status, lastError = :error WHERE id = :id")
    suspend fun updateStatusWithError(id: String, status: String, error: String?)

    @Query("UPDATE pending_payments SET retryCount = retryCount + 1, lastRetryAt = :timestamp, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun incrementRetry(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pending_payments SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCING'")
    suspend fun resetSyncingToPending()

    @Query("UPDATE pending_payments SET syncStatus = 'PENDING', retryCount = 0, lastError = NULL, lastRetryAt = NULL WHERE id = :id AND syncStatus = 'FAILED' AND q15Reason IS NULL")
    suspend fun retryFailed(id: String)

    /** Estado actual de UN cobro. Lo usa el reintento manual para saber cómo le
     *  fue de verdad, en vez de cantar éxito a ciegas. */
    @Query("SELECT syncStatus FROM pending_payments WHERE id = :id")
    suspend fun syncStatusOf(id: String): String?

    @Query("SELECT lastError FROM pending_payments WHERE id = :id")
    suspend fun lastErrorOf(id: String): String?

    /**
     * "Marcar resuelta": el gerente decidió que este cobro ya no debe
     * reintentarse (típico: el server confirma "ya está pagada" — el dinero SÍ
     * entró y reintentar jamás va a prosperar). SOLO borra filas FAILED: una
     * PENDING/SYNCING sigue viva y borrarla perdería un cobro real.
     */
    @Query(Q15_PAYMENT_DELETE_FAILED_SQL)
    suspend fun deleteFailed(id: String): Int

    @Query(Q15_PAYMENT_TTL_SQL)
    suspend fun deleteSynced(olderThan: Long)

    // MARK: - Cobro en vuelo (guardado ANTES de tocar la red; ver `CashPaymentRepository.reservarCobro`)

    /** Retain uncertain reservations; neither cleanup nor an assertion erases payment evidence. */
    @Query("DELETE FROM pending_payments WHERE id = :id AND syncStatus = 'EN_VUELO' AND 0")
    suspend fun borrarSiEnVuelo(id: String): Int

    /** «Sí se cobró»: la reserva pasa a la cola con su MISMA llave (el servidor deduplica si ya la tenía). */
    @Query(EN_VUELO_A_PENDIENTE_SQL)
    suspend fun enVueloAPendiente(id: String): Int

    /**
     * 🔴 D2 v7 (24-oct): la cajera decidió sobre ESA venta —tocó «Cancelar» o la volvió a cobrar con otra llave— y su apartado
     * sobra. Sólo un `EN_VUELO` con captura y sin motivo Q15: una fila heredada sin captura, una por revisar, una encolada o una
     * que ya subió no se tocan. Nunca se llama por lo que contestó la red.
     */
    @Query(DESCARTAR_RESERVA_RECHAZADA_SQL)
    suspend fun descartarReservaRechazada(id: String): Int

    /**
     * Codex r10 #1 y agregado 1 de la ronda corta: el importe que de verdad se cobra (precio aceptado, total adoptado) y la orden que
     * acaba de nacer, en el apartado que sigue `EN_VUELO`, en UNA escritura antes del `/pay`. Sólo vía
     * `CashPaymentRepository.actualizarApartado` (pasa por `AdmisionDePrecioQ16.write`). La captura no se toca.
     */
    @Query(
        "UPDATE pending_payments SET amountCents = :amountCents, orderId = :orderId, lineasDelTicketJson = :lineasDelTicketJson " +
            "WHERE id = :id AND syncStatus = 'EN_VUELO'",
    )
    suspend fun actualizarApartadoEnVuelo(id: String, amountCents: Int, orderId: String, lineasDelTicketJson: String? = null): Int

    /**
     * Agregado 5 y Codex r11 #2: una TANDA de los cobros de efectivo PURO que todavía pueden subir, de UN negocio y de las cajas
     * [cajas], en orden (`createdAt`, `id`) desde la fila que sigue a ([despuesDe], [despuesDeId]). Quien llama recorre TODAS las tandas
     * (`PendingCashSales.paresExactos`): nada se trunca. El SQL acota; quien decide es `PendingCashSales` en Kotlin.
     */
    @Query(COBROS_DE_EFECTIVO_VIVOS_SQL)
    suspend fun cobrosDeEfectivoVivos(
        venueId: String,
        cajas: List<String>,
        despuesDe: Long,
        despuesDeId: String,
        tanda: Int,
    ): List<PendingPaymentEntity>

    /**
     * El apartado de un intento NUEVO de la misma venta (otra llave, p. ej. tras el 409 de precio) y el borrado de los apartados
     * de sus intentos anteriores van en UNA escritura: si el guardado falla, los viejos se quedan (nunca dos filas por un billete,
     * y nunca ninguna).
     */
    suspend fun reservarEnLugarDe(payment: PendingPaymentEntity, anteriores: Collection<String>) =
        com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(payment.venueId) { reservarEnLugarDeTransaccion(payment, anteriores) }
    /**
     * ⚠️ Sólo a través de [reservarEnLugarDe]: llamarlo directo se salta `AdmisionDePrecioQ16.write`. Es público porque un DAO de
     * Room es una interfaz y su `@Transaction` necesita que Room genere la implementación (igual que [insertTransactionQ16]).
     */
    @androidx.room.Transaction
    suspend fun reservarEnLugarDeTransaccion(payment: PendingPaymentEntity, anteriores: Collection<String>) {
        for (id in anteriores) if (id != payment.id) descartarReservaRechazada(id)
        insertTransactionQ16(payment)
    }

    @Query("SELECT * FROM pending_payments WHERE venueId = :venueId AND syncStatus = 'EN_VUELO' ORDER BY createdAt ASC")
    suspend fun enVueloDelVenue(venueId: String): List<PendingPaymentEntity>

    @Query("SELECT * FROM pending_payments WHERE syncStatus = 'EN_VUELO' ORDER BY createdAt ASC")
    fun observarEnVuelo(): Flow<List<PendingPaymentEntity>>

    @Query(Q15_EN_VUELO_COUNT_SQL)
    suspend fun contarEnVueloSqlQ15(): Int
    @androidx.room.Transaction
    suspend fun enVueloCount(): Int {
        var count = contarEnVueloSqlQ15()
        var cursor = ""
        do {
            val page = canceladosCandidatosQ15(cursor, "EN_VUELO")
            count += page.count { !RevisionCanceladoCero.revisado(it) }
            if (page.size < 50) break
            cursor = page.last().id
        } while (true)
        return count
    }

    /** El estado de UN cobro encolado, en vivo. Lo mira la pantalla de resultado para no mentir. */
    @Query("SELECT syncStatus FROM pending_payments WHERE id = :id")
    fun observarEstado(id: String): Flow<String?>

    /** UNA fila de la cola, en vivo (D1: la parte de una venta dividida que se guardó sin red espera a que suba). */
    @Query("SELECT * FROM pending_payments WHERE venueId = :venueId AND id = :id")
    fun observarFila(venueId: String, id: String): Flow<PendingPaymentEntity?>

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus='FAILED'")
    fun observarRecuentoFallidosQ15(): Flow<Int>
    @Query(Q15_FAILED_ORDINARY_COUNT_SQL)
    suspend fun contarFallidosOrdinariosQ15(): Int
    fun getFailedCount(): Flow<Int> = observarRecuentoFallidosQ15().map {
        var count = contarFallidosOrdinariosQ15()
        var cursor = ""
        do {
            val page = canceladosCandidatosQ15(cursor)
            count += page.count { !RevisionCanceladoCero.revisado(it) }
            if (page.size < 50) break
            cursor = page.last().id
        } while (true)
        count
    }

    @Query("DELETE FROM pending_payments WHERE syncStatus='SYNCED' AND q15CaptureJson IS NULL AND q15Reason IS NULL")
    suspend fun deleteAll()

    @Query("DELETE FROM pending_payments WHERE venueId = :venueId AND syncStatus='SYNCED' AND q15CaptureJson IS NULL AND q15Reason IS NULL")
    suspend fun deleteForVenue(venueId: String)
}

/**
 * La fila que queda en disco cuando un cobro se vuelve a escribir con la MISMA llave (la reserva que se encola tras el fallo,
 * un reintento del mismo cobro). Lo que ya se capturó de la venta no lo pisa la escritura nueva: la evidencia Q15 y, desde
 * la v17, la caja de la venta.
 *
 * 🔴 «La primera captura manda» con la caja (efectivo sin red en el turno correcto, 21-oct): la caja se leyó UNA vez, al
 * confirmar el cobro, y se guardó en la reserva antes de la red. Si el reemplazo trajera otra (la adopción de una caja ajena
 * cambió la que está abierta mientras el POST esperaba) o ninguna, la venta acabaría en el turno de esa otra caja. Y una fila
 * de antes de la v17 conserva su `null`: no se le inventa una caja que no se sabe si fue la suya.
 */
internal fun filaAlReemplazar(old: PendingPaymentEntity?, payment: PendingPaymentEntity, protegerCeroOriginal: Boolean = false): PendingPaymentEntity =
    if (old == null) payment else payment.copy(
        staffId = old.staffId,
        orderId = old.orderId ?: payment.orderId,
        orderRequestJson = old.orderRequestJson,
        cashTenderedCents = payment.cashTenderedCents ?: old.cashTenderedCents,
        changeCents = payment.changeCents ?: old.changeCents,
        q15CaptureJson = old.q15CaptureJson ?: payment.q15CaptureJson,
        capturedOrderRef = if (protegerCeroOriginal) old.capturedOrderRef else old.capturedOrderRef ?: payment.capturedOrderRef,
        q15InitialResultJson = old.q15InitialResultJson, q15ResultJson = old.q15ResultJson,
        q15Reason = old.q15Reason, q15DetectedAt = old.q15DetectedAt,
        q15ReviewedAt = old.q15ReviewedAt, q15ReviewedBy = old.q15ReviewedBy, q15ReviewRole = old.q15ReviewRole,
        cashDrawerSessionId = old.cashDrawerSessionId,
        // D1: la copia sigue al importe de ESTA escritura (la fila toma el de `payment`); si ésta no la trae, se conserva la de disco.
        lineasDelTicketJson = payment.lineasDelTicketJson ?: old.lineasDelTicketJson,
    )

/** «Sí se cobró» y «se da por cobrado» (D2 v7): la reserva pasa a la cola con su MISMA llave. */
const val EN_VUELO_A_PENDIENTE_SQL =
    "UPDATE pending_payments SET syncStatus = 'PENDING' WHERE id = :id AND syncStatus = 'EN_VUELO' AND q15CaptureJson IS NOT NULL AND q15Reason IS NULL"

/**
 * D2 v7: el apartado que la cajera descartó con una acción sobre ESA venta. Ver [PendingPaymentDao.descartarReservaRechazada].
 * 🔴 Ronda 1 (I1): un apartado con motivo Q15 (un 2xx que no se pudo comprobar) es evidencia por revisar: no se borra, la misma
 * guarda que [EN_VUELO_A_PENDIENTE_SQL]. Al cancelar sólo se suelta y queda en la revisión Q15, como hoy.
 * 🔴 Ronda final (A5, espejo de iOS `esEfectivoPuro`): sólo EFECTIVO PURO. «Ya pagó de otra forma» (método manual o tipo del
 * catálogo) sigue como hoy: «Cancelar», mandar la venta a la tarjeta o volver a cobrarla con otra llave nunca lo borran.
 */
const val DESCARTAR_RESERVA_RECHAZADA_SQL =
    "DELETE FROM pending_payments WHERE id = :id AND syncStatus = 'EN_VUELO' AND q15CaptureJson IS NOT NULL AND q15Reason IS NULL " +
        "AND method = 'CASH' AND tenderTypeId IS NULL"

/** Ver [PendingPaymentDao.cobrosDeEfectivoVivos]: el mismo criterio que `PendingCashSales.todaviaPuedeSubir`, más efectivo puro. */
const val COBROS_DE_EFECTIVO_VIVOS_SQL =
    "SELECT * FROM pending_payments WHERE venueId = :venueId AND cashDrawerSessionId IN (:cajas) " +
        "AND method = 'CASH' AND tenderTypeId IS NULL AND (" +
        "(syncStatus IN ('PENDING', 'SYNCING') AND (q15Reason IS NULL OR q15Reason = 'PRICE_PROOF_PENDING')) OR " +
        "(syncStatus = 'EN_VUELO' AND q15Reason IS NULL)) " +
        "AND (createdAt > :despuesDe OR (createdAt = :despuesDe AND id > :despuesDeId)) ORDER BY createdAt, id LIMIT :tanda"

// Restore identity only; capture/body/money/retries remain untouched. No legacy release.
const val Q16_RECOVER_ORDER_SQL = """UPDATE pending_payments SET orderId=:recoveredOrderId,q15Reason=NULL
 WHERE id=:id AND venueId=:venueId AND orderId IS :originalOrderId
 AND syncStatus='PENDING' AND q15Reason='PRICE_PROOF_PENDING' AND q15CaptureJson IS NOT NULL"""

// Q16 (2.24.1): suelta la venta sin red que la 2.24.0 retuvo para siempre. Sólo quita la marca;
// llave, captura, cuerpo, reintentos y orderId (nulo) quedan igual. La siguiente vuelta la manda.
const val Q16_RELEASE_SALE_SQL = """UPDATE pending_payments SET q15Reason=NULL,q15DetectedAt=NULL
 WHERE id=:id AND venueId=:venueId AND orderId IS NULL
 AND syncStatus='PENDING' AND q15Reason='PRICE_PROOF_PENDING' AND q15CaptureJson IS NOT NULL"""

/** Sólo estado de reintento; la comparación íntegra y el contexto corresponden al wrapper transaccional. */
const val Q4_COURTESY_RETRY_SQL = """UPDATE pending_payments
SET syncStatus='PENDING',retryCount=0,lastError=NULL,lastRetryAt=NULL
WHERE venueId=:venueId AND id=:id AND syncStatus='FAILED' AND q15Reason IS NULL
  AND paymentType='ORDER' AND method='CASH' AND amountCents=0 AND tipCents=0
  AND tenderTypeId IS NULL AND tenderRevision IS NULL"""
