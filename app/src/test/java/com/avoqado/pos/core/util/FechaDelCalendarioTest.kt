package com.avoqado.pos.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class FechaDelCalendarioTest {

    private val zonaOriginal: TimeZone = TimeZone.getDefault()
    private val mexico: TimeZone = TimeZone.getTimeZone("America/Mexico_City")

    @After
    fun restaurar() = TimeZone.setDefault(zonaOriginal)

    // Lo que el DatePicker de Material3 devuelve al tocar el 9 de octubre de 2026: medianoche UTC.
    private val nueveDeOctubreDelPicker = 1_791_504_000_000L

    @Test
    fun `P1 elegir el 9 en hora de Mexico devuelve el 9 y no el 8`() {
        TimeZone.setDefault(mexico)
        assertEquals(LocalDate.of(2026, 10, 9), FechaDelCalendario.deMilisDelPicker(nueveDeOctubreDelPicker))
    }

    @Test
    fun `P1 abrir el calendario en el 9 y dar Aceptar sin tocar nada deja el 9`() {
        TimeZone.setDefault(mexico)
        val inicial = FechaDelCalendario.aMilisDelPicker(LocalDate.of(2026, 10, 9))
        assertEquals(nueveDeOctubreDelPicker, inicial)
        assertEquals(LocalDate.of(2026, 10, 9), FechaDelCalendario.deMilisDelPicker(inicial))
    }

    @Test
    fun `el dia no depende de la zona del aparato`() {
        for (zona in listOf("America/Mexico_City", "America/Tijuana", "Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zona))
            val fecha = LocalDate.of(2026, 3, 1)
            assertEquals("zona $zona", fecha, FechaDelCalendario.deMilisDelPicker(FechaDelCalendario.aMilisDelPicker(fecha)))
        }
    }

    @Test
    fun `P2 Desde arranca en el milisegundo cero del dia en la zona del negocio`() {
        // month es base 0, como lo entrega el DatePickerDialog de Android (9 = octubre).
        assertEquals(
            Instant.parse("2026-10-15T06:00:00.000Z").toEpochMilli(),
            FechaDelCalendario.inicioDelDia(2026, 9, 15, mexico),
        )
    }

    @Test
    fun `P2 Hasta termina en el ultimo milisegundo del dia en la zona del negocio`() {
        assertEquals(
            Instant.parse("2026-10-21T05:59:59.999Z").toEpochMilli(),
            FechaDelCalendario.finDelDia(2026, 9, 20, mexico),
        )
    }
}
