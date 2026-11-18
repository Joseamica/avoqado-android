package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.RescheduleNotificationChannel
import com.avoqado.pos.reservations.data.model.Reservation

/**
 * Cómo se le avisa al cliente que su cita cambió (nafc2b18, 17-nov-2026).
 *
 * El cliente no tiene una app con avisos: «Notificación push» nunca le llegaba a nadie. Desde el SERVIDOR #461 (ya en
 * producción) el valor `push` se entrega por WhatsApp si hay teléfono y, si no hay o WhatsApp falla, por correo. Por eso se
 * sigue mandando `push`, pero la opción dice lo que hace. Las apps viejas que aún dicen «push» también avisan.
 */
object CanalDeAviso {
    data class Opcion(val valor: RescheduleNotificationChannel, val texto: String, val ayuda: String? = null)

    val OPCIONES: List<Opcion> = listOf(
        Opcion(RescheduleNotificationChannel.push, "WhatsApp o correo (el que tenga)", "Si no tiene WhatsApp o no le llega, se le manda por correo."),
        Opcion(RescheduleNotificationChannel.whatsapp, "Sólo WhatsApp"),
        Opcion(RescheduleNotificationChannel.email, "Sólo correo"),
        Opcion(RescheduleNotificationChannel.none, "Sin notificación"),
    )

    /** Lo que viene marcado: el aviso que sí le llega; sin teléfono ni correo, «Sin notificación» (como el DASHBOARD). */
    fun porOmision(reservation: Reservation): RescheduleNotificationChannel {
        val tieneTelefono = !(reservation.customer?.phone ?: reservation.guestPhone).isNullOrBlank()
        val tieneCorreo = !(reservation.customer?.email ?: reservation.guestEmail).isNullOrBlank()
        return if (tieneTelefono || tieneCorreo) RescheduleNotificationChannel.push else RescheduleNotificationChannel.none
    }

    fun opcion(valor: RescheduleNotificationChannel): Opcion = OPCIONES.firstOrNull { it.valor == valor } ?: OPCIONES.first()
}
