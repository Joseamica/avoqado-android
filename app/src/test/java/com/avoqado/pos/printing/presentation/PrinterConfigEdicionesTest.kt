package com.avoqado.pos.printing.presentation

import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.SavedPrinter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La prueba de impresión salía a 80 mm con 58 elegido, y el ticket se cortaba.
 *
 * El ancho SÍ se guardaba: lo que fallaba era QUÉ se mandaba a imprimir. El
 * botón usaba `printer`, el parámetro del Composable — una copia congelada al
 * abrir la hoja — en vez de la impresora con lo editado en pantalla. Por eso con
 * rollo de 80 salía bien: estaba imprimiendo a 80 de verdad.
 *
 * Reportado desde una tablet el 2026-08-07.
 *
 * ⚠️ Lo que ESTE test NO cubre: que los botones llamen a `printerEditado()` y no
 * a `printer`. Eso es cableado de UI y sólo se ve imprimiendo. Aquí se fija que
 * la impresora editada refleje el ancho, que es la mitad comprobable.
 */
class PrinterConfigEdicionesTest {

    private fun base() = SavedPrinter(
        id = "p1",
        name = "Cocina",
        connectionType = "wifi",
        address = "192.168.1.50",
        port = 9100,
        paperWidthMm = 80,
    )

    private fun SavedPrinter.editandoAncho(mm: Int) = conEdiciones(
        name = name,
        roles = roles,
        paperWidthMm = mm,
        autoPrintReceipts = autoPrintReceipts,
        autoPrintKitchenTickets = autoPrintKitchenTickets,
        autoOpenCashDrawer = autoOpenCashDrawer,
        numberOfCopies = numberOfCopies,
    )

    @Test
    fun `elegir 58 deja la impresora en 58, no en la que se abrio`() {
        val abierta = base()                       // la hoja se abrió con 80
        val editada = abierta.editandoAncho(58)    // el usuario elige 58

        assertEquals(58, editada.paperWidthMm)
        assertEquals(PaperWidth.MM58, editada.paperWidth)
        // El original no se toca: es una copia, por eso hay que usar la editada.
        assertEquals(80, abierta.paperWidthMm)
    }

    @Test
    fun `58 imprime a 32 caracteres, que es lo que cabe en el rollo`() {
        // El síntoma: con 48 caracteres en un cabezal de 58 mm, las líneas se
        // parten y el ticket sale cortado.
        assertEquals(32, base().editandoAncho(58).paperWidth.charsPerLine)
        assertEquals(48, base().editandoAncho(80).paperWidth.charsPerLine)
    }

    @Test
    fun `lo demas que se edita tambien viaja`() {
        val editada = base().conEdiciones(
            name = "Barra",
            roles = listOf("kitchen", "bar"),
            paperWidthMm = 58,
            autoPrintReceipts = true,
            autoPrintKitchenTickets = true,
            autoOpenCashDrawer = false,
            numberOfCopies = 2,
        )
        assertEquals("Barra", editada.name)
        assertEquals(listOf("kitchen", "bar"), editada.roles)
        assertEquals(true, editada.autoPrintReceipts)
        assertEquals(false, editada.autoOpenCashDrawer)
        assertEquals(2, editada.numberOfCopies)
        // La identidad no cambia: sigue siendo la misma impresora.
        assertEquals("p1", editada.id)
        assertEquals("192.168.1.50", editada.address)
    }
}
