package com.avoqado.pos.core.data.local.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
