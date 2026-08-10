package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.SunmiInnerPrinter
import com.sunmi.peripheral.printer.SunmiPrinterService
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El bind AIDL NO prueba que exista una impresora: Sunmi preinstala
 * `woyou.aidlservice.jiuiv5` en toda su gama, así que una T3 Pro (sin cabezal)
 * también se liga. La app terminaba ofreciendo "Impresora integrada" en un
 * equipo sin impresora → comanda enrutada a un destino inexistente → la cocina
 * nunca se entera, y el fallo se descubre al servir, no al configurar.
 *
 * Los valores de la T3 salieron de hardware real (2026-07-28): state=505,
 * modal/serial vacíos.
 *
 * El sesgo es hacia OFRECERLA: sólo se descarta con evidencia positiva de
 * ausencia, porque descartar de más deja al local sin comandas.
 */
class SunmiPhysicalPrinterTest {

    private fun printerWith(
        state: Int? = null,
        modal: String? = null,
        throws: Boolean = false,
    ): SunmiInnerPrinter {
        val svc = mockk<SunmiPrinterService>(relaxed = true)
        if (throws) {
            every { svc.updatePrinterState() } throws RuntimeException("servicio caído")
            every { svc.printerModal } throws RuntimeException("servicio caído")
        } else {
            state?.let { every { svc.updatePrinterState() } returns it }
                ?: every { svc.updatePrinterState() } throws RuntimeException("no soportado")
            every { svc.printerModal } returns modal
        }
        return SunmiInnerPrinter(mockk(relaxed = true)).also { it.attachServiceForTest(svc) }
    }

    @Test
    fun `T3 Pro sin impresora (state 505) NO se ofrece`() {
        assertFalse(printerWith(state = 505, modal = "").hasPhysicalPrinter)
    }

    @Test
    fun `equipo con impresora lista SI se ofrece`() {
        assertTrue(printerWith(state = 1, modal = "D3-mini").hasPhysicalPrinter)
    }

    @Test
    fun `sin papel SI se ofrece — la impresora existe, solo hay que reponer`() {
        assertTrue(printerWith(state = 4, modal = "D3-mini").hasPhysicalPrinter)
    }

    @Test
    fun `tapa abierta SI se ofrece`() {
        assertTrue(printerWith(state = 6, modal = "D3-mini").hasPhysicalPrinter)
    }

    @Test
    fun `estado desconocido SI se ofrece — ante la duda nunca dejar sin imprimir`() {
        assertTrue(printerWith(state = 77, modal = "").hasPhysicalPrinter)
    }

    @Test
    fun `servicio que revienta pero reporta modelo SI se ofrece`() {
        val svc = mockk<SunmiPrinterService>(relaxed = true)
        every { svc.updatePrinterState() } throws RuntimeException("boom")
        every { svc.printerModal } returns "D3-mini"
        val p = SunmiInnerPrinter(mockk(relaxed = true)).also { it.attachServiceForTest(svc) }
        assertTrue(p.hasPhysicalPrinter)
    }

    @Test
    fun `sin estado y sin modelo NO se ofrece — nada respalda que exista`() {
        assertFalse(printerWith(throws = true).hasPhysicalPrinter)
    }

    @Test
    fun `sin servicio ligado NO se ofrece`() {
        assertFalse(SunmiInnerPrinter(mockk(relaxed = true)).hasPhysicalPrinter)
    }
}

/**
 * El gemelo del anterior: la impresora SÍ existe, pero **no tiene papel** — y la
 * app cantaba "Recibo impreso".
 *
 * 🔴 Encontrado en la T3 con una EPSON TM-m30III el 2026-08-10: el cajero tocó
 * "Imprimir recibo", la pantalla dijo que sí, y no salió nada. El puerto 9100 es
 * fuego-y-olvido: el socket acepta los bytes pase lo que pase. El log lo
 * confirmaba — "Connected to printer" y "Manual reprint succeeded" con el rollo
 * vacío.
 *
 * El arreglo pregunta con `DLE EOT 4` (`0x10 0x04 0x04`), el comando de TIEMPO
 * REAL de ESC/POS que la impresora contesta AUNQUE esté en estado de error, que
 * es justo cuando hace falta. Aquí se fija la lectura de esa respuesta.
 *
 * Espejo de `isOutOfPaper` en PrinterService.kt.
 */
class PaperStatusTest {

    /** Bits 5 y 6 encendidos = rollo agotado (spec ESC/POS, DLE EOT n=4). */
    private fun sinPapel(status: Int): Boolean = status >= 0 && (status and 0x60) == 0x60

    @Test
    fun `0x60 significa sin papel`() {
        assertTrue(sinPapel(0x60))
    }

    @Test
    fun `el estado normal de una Epson lista no se confunde con sin papel`() {
        assertFalse(sinPapel(0x12))
        assertFalse(sinPapel(0x16))
    }

    @Test
    fun `papel POR ACABARSE no es papel agotado`() {
        // Bits 2-3 (0x0C) = near-end: todavía imprime. Bloquear aquí dejaría al
        // local sin tickets con rollo de sobra.
        assertFalse(sinPapel(0x0C))
        assertFalse(sinPapel(0x1E))
    }

    @Test
    fun `un solo bit del sensor NO alcanza para declarar sin papel`() {
        // La spec exige los DOS. Con uno solo (ruido, o un modelo que use el bit
        // para otra cosa) se imprime igual: un falso positivo deja al local sin
        // comandas, que es peor que un aviso de más.
        assertFalse(sinPapel(0x20))
        assertFalse(sinPapel(0x40))
    }

    @Test
    fun `sin respuesta NO se bloquea la impresion`() {
        // read() == -1, o un modelo que no soporta DLE EOT. Falla ABIERTO a
        // propósito: en este dominio el "fail-safe" no puede ser dejar de
        // imprimir — una comanda que no llega a la cocina es peor que un aviso
        // que no aparece. Mismo criterio que el guard de estaciones.
        assertFalse(sinPapel(-1))
    }
}
