package com.avoqado.pos.core.data.local.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import com.avoqado.pos.payment.data.ConciliacionDelCobro

internal object RevisionCanceladoCero {
    const val MOTIVO = "CANCELLED_ZERO_PAYMENT"
    const val AVISO = "Intento cancelado de $0. La cortesía no quedó registrada; revisa el inventario."
    private val gerentes = setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")
    fun esGerente(role: String) = role.uppercase() in gerentes
    private fun objeto(raw: String?) = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun JsonObject.texto(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.fecha(key: String) = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
    fun externalId(row: PendingPaymentEntity): String? {
        if (row.paymentType != "ORDER" || row.amountCents != 0 || row.tipCents != 0 ||
            row.id.isBlank() || row.venueId.isBlank() || row.orderId?.isBlank() == true) return null
        val body = objeto(row.orderRequestJson) ?: return null
        if (row.syncStatus == "FAILED") return body.texto("externalId")
        if (row.syncStatus != "EN_VUELO" ||
            (row.q15Reason != ConciliacionDelCobro.RESULTADO_NO_COMPROBADO && row.q15Reason != MOTIVO) ||
            row.method != "CASH" || row.tenderTypeId != null || row.tenderRevision != null) return null
        val capture = ConciliacionDelCobro.leerCaptura(row.q15CaptureJson, row.venueId, row.id) ?: return null
        if (objeto(ConciliacionDelCobro.guardarCaptura(capture)) != objeto(row.q15CaptureJson) ||
            capture.amountCents != 0 || capture.tipCents != 0 || capture.method != "CASH" ||
            capture.tenderTypeId != null || capture.tenderRevision != null || capture.splitType != row.splitType ||
            capture.customerId != row.customerId || capture.cashTenderedCents != row.cashTenderedCents ||
            capture.changeCents != row.changeCents || (capture.orderId != null && capture.orderId != row.orderId)) return null
        val captured = objeto(capture.orderRequestJson) ?: return null
        val external = captured.texto("externalId") ?: return null
        if (body == captured) return external
        // Old re-reservation rewrote these two fields; only server proof of the original capture can review it.
        if (row.orderId == null || capture.orderId != null || row.capturedOrderRef != row.id || external != row.id ||
            body.texto("externalId") != "offline-order:${row.id}" || captured.fecha("createdAtLocal") == null ||
            body.fecha("createdAtLocal") != row.createdAt ||
            body.filterKeys { it != "externalId" && it != "createdAtLocal" } !=
            captured.filterKeys { it != "externalId" && it != "createdAtLocal" }) return null
        return external
    }
    private fun valida(row: PendingPaymentEntity, proof: JsonObject): Boolean {
        val external = externalId(row) ?: return false
        val orderId = proof.texto("orderId") ?: return false
        val version = proof["version"] as? JsonPrimitive ?: return false
        fun falso(key: String) = (proof[key] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } == true
        return !version.isString && version.intOrNull == 1 && proof.texto("venueId") == row.venueId &&
            (row.orderId == null || orderId == row.orderId) && proof.texto("externalId") == external &&
            proof.texto("orderStatus") == "CANCELLED" && falso("hasPayments") && falso("hasTerminalRequests") &&
            proof.texto("verifiedAt")?.let { runCatching { java.time.Instant.parse(it) }.isSuccess } == true
    }
    fun evidencia(row: PendingPaymentEntity, recoveryJson: String): String? {
        if (row.q15ReviewedAt != null || (row.syncStatus == "EN_VUELO" && row.q15Reason != ConciliacionDelCobro.RESULTADO_NO_COMPROBADO)) return null
        val external = externalId(row) ?: return null
        val data = objeto(recoveryJson)?.get("data") as? JsonObject ?: return null
        val record = (data["orders"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
        val orderId = record.texto("orderId") ?: return null
        if (record.texto("externalId") != external) return null
        val proof = record["cancelledPaymentEvidence"] as? JsonObject ?: return null
        if (proof.texto("orderId") != orderId) return null
        return if (valida(row, proof)) JsonObject(mapOf("cancelledPaymentEvidence" to proof)).toString() else null
    }
    /** Canonical identity from strict evidence, without filling the durable original NULL. */
    fun ordenAcreditada(row: PendingPaymentEntity, resultadoJson: String?): String? {
        val proof = objeto(resultadoJson)?.get("cancelledPaymentEvidence") as? JsonObject ?: return null
        return proof.texto("orderId")?.takeIf { valida(row, proof) }
    }
    fun revisado(row: PendingPaymentEntity): Boolean = row.q15Reason == MOTIVO &&
        row.q15ReviewedAt?.let { it > 0 } == true && !row.q15ReviewedBy.isNullOrBlank() &&
        row.q15ReviewRole?.let(::esGerente) == true &&
        ordenAcreditada(row, row.q15ResultJson) != null

    /** A retained FAILED with no pending Q15 card must remain visible in quarantine. */
    fun mostrarFallido(row: PendingPaymentEntity) = !revisado(row) && (row.q15Reason == null || row.q15ReviewedAt != null)
}

const val Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL = """UPDATE pending_payments SET q15ResultJson=:resultadoJson,
 q15Reason='CANCELLED_ZERO_PAYMENT',q15DetectedAt=COALESCE(q15DetectedAt,:ahora),q15ReviewedAt=:ahora,q15ReviewedBy=:actor,q15ReviewRole=:rol
 WHERE venueId=:venueId AND id=:id AND syncStatus=:originalSyncStatus AND syncStatus IN ('FAILED','EN_VUELO') AND paymentType='ORDER' AND amountCents=0 AND tipCents=0
 AND orderId IS :originalOrderId AND orderRequestJson IS :originalOrderRequestJson AND q15ResultJson IS :originalResultJson
 AND q15Reason IS :originalReason AND q15CaptureJson IS :originalCaptureJson AND createdAt=:originalCreatedAt AND capturedOrderRef IS :originalCapturedOrderRef
 AND (syncStatus='FAILED' OR (q15Reason='RESULTADO_NO_COMPROBADO' AND method='CASH' AND tenderTypeId IS NULL AND tenderRevision IS NULL
 AND splitType IS :originalSplitType AND customerId IS :originalCustomerId AND cashTenderedCents IS :originalCashTenderedCents AND changeCents IS :originalChangeCents))
 AND q15ReviewedAt IS NULL AND TRIM(:actor)!='' AND UPPER(:rol) IN ('MANAGER','ADMIN','OWNER','SUPERADMIN')"""

// Only a candidate: every reader must validate its original identity and proof in Kotlin, without SQLite JSON1.
internal const val Q15_CANCELLED_CANDIDATE_SQL = "COALESCE(id!='' AND syncStatus IN ('FAILED','EN_VUELO') AND paymentType='ORDER' AND amountCents=0 AND tipCents=0 AND q15Reason='CANCELLED_ZERO_PAYMENT' AND q15ReviewedAt IS NOT NULL AND q15ReviewedBy IS NOT NULL AND q15ReviewRole IS NOT NULL AND q15ResultJson IS NOT NULL,0)"
internal const val Q15_CANCELLED_PAGE_SQL = "SELECT * FROM pending_payments WHERE venueId=:venueId AND $Q15_CANCELLED_CANDIDATE_SQL AND id>:cursor ORDER BY id LIMIT 50"
internal const val Q15_CANCELLED_STATE_PAGE_SQL = "SELECT * FROM pending_payments WHERE syncStatus=:estado AND $Q15_CANCELLED_CANDIDATE_SQL AND id>:cursor ORDER BY id LIMIT 50"
internal const val Q15_EN_VUELO_COUNT_SQL = "SELECT COUNT(*) FROM pending_payments WHERE syncStatus='EN_VUELO' AND NOT ($Q15_CANCELLED_CANDIDATE_SQL)"
internal const val Q15_FAILED_ORDINARY_SQL = "SELECT * FROM pending_payments WHERE syncStatus='FAILED' AND NOT ($Q15_CANCELLED_CANDIDATE_SQL) ORDER BY createdAt DESC"
internal const val Q15_FAILED_ORDINARY_COUNT_SQL = "SELECT COUNT(*) FROM pending_payments WHERE syncStatus='FAILED' AND (q15Reason IS NULL OR q15ReviewedAt IS NOT NULL) AND NOT ($Q15_CANCELLED_CANDIDATE_SQL)"

/** Only original nonblank STRING IDs; numeric JSON, amounts and dates never establish identity. */
fun referenciaOriginalDelCobro(payloadJson: String): String? {
    val payload = runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
    fun ref(key: String): String? = (payload[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    return ref("localOrderId") ?: ref("orderId")
}

/** SQL de producción; la evidencia económica vive en sus filas originales. */
const val Q15_PAYMENT_RESULT_SQL = "UPDATE pending_payments SET syncStatus=CASE WHEN syncStatus='SYNCED' OR :aplicado=1 THEN 'SYNCED' ELSE 'PENDING' END,q15InitialResultJson=COALESCE(q15InitialResultJson,:resultadoJson),q15ResultJson=COALESCE(:resultadoJson,q15ResultJson),q15DetectedAt=CASE WHEN q15Reason IS NOT NULL OR :motivo IS NOT NULL THEN COALESCE(q15DetectedAt,:ahora) ELSE q15DetectedAt END,q15Reason=COALESCE(q15Reason,:motivo) WHERE venueId=:venueId AND id=:id"
const val Q15_INTENT_RESULT_SQL = "UPDATE pos_sync_intents SET q15InitialResultJson=COALESCE(q15InitialResultJson,:resultadoJson),q15ResultJson=COALESCE(:resultadoJson,q15ResultJson),q15DetectedAt=CASE WHEN q15Reason IS NOT NULL OR :motivo IS NOT NULL THEN COALESCE(q15DetectedAt,:ahora) ELSE q15DetectedAt END,q15Reason=COALESCE(q15Reason,:motivo) WHERE venue_id=:venueId AND id=:id"
const val Q15_HISTORY_PAGE_SQL = "SELECT * FROM (SELECT 'PAYMENT' AS origen,id,venueId,syncStatus AS estado,q15CaptureJson,q15InitialResultJson,q15ResultJson,q15Reason,COALESCE(q15DetectedAt,0) AS q15DetectedAt,q15ReviewedAt,q15ReviewedBy,q15ReviewRole,capturedOrderRef FROM pending_payments WHERE venueId=:venueId AND q15Reason IS NOT NULL UNION ALL SELECT 'INTENT' AS origen,id,venue_id AS venueId,status AS estado,q15CaptureJson,q15InitialResultJson,q15ResultJson,q15Reason,COALESCE(q15DetectedAt,0) AS q15DetectedAt,q15ReviewedAt,q15ReviewedBy,q15ReviewRole,capturedOrderRef FROM pos_sync_intents WHERE venue_id=:venueId AND q15Reason IS NOT NULL) WHERE ((:revisadas=1 AND q15ReviewedAt IS NOT NULL) OR (:revisadas=0 AND q15ReviewedAt IS NULL)) AND (:busqueda='' OR instr(id,:busqueda)>0 OR instr(COALESCE(capturedOrderRef,''),:busqueda)>0) AND (:cursorFecha IS NULL OR q15DetectedAt<:cursorFecha OR (q15DetectedAt=:cursorFecha AND (origen>:cursorOrigen OR (origen=:cursorOrigen AND id>:cursorId)))) ORDER BY q15DetectedAt DESC,origen ASC,id ASC LIMIT 50"
const val Q15_HISTORY_COUNT_SQL = "SELECT COUNT(*) AS total FROM (SELECT 'PAYMENT' AS origen,id,venueId,syncStatus AS estado,q15CaptureJson,q15InitialResultJson,q15ResultJson,q15Reason,COALESCE(q15DetectedAt,0) AS q15DetectedAt,q15ReviewedAt,q15ReviewedBy,q15ReviewRole,capturedOrderRef FROM pending_payments WHERE venueId=:venueId AND q15Reason IS NOT NULL UNION ALL SELECT 'INTENT' AS origen,id,venue_id AS venueId,status AS estado,q15CaptureJson,q15InitialResultJson,q15ResultJson,q15Reason,COALESCE(q15DetectedAt,0) AS q15DetectedAt,q15ReviewedAt,q15ReviewedBy,q15ReviewRole,capturedOrderRef FROM pos_sync_intents WHERE venue_id=:venueId AND q15Reason IS NOT NULL) WHERE ((:revisadas=1 AND q15ReviewedAt IS NOT NULL) OR (:revisadas=0 AND q15ReviewedAt IS NULL)) AND (:busqueda='' OR instr(id,:busqueda)>0 OR instr(COALESCE(capturedOrderRef,''),:busqueda)>0)"
const val Q15_PAYMENT_REVIEW_SQL = "UPDATE pending_payments SET q15ReviewedAt=:ahora,q15ReviewedBy=:actor,q15ReviewRole=:rol WHERE venueId=:venueId AND id=:id AND syncStatus='SYNCED' AND q15Reason IS NOT NULL AND q15ReviewedAt IS NULL AND :actor!='' AND UPPER(:rol) IN ('MANAGER','ADMIN','OWNER','SUPERADMIN') AND q15Reason NOT IN ('PRICE_PROOF_PENDING','CAPTURA_SIN_EVIDENCIA')"
const val Q15_INTENT_REVIEW_SQL = "UPDATE pos_sync_intents SET q15ReviewedAt=:ahora,q15ReviewedBy=:actor,q15ReviewRole=:rol WHERE venue_id=:venueId AND id=:id AND status='ACKED' AND q15Reason IS NOT NULL AND q15ReviewedAt IS NULL AND :actor!='' AND UPPER(:rol) IN ('MANAGER','ADMIN','OWNER','SUPERADMIN') AND q15Reason NOT IN ('PRICE_PROOF_PENDING','CAPTURA_SIN_EVIDENCIA')"
const val Q15_PAYMENT_HOLD_SQL = "UPDATE pending_payments SET q15Reason=COALESCE(q15Reason,:motivo),q15DetectedAt=COALESCE(q15DetectedAt,:ahora) WHERE venueId=:venueId AND id=:id AND syncStatus!='SYNCED'"
/** Q16 (19-oct, como `Q16_RELEASE_SALE_SQL` de mostrador): quita SÓLO la espera de precio de una ronda/apertura que el
 *  servidor dice no tener; la admisión de precio vuelve a decidir con la regla vigente antes de mandarla. */
const val Q16_INTENT_RELEASE_SQL = "UPDATE pos_sync_intents SET q15Reason=NULL,q15DetectedAt=NULL WHERE venue_id=:venueId AND id=:id AND q15Reason='PRICE_PROOF_PENDING' AND type IN ('OPEN_TABLE','ADD_ITEMS') AND status IN ('PENDING','HELD')"
const val Q15_INTENT_HOLD_SQL = "UPDATE pos_sync_intents SET q15Reason=COALESCE(q15Reason,:motivo),q15DetectedAt=COALESCE(q15DetectedAt,:ahora) WHERE venue_id=:venueId AND id=:id AND status!='ACKED'"
const val Q15_PAYMENT_TTL_SQL = "DELETE FROM pending_payments WHERE syncStatus='SYNCED' AND createdAt<:olderThan AND q15CaptureJson IS NULL AND q15Reason IS NULL"
const val Q15_INTENT_TTL_SQL = "DELETE FROM pos_sync_intents WHERE status='ACKED' AND created_at<:olderThan AND q15CaptureJson IS NULL AND q15Reason IS NULL"
const val Q15_PAYMENT_DELETE_FAILED_SQL = "DELETE FROM pending_payments WHERE id=:id AND 0"
const val Q15_INTENT_DISMISS_SQL = "DELETE FROM pos_sync_intents WHERE id=:id AND type!='PAY_CASH' AND q15CaptureJson IS NULL AND q15Reason IS NULL"
const val Q15_CAPTURED_ORDER_EXISTS_SQL = "SELECT (EXISTS(SELECT 1 FROM pending_payments WHERE venueId=:venueId AND capturedOrderRef=:orderRef) OR EXISTS(SELECT 1 FROM pos_sync_intents WHERE venue_id=:venueId AND capturedOrderRef=:orderRef)) AS hayCaptura"
val Q15_MIGRATION_SQL = listOf(
    "ALTER TABLE pending_payments ADD COLUMN q15CaptureJson TEXT",
    "ALTER TABLE pending_payments ADD COLUMN q15InitialResultJson TEXT",
    "ALTER TABLE pending_payments ADD COLUMN q15ResultJson TEXT",
    "ALTER TABLE pending_payments ADD COLUMN q15Reason TEXT",
    "ALTER TABLE pending_payments ADD COLUMN q15DetectedAt INTEGER",
    "ALTER TABLE pending_payments ADD COLUMN q15ReviewedAt INTEGER",
    "ALTER TABLE pending_payments ADD COLUMN q15ReviewedBy TEXT",
    "ALTER TABLE pending_payments ADD COLUMN q15ReviewRole TEXT",
    "ALTER TABLE pending_payments ADD COLUMN capturedOrderRef TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15CaptureJson TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15InitialResultJson TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15ResultJson TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15Reason TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15DetectedAt INTEGER",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15ReviewedAt INTEGER",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15ReviewedBy TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN q15ReviewRole TEXT",
    "ALTER TABLE pos_sync_intents ADD COLUMN capturedOrderRef TEXT",
    "CREATE INDEX index_pending_payments_venueId_q15Reason_q15ReviewedAt_q15DetectedAt_id ON pending_payments (venueId,q15Reason,q15ReviewedAt,q15DetectedAt,id)",
    "CREATE INDEX index_pending_payments_venueId_capturedOrderRef ON pending_payments (venueId,capturedOrderRef)",
    "CREATE INDEX index_pos_sync_intents_venue_id_q15Reason_q15ReviewedAt_q15DetectedAt_id ON pos_sync_intents (venue_id,q15Reason,q15ReviewedAt,q15DetectedAt,id)",
    "CREATE INDEX index_pos_sync_intents_venue_id_capturedOrderRef ON pos_sync_intents (venue_id,capturedOrderRef)",
)
data class RevisionDelCobroRecord(
    val origen: String, val id: String, val venueId: String, val estado: String,
    val q15CaptureJson: String?, val q15InitialResultJson: String?, val q15ResultJson: String?,
    val q15Reason: String, val q15DetectedAt: Long, val q15ReviewedAt: Long?,
    val q15ReviewedBy: String?, val q15ReviewRole: String?, val capturedOrderRef: String?,
)
