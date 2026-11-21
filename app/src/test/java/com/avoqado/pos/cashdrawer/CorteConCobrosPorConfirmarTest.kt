package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder
import com.avoqado.pos.cashdrawer.data.PorConfirmarEnElCorte
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.payment.presentation.CobroPorConfirmar
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1, fila 12 / P9 (full-testing del 21-nov en la D3): con «Cobros por confirmar: 1» en la banda, ni el corte parcial, ni «Cerrar
 * caja», ni el corte final decían que quedó dinero con tarjeta en duda. La línea sólo informa: no frena el cierre.
 */
class CorteConCobrosPorConfirmarTest {

    private fun cobro(id: String, monto: Int?, desde: Long? = 1_000L, antesDePropina: Boolean = false) =
        CobroPorConfirmar(requestId = id, venueId = "v1", montoCents = monto, desdeMillis = desde, antesDePropina = antesDePropina)

    @Test
    fun `P9 sin dudas no sale la linea`() {
        assertNull(PorConfirmarEnElCorte.de(emptyList(), finDelTurnoMillis = null))
        assertNull(PorConfirmarEnElCorte.de(emptyList(), finDelTurnoMillis = 5_000L))
    }

    @Test
    fun `P9 una duda dice cuantas y su importe con propina`() {
        val linea = PorConfirmarEnElCorte.de(listOf(cobro("a", 2_000)), finDelTurnoMillis = null)!!
        assertEquals("Cobros con tarjeta por confirmar (1)", linea.etiqueta)
        assertEquals("$20.00", linea.importe)
        assertEquals("Cobros con tarjeta por confirmar (1): $20.00", linea.texto)
    }

