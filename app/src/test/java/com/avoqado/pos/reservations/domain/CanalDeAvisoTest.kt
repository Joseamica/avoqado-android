package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.CustomerLite
import com.avoqado.pos.reservations.data.model.RescheduleNotificationChannel
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** nafc2b18: la opción «Notificación push» avisa por WhatsApp o correo y debe decirlo; sin contacto, «Sin notificación». */
class CanalDeAvisoTest {

    private fun cita(customer: CustomerLite? = null, guestPhone: String? = null, guestEmail: String? = null) = Reservation(
        id = "r1", venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = ReservationStatus.CONFIRMED, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-11-12T21:00:00.000Z", endsAt = "2026-11-12T22:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "...",
        customer = customer, guestPhone = guestPhone, guestEmail = guestEmail,
    )

    @Test
    fun `ninguna opcion dice push y la automatica dice WhatsApp o correo`() {
        assertFalse(CanalDeAviso.OPCIONES.any { it.texto.contains("push", ignoreCase = true) })
        assertEquals("WhatsApp o correo (el que tenga)", CanalDeAviso.opcion(RescheduleNotificationChannel.push).texto)
        assertEquals(
            listOf(RescheduleNotificationChannel.push, RescheduleNotificationChannel.whatsapp, RescheduleNotificationChannel.email, RescheduleNotificationChannel.none),
            CanalDeAviso.OPCIONES.map { it.valor },
        )
    }

    @Test
    fun `con telefono o correo viene marcada la automatica`() {
        assertEquals(RescheduleNotificationChannel.push, CanalDeAviso.porOmision(cita(guestPhone = "+5215551234567")))
        assertEquals(RescheduleNotificationChannel.push, CanalDeAviso.porOmision(cita(guestEmail = "a@b.com")))
        assertEquals(
            RescheduleNotificationChannel.push,
            CanalDeAviso.porOmision(cita(customer = CustomerLite(id = "c", email = "a@b.com"))),
        )
    }

    @Test
    fun `sin telefono ni correo viene marcada Sin notificacion`() {
        assertEquals(RescheduleNotificationChannel.none, CanalDeAviso.porOmision(cita()))
        assertEquals(RescheduleNotificationChannel.none, CanalDeAviso.porOmision(cita(guestPhone = " ", guestEmail = "")))
    }
}
