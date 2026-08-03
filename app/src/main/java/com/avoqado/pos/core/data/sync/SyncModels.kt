package com.avoqado.pos.core.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Contrato del replay offline — espejo EXACTO por nombre del server
 * (avoqado-server src/services/mobile/sync.mobile.service.ts, Corte D).
 */

/** Tipos de intent soportados. Se replican por nombre EXACTO. */
object SyncIntentTypes {
    const val OPEN_TABLE = "OPEN_TABLE"
    const val ADD_ITEMS = "ADD_ITEMS"
    const val PAY_CASH = "PAY_CASH"
}

/** Códigos de rechazo estructurados del reducer. */
object SyncErrorCodes {
    const val UNKNOWN_INTENT_TYPE = "UNKNOWN_INTENT_TYPE"
    const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
    const val FEATURE_LOCKED = "FEATURE_LOCKED"
    const val TABLE_OWNED_BY_OTHER = "TABLE_OWNED_BY_OTHER"
    const val BUSINESS_RULE = "BUSINESS_RULE"
}

@Serializable
data class SyncIntentWire(
    /** UUID generado en el dispositivo = idempotencyKey del server. */
    val id: String,
    /** Secuencia monotónica por dispositivo (orden de replay). */
    val seq: Long,
    val type: String,
    val payload: JsonObject,
    /** Actor original; null únicamente para filas creadas por clientes viejos. */
    val staffId: String? = null,
    /** Reloj local al crear el intent (informativo — NUNCA ordena). */
    val createdAtLocal: Long,
)

@Serializable
data class SyncIntentsRequest(
    val deviceId: String,
    val intents: List<SyncIntentWire>,
)

@Serializable
data class SyncAck(
    val id: String,
    val status: String, // ACKED | REJECTED | RETRY
    val errorCode: String? = null,
    val message: String? = null,
    val result: JsonObject? = null,
) {
    val isAcked: Boolean get() = status == "ACKED"

    /** Condición TRANSITORIA (conflicto de versión, etc.): el intent se queda
     *  PENDING y se reintenta — NO es un rechazo terminal, NO va a cuarentena. */
    val isRetry: Boolean get() = status == "RETRY"
}

@Serializable
data class SyncIntentsResponse(
    val success: Boolean = true,
    val data: List<SyncAck> = emptyList(),
)
