package com.avoqado.pos.kiosk

import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * La fila de una llegada del kiosco (Modo Autoservicio 0.e): qué guarda y cómo se clasifica su envío.
 *
 * 🔴 Lo que guarda no depende de ningún reloj del aparato: el cierre que vio la persona (hora del SERVIDOR) y cuánto
 * faltaba para él. Y la clasificación decide si una llegada se queda, se borra o va a cuarentena.
 */
class KioskTapTest {

    private val C = "2026-10-28T19:20:00.000Z"

    @Test
    fun `payload y parse van y vienen, y lo invalido es null`() {
        assertEquals(KioskTap.Saved("v1", C, 900_000), KioskTap.parse(KioskTap.payload("v1", C, 900_000)))
        assertEquals("""{"venueId":"v1","closesAt":"$C","msBeforeClose":900000}""", KioskTap.payload("v1", C, 900_000))
        assertNull(KioskTap.parse(null))
        assertNull(KioskTap.parse("{}"))
        assertNull(KioskTap.parse("no es json"))
        assertNull(KioskTap.parse("""{"venueId":"v1","closesAt":"$C","msBeforeClose":0}"""))
    }

    @Test
    fun `pendingFor cuenta solo las llegadas vivas de esta sucursal`() {
        fun fila(action: String, venue: String, intentos: Int = 0) =
            PendingReservationActionEntity(
                reservationId = "r",
                action = action,
                payloadJson = KioskTap.payload(venue, C, 900_000),
                attemptCount = intentos,
            )
        val filas = listOf(
            fila(KioskTap.ACTION, "v1"),
            fila(KioskTap.ACTION, "v1", intentos = 5), // en cuarentena
            fila(KioskTap.ACTION, "v2"), // de otra sucursal: no frena a v1
            fila("CHECK_IN", "v1"), // del personal: tiene su propio conteo
        )
        assertEquals(1, KioskTap.pendingFor(filas, "v1", 5))
        assertEquals(1, KioskTap.pendingFor(filas, "v2", 5))
        assertEquals(0, KioskTap.pendingFor(filas, null, 5))
    }

    @Test
    fun `outcome separa enviado, se queda y rechazado`() {
        assertEquals(KioskTap.Outcome.Sent, KioskTap.outcome(Result.success(Unit)))
        assertEquals(KioskTap.Outcome.Kept, KioskTap.outcome(Result.failure(IOException())))
        assertEquals(KioskTap.Outcome.Kept, KioskTap.outcome(Result.failure(KioskCheckInApi.Rejected(503, "caído"))))
        assertEquals(KioskTap.Outcome.Refused("x"), KioskTap.outcome(Result.failure(KioskCheckInApi.Rejected(409, "x"))))
        assertTrue(KioskTap.outcome(Result.failure(KioskCheckInApi.OutsideWindow())) is KioskTap.Outcome.Refused)
    }

    @Test
    fun `408 y 429 son pasajeros - la fila se queda`() {
        assertEquals(KioskTap.Outcome.Kept, KioskTap.outcome(Result.failure(KioskCheckInApi.Rejected(408, "Request Timeout"))))
        assertEquals(KioskTap.Outcome.Kept, KioskTap.outcome(Result.failure(KioskCheckInApi.Rejected(429, "Demasiadas solicitudes"))))
    }
}
