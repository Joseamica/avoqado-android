package com.avoqado.pos.kiosk.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Asistencia verificada (16-nov-2026): la alumna sin celular teclea su teléfono en el kiosco y cuenta «con teléfono»
 * (decisión «C» del founder). Si no hay red, la llegada se GUARDA antes de tocarla en `pending_reservation_action` y el
 * mismo [com.avoqado.pos.reservations.data.ReservationActionsRetrier] la manda después.
 *
 * - [Saved.occurredAt]: la hora del SERVIDOR estimada por el aparato (último `Date` que contestó + reloj monótono). Sin esa
 *   ancla NO se guarda nada (Codex r1 #4): la hora del aparato podía caer en la clase de ayer.
 * - [Saved.opId]: el id de esta llegada. El servidor lo deja en la historia de la reserva; un reintento con el mismo id
 *   nunca vuelve a marcar una llegada que una gerente deshizo después (Codex r1 #5).
 *
 * No entra al enum `ReservationAction`: es una acción de la COLA, como [KioskTap].
 */
object KioskPhone {
    const val ACTION = "KIOSK_PHONE_CHECK_IN"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Saved(val venueId: String, val phone: String, val occurredAt: String, val opId: String)

    fun payload(saved: Saved): String = json.encodeToString(Saved.serializer(), saved)

    fun parse(payloadJson: String?): Saved? =
        payloadJson
            ?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
            ?.takeIf { it.venueId.isNotBlank() && it.phone.isNotBlank() && it.occurredAt.isNotBlank() && it.opId.length >= 8 }

    /** Lo mismo que [KioskTap.outcome], más los dos errores propios del teléfono. */
    fun outcome(result: Result<*>): KioskTap.Outcome {
        val error = result.exceptionOrNull() ?: return KioskTap.Outcome.Sent
        return when (error) {
            is IOException -> KioskTap.Outcome.Kept
            is KioskCheckInApi.TooManyAttempts -> KioskTap.Outcome.Kept
            is KioskCheckInApi.NotFound -> KioskTap.Outcome.Refused(NO_ENCONTRADA)
            else -> KioskTap.outcome(Result.failure<Unit>(error))
        }
    }

    const val NO_ENCONTRADA = "No encontramos una reservación de ese teléfono para esa hora. Revísala en Reservaciones."
}
