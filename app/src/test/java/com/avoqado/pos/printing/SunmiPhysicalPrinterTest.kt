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
