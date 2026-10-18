package com.avoqado.pos.core.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
        if (old?.syncStatus == "SYNCED") return
        insertRaw(if (old == null) payment else payment.copy(
            q15CaptureJson = old.q15CaptureJson ?: payment.q15CaptureJson,
            capturedOrderRef = old.capturedOrderRef ?: payment.capturedOrderRef,
            q15InitialResultJson = old.q15InitialResultJson, q15ResultJson = old.q15ResultJson,
            q15Reason = old.q15Reason, q15DetectedAt = old.q15DetectedAt,
            q15ReviewedAt = old.q15ReviewedAt, q15ReviewedBy = old.q15ReviewedBy, q15ReviewRole = old.q15ReviewRole))
    }
    @Query("SELECT * FROM pending_payments WHERE id=:id")
    suspend fun findByIdQ15(id: String): PendingPaymentEntity?
    @Query("SELECT * FROM pending_payments WHERE venueId=:venueId AND id=:id")
    suspend fun findForVenue(venueId: String, id: String): PendingPaymentEntity?
    @Query(Q15_PAYMENT_RESULT_SQL)
    suspend fun registrarResultadoQ15(venueId: String, id: String, aplicado: Boolean, motivo: String?, resultadoJson: String?, ahora: Long): Int
    @Query(Q15_PAYMENT_HOLD_SQL)
    suspend fun retenerPagoQ15(venueId: String, id: String, motivo: String, ahora: Long): Int
    @Query(Q15_PAYMENT_REVIEW_SQL)
    suspend fun revisarPagoQ15(venueId: String, id: String, actor: String, rol: String, ahora: Long): Int
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

    @Query("SELECT * FROM pending_payments WHERE syncStatus = 'FAILED' AND q15Reason IS NULL ORDER BY createdAt DESC")
    suspend fun getFailedPayments(): List<PendingPaymentEntity>

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
    @Query("UPDATE pending_payments SET syncStatus = 'PENDING' WHERE id = :id AND syncStatus = 'EN_VUELO' AND q15CaptureJson IS NOT NULL AND q15Reason IS NULL")
    suspend fun enVueloAPendiente(id: String): Int

    @Query("SELECT * FROM pending_payments WHERE venueId = :venueId AND syncStatus = 'EN_VUELO' ORDER BY createdAt ASC")
    suspend fun enVueloDelVenue(venueId: String): List<PendingPaymentEntity>

    @Query("SELECT * FROM pending_payments WHERE syncStatus = 'EN_VUELO' ORDER BY createdAt ASC")
    fun observarEnVuelo(): Flow<List<PendingPaymentEntity>>

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus = 'EN_VUELO'")
    suspend fun enVueloCount(): Int

    /** El estado de UN cobro encolado, en vivo. Lo mira la pantalla de resultado para no mentir. */
    @Query("SELECT syncStatus FROM pending_payments WHERE id = :id")
    fun observarEstado(id: String): Flow<String?>

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_payments WHERE syncStatus = 'FAILED' AND q15Reason IS NULL")
    fun getFailedCount(): Flow<Int>

    @Query("DELETE FROM pending_payments WHERE syncStatus='SYNCED' AND q15CaptureJson IS NULL AND q15Reason IS NULL")
    suspend fun deleteAll()

    @Query("DELETE FROM pending_payments WHERE venueId = :venueId AND syncStatus='SYNCED' AND q15CaptureJson IS NULL AND q15Reason IS NULL")
    suspend fun deleteForVenue(venueId: String)
}

// Restore identity only; capture/body/money/retries remain untouched. No legacy release.
const val Q16_RECOVER_ORDER_SQL = """UPDATE pending_payments SET orderId=:recoveredOrderId,q15Reason=NULL
 WHERE id=:id AND venueId=:venueId AND orderId IS :originalOrderId
 AND syncStatus='PENDING' AND q15Reason='PRICE_PROOF_PENDING' AND q15CaptureJson IS NOT NULL"""

// Q16 (2.24.1): suelta la venta sin red que la 2.24.0 retuvo para siempre. Sólo quita la marca;
// llave, captura, cuerpo, reintentos y orderId (nulo) quedan igual. La siguiente vuelta la manda.
const val Q16_RELEASE_SALE_SQL = """UPDATE pending_payments SET q15Reason=NULL,q15DetectedAt=NULL
 WHERE id=:id AND venueId=:venueId AND orderId IS NULL
 AND syncStatus='PENDING' AND q15Reason='PRICE_PROOF_PENDING' AND q15CaptureJson IS NOT NULL"""
