package com.avoqado.pos.kds.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El reloj de la comanda tiene que caber de un vistazo.
 *
 * Era "%d:%02d" con los minutos sin tope: una comanda de hora y media salía
 * "90:14" y una olvidada, "4320:07". Medido en el iPad el 2026-08-04 (el mismo
 * defecto estaba en las dos plataformas): la pantalla de cocina mostraba
 * "30090:13" en TODOS los tickets — cinco dígitos donde la cocina sólo quiere
 * saber, de reojo, si va tarde.
 *
 * Espejo de `KDSTiempoTests` en iOS.
 */
class KDSElapsedTimeTest {

    private fun min(m: Long) = m * 60_000
    private fun hrs(h: Long) = h * 3_600_000
    private fun days(d: Long) = d * 86_400_000

    @Test
    fun `menos de una hora va en minutos y segundos`() {
        assertEquals("0:00", formatElapsedTime(0))
        assertEquals("0:32", formatElapsedTime(32_000))
        assertEquals("7:32", formatElapsedTime(min(7) + 32_000))
        assertEquals("59:59", formatElapsedTime(min(59) + 59_000))
    }

    @Test
    fun `a partir de una hora cambia de unidad`() {
        // El caso que rompía: un servicio lleno pasa de la hora sin más.
        assertEquals("1h 00", formatElapsedTime(hrs(1)))
        assertEquals("1h 30", formatElapsedTime(min(90) + 14_000))   // antes "90:14"
        assertEquals("23h 59", formatElapsedTime(hrs(23) + min(59)))
    }

    @Test
    fun `una comanda olvidada se lee en dias`() {
        // Lo que había en la pantalla de verdad: tickets de 21 y 25 días.
        assertEquals("1 d", formatElapsedTime(days(1)))
        assertEquals("25 d", formatElapsedTime(days(25)))
    }

    @Test
    fun `nunca se pasa de lo que cabe en la esquina del ticket`() {
        var ms = 0L
        while (ms < days(40)) {
            val t = formatElapsedTime(ms)
            assertTrue("«$t» no cabe en la esquina de la comanda", t.length <= 6)
            ms += 997_000
        }
    }

    @Test
    fun `un reloj adelantado no pinta negativos`() {
        // Si el reloj del dispositivo va atrasado respecto al server.
        assertEquals("0:00", formatElapsedTime(-120_000))
    }
}