    @Test
    fun `P9 varias dudas suman su importe`() {
        val linea = PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000), cobro("b", 15_000)), finDelTurnoMillis = null)!!
        assertEquals(2, linea.cuantos)
        assertEquals("Cobros con tarjeta por confirmar (2): $350.00", linea.texto)
    }

    @Test
    fun `P9 una fila sin importe o sin su propina no deja afirmar el total`() {
        assertEquals("al menos $200.00", PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000), cobro("b", null)), null)!!.importe)
        assertEquals("al menos $200.00", PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000, antesDePropina = true)), null)!!.importe)
        assertEquals("importe por confirmar", PorConfirmarEnElCorte.de(listOf(cobro("a", null)), null)!!.importe)
    }

    @Test
    fun `P9 una duda nacida despues del cierre no sale en ese corte, la de antes y la sin fecha si`() {
        val cierre = 10_000L
        val linea = PorConfirmarEnElCorte.de(
            listOf(cobro("antes", 1_000, desde = 9_000L), cobro("sin-fecha", 2_000, desde = null), cobro("despues", 4_000, desde = 11_000L)),
            finDelTurnoMillis = cierre,
        )!!
        assertEquals(2, linea.cuantos)
        assertEquals("$30.00", linea.importe)
        assertNull(PorConfirmarEnElCorte.de(listOf(cobro("despues", 4_000, desde = 11_000L)), finDelTurnoMillis = cierre))
    }

    // MARK: - El papel

    private val cerrada = CashDrawerSessionEntity(
        id = "s1", venueId = "v1", deviceName = "Caja 1", openedByStaffId = "st1", openedByName = "Ana",
        openedAt = 1_785_000_000_000L, startingAmountCents = 50_000, closedAt = 1_785_010_000_000L, actualAmountCents = 50_000,
    )

    private fun papel(porConfirmar: PorConfirmarEnElCorte?, ancho: PaperWidth = PaperWidth.MM58, isPartial: Boolean = false): String =
        String(
            CorteTicketBuilder.build(
                session = cerrada, events = emptyList(), tenders = emptyList(), venueName = "Café", paperWidth = ancho,
                isPartial = isPartial, porConfirmar = porConfirmar,
            ),
            Charsets.ISO_8859_1,
        )

    /** El renglón de dos columnas tal como lo arma la impresora: si no cupiera, el relleno no daría el ancho exacto. */
    private fun dosColumnas(izq: String, der: String, ancho: PaperWidth) = izq + " ".repeat(ancho.charsPerLine - izq.length - der.length) + der

    @Test
    fun `P9 el papel lleva la linea, cabe en 58 mm y sin dudas no sale`() {
        val linea = PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000), cobro("b", 115_000)), null)!!
        listOf(PaperWidth.MM58, PaperWidth.MM80).forEach { ancho ->
            listOf(false, true).forEach { parcial ->
                val t = papel(linea, ancho, parcial)
                assertTrue("$ancho parcial=$parcial: falta el título", t.contains("COBROS CON TARJETA POR CONFIRMAR\n"))
                assertTrue("$ancho parcial=$parcial: falta el importe", t.contains(dosColumnas("2 cobros", "$1,350.00", ancho) + "\n"))
                assertTrue("$ancho parcial=$parcial: falta «No están en el total.»", t.contains("No están en el total.\n"))
            }
        }
        assertTrue(papel(PorConfirmarEnElCorte.de(listOf(cobro("a", 2_000)), null)).contains(dosColumnas("1 cobro", "$20.00", PaperWidth.MM58)))
        assertFalse(papel(null).contains("POR CONFIRMAR"))
    }

    // MARK: - El cableado (se lee la fuente: aquí no hay `createComposeRule`)

    private fun fuente(ruta: String) = java.io.File("src/main/java/com/avoqado/pos/cashdrawer/$ruta").readText()

    @Test
    fun `P9 el corte en pantalla pinta la linea y que no esta en el total`() {
        val corte = fuente("presentation/DailyReportView.kt")
        assertTrue(corte.contains("porConfirmar: com.avoqado.pos.cashdrawer.data.PorConfirmarEnElCorte? = null"))
        assertTrue(corte.contains("ReportRow(label = linea.etiqueta, value = linea.importe)"))
        assertTrue(corte.contains("PorConfirmarEnElCorte.NO_ESTAN_EN_EL_TOTAL"))
    }

    @Test
    fun `P9 la hoja Cerrar caja pinta la linea`() {
        val hoja = fuente("presentation/CloseDrawerSheet.kt")
        assertTrue(hoja.contains("porConfirmar: com.avoqado.pos.cashdrawer.data.PorConfirmarEnElCorte? = null"))
        assertTrue(hoja.contains("porConfirmar?.let"))
        assertTrue(hoja.contains("text = it.texto"))
    }

    @Test
    fun `P9 los tres cortes, la hoja y los tres papeles reciben la lista`() {
        val pantalla = fuente("presentation/CashDrawerScreen.kt")
        // Tableta, teléfono y corte parcial: cada `DailyReportView(` lleva su línea; las dos hojas de cierre, también.
        listOf("DailyReportView(" to 3, "CloseDrawerSheet(" to 2).forEach { (llamada, veces) ->
            val tramos = pantalla.split(llamada).drop(1).map { it.substringBefore("\n        )") }
            assertEquals(llamada, veces, tramos.size)
            tramos.forEach { assertTrue("$llamada sin porConfirmar", it.contains("porConfirmar = ")) }
        }
        // El papel: los dos cortes cerrados y el parcial.
        assertEquals(2, Regex("viewModel\\.printCorte\\([^)]*porConfirmar = ", RegexOption.DOT_MATCHES_ALL).findAll(pantalla).count())
        assertTrue(pantalla.contains("viewModel.printPartialCorte(partialSession, events, porConfirmar)"))
        val vm = fuente("presentation/CashDrawerViewModel.kt")
        assertEquals(2, Regex("porConfirmar = porConfirmar").findAll(vm).count()) // printCorte → builder y printPartialCorte → printCorte
    }
}
