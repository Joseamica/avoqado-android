package com.avoqado.pos.kiosk.data

import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

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

    @Serializable
    data class Saved(val venueId: String, val closesAt: String, val msBeforeClose: Long)

    /** `{"venueId":"…","closesAt":"…","msBeforeClose":N}` */
    fun payload(venueId: String, closesAt: String, msBeforeClose: Long): String =
        json.encodeToString(Saved.serializer(), Saved(venueId, closesAt, msBeforeClose))

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
