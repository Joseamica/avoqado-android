package com.avoqado.pos.kiosk.data

import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant

/**
 * Una llegada tocada en el kiosco, guardada en `pending_reservation_action` ANTES de cualquier red (Modo Autoservicio
 * 0.e, founder 27-oct: «como hoy, con ✓»).
 *
 * 🔴 Lo que guarda no depende de ningún reloj del aparato (Codex 0.e r1 #1, r2 R1-1): el cierre de la ventana que vio la
 * persona, en hora del SERVIDOR y tal cual vino, y cuánto faltaba para él al tocar (contado con el reloj monótono desde
 * que se PIDIÓ la lista). Si corrigen la hora del aparato o la app se reinicia, la fila sigue diciendo lo mismo. Y lleva
 * su sucursal: el reintentador la manda allá, aunque la sesión ande en otra (Codex 0.e r1 #6).
 *
 * No entra al enum `ReservationAction`: es una acción de la COLA, no un estado de la reserva.
 */
object KioskTap {
    const val ACTION = "KIOSK_CHECK_IN"

    private val json = Json { ignoreUnknownKeys = true }

    /** [name]: el nombre corto que vio la persona en la lista (D4: para decir en la franja quién llegó). Opcional. */
    @Serializable
    data class Saved(val venueId: String, val closesAt: String, val msBeforeClose: Long, val name: String? = null) {
        /** La hora del toque en hora del SERVIDOR (lo mismo que reconstruye él): el cierre menos lo que faltaba. */
        fun tappedAt(): String? = runCatching { Instant.parse(closesAt).minusMillis(msBeforeClose).toString() }.getOrNull()
    }

    /** `{"venueId":"…","closesAt":"…","msBeforeClose":N}` (+ `"name"` si se sabe) */
    fun payload(venueId: String, closesAt: String, msBeforeClose: Long, name: String? = null): String =
        json.encodeToString(Saved.serializer(), Saved(venueId, closesAt, msBeforeClose, name))

    // MARK: - D4 · el aviso de la franja

    /**
     * El SERVIDOR corrigió solo un «No asistió» con esta llegada y dejó algo pendiente. Vive en la MISMA tabla, ya en
     * cuarentena (`attemptCount = MAX_ATTEMPTS`): así sale en «Operaciones por revisar» y el reintentador nunca lo manda.
     * No es un valor de `ReservationAction` (rompería los `when` exhaustivos).
     */
    const val CORRECTION = "KIOSK_NO_SHOW_CORRECTION"

    /**
     * La `correction` de la respuesta del kiosco (`{id, pending}`), más quién, a qué hora y de qué sucursal (la de la
     * llegada: «Marcar resuelta» va contra ELLA aunque la sesión ande en otra; un aviso viejo sin ella usa la de la sesión).
     */
    @Serializable
    data class Correction(
        val id: String,
        val pending: List<String> = emptyList(),
        val name: String? = null,
        val tappedAt: String? = null,
        val venueId: String? = null,
    )

    fun correctionPayload(c: Correction): String = json.encodeToString(Correction.serializer(), c)

    fun parseCorrection(payloadJson: String?): Correction? =
        payloadJson?.let { runCatching { json.decodeFromString(Correction.serializer(), it) }.getOrNull() }

    /**
     * La sucursal de una fila del kiosco en la franja (la llegada rechazada o el aviso de la corrección): TODO lo que nace
     * de ella va contra ésta, aunque la sesión ande en otra. null (un aviso viejo que no la guardó) = la de la sesión.
     */
    fun venueOf(payloadJson: String?): String? =
        payloadJson
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject["venueId"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /** null si falta algo o `msBeforeClose ≤ 0` (la ventana ya había cerrado: el servidor tampoco lo acepta). */
    fun parse(payloadJson: String?): Saved? =
        payloadJson
            ?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
            ?.takeIf { it.msBeforeClose > 0 && it.venueId.isNotBlank() && it.closesAt.isNotBlank() }

    /**
     * Llegadas que todavía se van a mandar con ESTA sucursal activa. Las de otra sucursal no cuentan: frenar por ellas el
     * cambio de sucursal o el cierre de sesión no las manda (sólo salen con su sucursal) y dejaría la caja sin salida;
     * esperan, intactas, a que vuelva su sucursal.
     */
    fun pendingFor(rows: List<PendingReservationActionEntity>, venueId: String?, maxAttempts: Int): Int =
        rows.count { it.action == ACTION && it.attemptCount < maxAttempts && parse(it.payloadJson)?.venueId == venueId }

    /** Qué pasó con un envío. Igual para el toque en vivo y para el reenvío: un solo criterio. */
    sealed interface Outcome {
        /** El servidor la registró (o ya estaba registrada): la fila se borra. */
        data object Sent : Outcome

        /** Sin red o el servidor falló (5xx, 408, 429): la fila SE QUEDA y se manda sola después. */
        data object Kept : Outcome

        /** El servidor dijo que no (4xx salvo 408/429): va a cuarentena con su texto. */
        data class Refused(val message: String?) : Outcome
    }

    fun outcome(result: Result<Unit>): Outcome {
        val error = result.exceptionOrNull() ?: return Outcome.Sent
        return when {
            error is IOException -> Outcome.Kept
            // 5xx, 408 (Request Timeout) y 429 (Too Many Requests) son pasajeros: se queda, gastando intento.
            error is KioskCheckInApi.Rejected && (error.status >= 500 || error.status == 408 || error.status == 429) -> Outcome.Kept
            error is KioskCheckInApi.Rejected || error is KioskCheckInApi.OutsideWindow -> Outcome.Refused(error.message)
            // Algo que no es respuesta del servidor ni falla de red: se queda (gastando intento en el reintentador).
            else -> Outcome.Kept
        }
    }
}
