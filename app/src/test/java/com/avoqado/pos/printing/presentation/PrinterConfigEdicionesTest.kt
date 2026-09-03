package com.avoqado.pos.printing.presentation

import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterRole
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
        leftMarginChars = leftMarginChars,
        autoPrintReceipts = autoPrintReceipts,
        autoPrintKitchenTickets = autoPrintKitchenTickets,
        autoOpenCashDrawer = autoOpenCashDrawer,
        numberOfCopies = numberOfCopies,
    )

    private fun SavedPrinter.editandoMargen(columnas: Int) = conEdiciones(
        name = name,
        roles = roles,
        paperWidthMm = paperWidthMm,
        leftMarginChars = columnas,
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
    fun `ajustar el margen deja la impresora con el margen nuevo, no con el de apertura`() {
        // Mismo defecto que el ancho, y aquí duele más: la página de prueba ES la
        // herramienta para calibrar el margen. Si imprimiera con la copia
        // congelada, subirle al margen no cambiaría nada en el papel y quien
        // instala concluiría que el ajuste no sirve.
        val abierta = base()
        val editada = abierta.editandoMargen(6)

        assertEquals(6, editada.leftMarginChars)
        assertEquals(0, abierta.leftMarginChars)
    }

    @Test
    fun `una impresora nueva hereda el margen que ya calibro otra del local`() {
        // Calibrar es una vez por SUCURSAL, no una vez por aparato: cinco
        // impresoras iguales con los mismos adaptadores dan el mismo número.
        val yaCalibrada = base().copy(id = "p1", paperWidthMm = 58, leftMarginChars = 9)
        val nueva = base().copy(id = "p2", paperWidthMm = 58)

        assertEquals(9, margenHeredado(listOf(yaCalibrada, nueva), "p2", 58, PrinterConnectionType.WIFI))
    }

    @Test
    fun `no hereda de una impresora de otro ancho`() {
        // El corrimiento sólo existe porque el rollo no llena el cabezal. Copiar
        // el de una de 58 a una de 80 recorrería el ticket sin razón.
        val de58 = base().copy(id = "p1", paperWidthMm = 58, leftMarginChars = 9)

        assertEquals(null, margenHeredado(listOf(de58), "p2", 80, PrinterConnectionType.WIFI))
    }

    @Test
    fun `no se hereda de si misma`() {
        // Sin esto, la única impresora del local se "heredaría" su propio valor
        // y el ajuste parecería pegarse solo.
        val sola = base().copy(id = "p1", paperWidthMm = 58, leftMarginChars = 9)

        assertEquals(null, margenHeredado(listOf(sola), "p1", 58, PrinterConnectionType.WIFI))
    }

    @Test
    fun `sin ninguna calibrada no inventa un numero`() {
        // 0 es el default correcto y seguro. Adivinar un corrimiento en una
        // impresora nativa de 58 mm le comería el precio por la derecha.
        val sinCalibrar = base().copy(id = "p1", paperWidthMm = 58, leftMarginChars = 0)

        assertEquals(null, margenHeredado(listOf(sinCalibrar), "p2", 58, PrinterConnectionType.WIFI))
    }

    @Test
    fun `la integrada NUNCA hereda el margen de una con adaptadores`() {
        // 🔴 Hueco real, encontrado antes de probar en una D3. El corrimiento
        // existe SÓLO porque un rollo angosto con adaptadores no empieza donde
        // el cabezal empieza. Un cabezal soldado al equipo no lleva adaptadores:
        // su rollo llena su ancho por construcción, y su margen es 0.
        //
        // Sin esta exclusión la integrada hereda el 9 de la Epson del mismo
        // local y se recorre a la derecha, comiéndose la columna del precio —
        // el peor lado para fallar, porque un ticket mocho de la izquierda se ve
        // y uno sin el último dígito del total se paga.
        val epsonConAdaptadores = base().copy(id = "p1", paperWidthMm = 58, leftMarginChars = 9)
        val integrada = base().copy(id = "p2", connectionType = "internal", paperWidthMm = 58)

        assertEquals(
            null,
            margenHeredado(listOf(epsonConAdaptadores, integrada), "p2", 58, PrinterConnectionType.INTERNAL),
        )
    }

    @Test
    fun `una integrada tampoco contagia su margen a las demas`() {
        // Si alguien le puso margen a la integrada por error, ese error no se
        // propaga al resto del local.
        val integradaMalConfigurada = base().copy(id = "p1", connectionType = "internal", paperWidthMm = 58, leftMarginChars = 9)

        assertEquals(null, margenHeredado(listOf(integradaMalConfigurada), "p2", 58, PrinterConnectionType.WIFI))
    }

    // MARK: - Roles que la hoja OFRECE

    @Test
    fun `el selector no ofrece el rol Bar cuando la impresora no lo tiene`() {
        // "Bar" no hace nada en el ruteo (ninguna ruta lo consulta): ofrecerlo hace creer
        // que la comanda de barra saldrá ahí — el engaño exacto que sufrió Testarudo
        // (2026-08-31). Para rutear a la barra se usa una ESTACIÓN del dashboard.
        val ofrecidos = rolesConfigurables(emptySet())

        assertEquals(listOf(PrinterRole.RECEIPT, PrinterRole.KITCHEN, PrinterRole.LABEL), ofrecidos)
    }

    @Test
    fun `el rol Bar sigue visible SOLO para poder quitarlo si ya estaba asignado`() {
        val ofrecidos = rolesConfigurables(setOf("bar"))

        assertEquals(listOf(PrinterRole.RECEIPT, PrinterRole.KITCHEN, PrinterRole.BAR, PrinterRole.LABEL), ofrecidos)
    }

    @Test
    fun `lo demas que se edita tambien viaja`() {
        val editada = base().conEdiciones(
            name = "Barra",
            roles = listOf("kitchen", "bar"),
            paperWidthMm = 58,
            leftMarginChars = 6,
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
        assertEquals(6, editada.leftMarginChars)
        // La identidad no cambia: sigue siendo la misma impresora.
        assertEquals("p1", editada.id)
        assertEquals("192.168.1.50", editada.address)
    }
}
