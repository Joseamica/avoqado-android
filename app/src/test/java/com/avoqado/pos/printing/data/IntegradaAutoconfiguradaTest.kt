package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.SavedPrinter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 (founder, 28-oct): la impresora integrada de la D3 se configura SOLA como «Recibos y cajón» la primera vez, para que
 * «Impresoras y cajón» y el cobro digan lo mismo y el cajón abra. Si el dueño le cambia los papeles (p. ej. sólo comandas),
 * se respeta: el respaldo escondido de recibos ya no la usa.
 */
class IntegradaAutoconfiguradaTest {
    private val recibosWifi = SavedPrinter(id = "r", name = "Ticketera", connectionType = "wifi", address = "10.0.0.5", roles = listOf("receipt"))
    private val cocinaWifi = SavedPrinter(id = "c", name = "Cocina", connectionType = "wifi", address = "10.0.0.6", roles = listOf("kitchen"))
    private val integradaSoloComandas = SavedPrinter(
        id = "i", name = "Impresora integrada", connectionType = PrinterConnectionType.INTERNAL.value, address = "internal", roles = listOf("kitchen"),
    )

    @Test fun `P1 una D3 sin impresora de recibos y con cabezal la configura sola`() {
        assertTrue(debeAutoconfigurarIntegrada(emptyList(), yaSeHizo = false, hayCabezal = true))
        assertTrue(debeAutoconfigurarIntegrada(listOf(cocinaWifi), yaSeHizo = false, hayCabezal = true))
    }

    @Test fun `P1 sin cabezal (T3 Pro) nunca se configura`() {
        assertFalse(debeAutoconfigurarIntegrada(emptyList(), yaSeHizo = false, hayCabezal = false))
    }

    @Test fun `P1 si ya tiene impresora de recibos no se toca`() {
        assertFalse(debeAutoconfigurarIntegrada(listOf(recibosWifi), yaSeHizo = false, hayCabezal = true))
    }

    @Test fun `P1 si el dueno ya guardo la integrada con otro papel no se agrega otra`() {
        assertFalse(debeAutoconfigurarIntegrada(listOf(integradaSoloComandas), yaSeHizo = false, hayCabezal = true))
    }

    @Test fun `P1 se hace una sola vez - si la borraron no regresa`() {
        assertFalse(debeAutoconfigurarIntegrada(emptyList(), yaSeHizo = true, hayCabezal = true))
    }

    /** Codex r3 #1: el alta automática y la manual (desde «Disponibles») no pueden dejar DOS integradas: imprimiría doble. */
    @Test fun `P1 guardar una integrada reemplaza a la otra integrada, nunca la duplica`() {
        val auto = integradaSoloComandas.copy(id = "internal-auto-receipt", roles = listOf("receipt"))
        val manual = integradaSoloComandas.copy(id = "uuid-manual", roles = listOf("kitchen"))

        val resultado = conGuardada(listOf(recibosWifi, auto), manual)

        assertEquals(listOf(recibosWifi, manual), resultado)
    }

    @Test fun `guardar una impresora normal no toca a las demas`() {
        assertEquals(listOf(recibosWifi, cocinaWifi), conGuardada(listOf(recibosWifi), cocinaWifi))
        val editada = recibosWifi.copy(name = "Ticketera 2")
        assertEquals(listOf(editada, cocinaWifi), conGuardada(listOf(recibosWifi, cocinaWifi), editada))
    }

    /**
     * D5 (28-oct): la Sunmi D3 contesta `printerPaper = 1` (58 mm) aunque su rollo es de 80 mm — confirmado con un ticket real
     * (renglones de 47 caracteres y un Code 128 de 444 puntos salen completos). Con 58 el ticket salía a la mitad del papel.
     */
    @Test fun `P2 la D3 imprime a 80 mm aunque su impresora diga 58`() {
        assertEquals(80, anchoDeLaIntegrada(modelo = "D3", printerPaper = 1))
        assertEquals(80, anchoDeLaIntegrada(modelo = "D3", printerPaper = 2))
    }

    @Test fun `P1 los demas aparatos usan lo que dice su impresora, y ante la duda 58`() {
        assertEquals(58, anchoDeLaIntegrada(modelo = "V2s", printerPaper = 1))
        assertEquals(80, anchoDeLaIntegrada(modelo = "T2", printerPaper = 2))
        assertEquals(58, anchoDeLaIntegrada(modelo = "D3 MINI", printerPaper = 1))
        assertEquals(58, anchoDeLaIntegrada(modelo = "T3 PRO", printerPaper = null))
    }

    @Test fun `P1 la integrada guardada solo para comandas ya no imprime recibos por respaldo`() {
        assertFalse(respaldoDeIntegradaPermitido(listOf(integradaSoloComandas)))
        assertTrue(respaldoDeIntegradaPermitido(listOf(cocinaWifi)))
        assertTrue(respaldoDeIntegradaPermitido(emptyList()))
    }
}
