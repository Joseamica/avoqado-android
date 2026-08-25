package com.avoqado.pos.kiosk

import com.avoqado.pos.kiosk.domain.KioskWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * La ventana de check-in del kiosco: cuándo se abre sola la lista y cuándo se
 * cierra sola.
 *
 * Estas pruebas fijan la MISMA regla que aplica el servidor
 * (`evaluateKioskWindow` en `checkIn.service.ts`). Si alguien cambia una y no la
 * otra, el kiosco enseñaría una lista que el servidor rechaza con 422 — y el
 * cliente vería su nombre, lo tocaría, y le diría que no. Estas pruebas existen
 * para que esa divergencia truene aquí y no enfrente de alguien.
 */
class KioskWindowTest {

    private val inicio: Instant = Instant.parse("2026-08-24T19:00:00Z") // la clase de las 7
    private val tolerancia = 20

    private fun enMinutos(m: Long): Instant = inicio.plusSeconds(m * 60)

    @Test
    fun `P1 cerrada 21 minutos antes de la clase`() {
        assertFalse(KioskWindow.isOpen(inicio, enMinutos(-21), tolerancia))
    }

    @Test
    fun `P1 abre EXACTAMENTE 20 minutos antes`() {
        assertTrue(KioskWindow.isOpen(inicio, enMinutos(-20), tolerancia))
    }

    @Test
    fun `P1 abierta en la hora de inicio`() {
        assertTrue(KioskWindow.isOpen(inicio, inicio, tolerancia))
    }

    @Test
    fun `P1 abierta un minuto antes de vencer la tolerancia`() {
        assertTrue(KioskWindow.isOpen(inicio, enMinutos(19), tolerancia))
    }

    /**
     * El instante exacto del cierre pertenece al no-show, no al check-in: el job
     * del servidor marca con `deadline <= now`. Un `<=` aquí dejaría entrar a
     * alguien que allá ya quedó como falta.
     */
    @Test
    fun `P1 CERRADA en el instante exacto de inicio mas tolerancia`() {
        assertFalse(KioskWindow.isOpen(inicio, enMinutos(20), tolerancia))
    }

    @Test
    fun `P1 cerrada pasada la tolerancia`() {
        assertFalse(KioskWindow.isOpen(inicio, enMinutos(21), tolerancia))
    }

    /** Tolerancia 0 = se cierra en el minuto exacto en que empieza la clase. */
    @Test
    fun `P2 con tolerancia cero cierra en el inicio exacto`() {
        assertTrue(KioskWindow.isOpen(inicio, enMinutos(-1), 0))
        assertFalse(KioskWindow.isOpen(inicio, inicio, 0))
    }

    /** La tolerancia la mueve el admin; la apertura de 20 min no se mueve. */
    @Test
    fun `P2 una tolerancia mas larga extiende solo el cierre`() {
        assertTrue(KioskWindow.isOpen(inicio, enMinutos(45), 60))
        assertFalse(KioskWindow.isOpen(inicio, enMinutos(-21), 60))
    }
}
