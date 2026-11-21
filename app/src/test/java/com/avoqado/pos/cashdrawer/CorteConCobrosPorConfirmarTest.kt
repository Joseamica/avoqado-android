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
 *
 * Rediseño tras la ronda 2 de Codex: la línea dice las dudas vivas de este aparato AL MOMENTO (lo mismo que la banda), sin
 * repartirlas por turno con horas (cierre reenviado sin red, reloj adelantado y filas sin fecha lo hacían mentir). Por eso sale en
 * el corte parcial, en «Cerrar caja» y en el corte que se enseña al cerrar, y no en un corte viejo del Historial.
 */
class CorteConCobrosPorConfirmarTest {

    private fun cobro(id: String, monto: Int?, desde: Long? = 1_000L, antesDePropina: Boolean = false, venue: String? = "v1") =
        CobroPorConfirmar(requestId = id, venueId = venue, montoCents = monto, desdeMillis = desde, antesDePropina = antesDePropina)

    @Test
    fun `P9 sin dudas no sale la linea`() {
        assertNull(PorConfirmarEnElCorte.de(emptyList()))
    }

    @Test
    fun `P9 una duda dice cuantas y su importe con propina`() {
        val linea = PorConfirmarEnElCorte.de(listOf(cobro("a", 2_000)))!!
        assertEquals("Cobros con tarjeta por confirmar (1)", linea.etiqueta)
        assertEquals("$20.00", linea.importe)
        assertEquals("Cobros con tarjeta por confirmar (1): $20.00. Pueden faltar en el total.", linea.texto)
    }

    @Test
    fun `P9 varias dudas suman su importe`() {
        val linea = PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000), cobro("b", 15_000)))!!
        assertEquals(2, linea.cuantos)
        assertEquals("Cobros con tarjeta por confirmar (2): $350.00. Pueden faltar en el total.", linea.texto)
    }

    @Test
    fun `P9 cuenta todas las vivas, como la banda - sin importar su hora ni si les falta la sucursal`() {
        // Codex r2 #1 y #2: repartirlas por hora (reloj adelantado, cierre reenviado) escondía dudas vivas. Ya no se corta por hora.
        val linea = PorConfirmarEnElCorte.de(
            listOf(cobro("ayer", 1_000, desde = 1L), cobro("sin-fecha", 2_000, desde = null), cobro("sin-sucursal", 3_000, venue = null)),
        )!!
        assertEquals(3, linea.cuantos)
        assertEquals("$60.00", linea.importe)
    }

    @Test
    fun `P9 una fila sin importe o sin su propina no deja afirmar el total`() {
        assertEquals("al menos $200.00", PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000), cobro("b", null)))!!.importe)
        assertEquals("al menos $200.00", PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000, antesDePropina = true)))!!.importe)
        assertEquals("importe por confirmar", PorConfirmarEnElCorte.de(listOf(cobro("a", null)))!!.importe)
    }

    @Test
    fun `P9 muchas dudas grandes no desbordan la suma`() {
        // Codex r2 #4: 22 intentos de $1,000,000.00 (el tope por intento) daban −$20,949,672.96 sumando en Int.
        val linea = PorConfirmarEnElCorte.de((1..22).map { cobro("c$it", 100_000_000) })!!
        assertEquals("$22,000,000.00", linea.importe)
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
        val linea = PorConfirmarEnElCorte.de(listOf(cobro("a", 20_000), cobro("b", 115_000)))!!
        listOf(PaperWidth.MM58, PaperWidth.MM80).forEach { ancho ->
            listOf(false, true).forEach { parcial ->
                val t = papel(linea, ancho, parcial)
                assertTrue("$ancho parcial=$parcial: falta el título", t.contains("COBROS CON TARJETA POR CONFIRMAR\n"))
                assertTrue("$ancho parcial=$parcial: falta el importe", t.contains(dosColumnas("2 cobros", "$1,350.00", ancho) + "\n"))
                // Codex r1 #1: si la terminal sí registró el cobro y el POS aún no se entera, ya viene en el desglose: no se afirma
                // que «no están en el total».
                assertFalse(t.contains("No están en el total"))
                // Texto decidido por «OFFLINE» (21-nov): conserva la intención de B4 sin afirmar algo falso; en su propio renglón.
                assertTrue("$ancho parcial=$parcial: falta «Pueden faltar en el total.»",
                    t.contains(dosColumnas("2 cobros", "$1,350.00", ancho) + "\nPueden faltar en el total.\n"))
            }
        }
        assertTrue(papel(PorConfirmarEnElCorte.de(listOf(cobro("a", 2_000)))).contains(dosColumnas("1 cobro", "$20.00", PaperWidth.MM58)))
        assertFalse(papel(null).contains("POR CONFIRMAR"))
    }

    // MARK: - El cableado (se lee la fuente: aquí no hay `createComposeRule`)

    private fun fuente(ruta: String) = java.io.File("src/main/java/com/avoqado/pos/cashdrawer/$ruta").readText()

    @Test
    fun `P9 el corte en pantalla pinta la linea sin afirmar que no esta en el total`() {
        val corte = fuente("presentation/DailyReportView.kt")
        assertTrue(corte.contains("porConfirmar: com.avoqado.pos.cashdrawer.data.PorConfirmarEnElCorte? = null"))
        // Codex r3 #1: en una fila de dos columnas, un teléfono angosto con letra grande dejaba el importe sin ancho. Un solo texto
        // que se parte en renglones: el número y el importe siempre se ven.
        val linea = corte.substringAfter("porConfirmar?.let {").substringBefore("\n            }")
        assertTrue(linea.contains("text = it.texto"))
        assertFalse(linea.contains("ReportRow("))
        assertFalse(corte.contains("No están en el total"))
    }

    @Test
    fun `P9 la hoja Cerrar caja pinta la linea y su boton sigue al alcance`() {
        val hoja = fuente("presentation/CloseDrawerSheet.kt")
        assertTrue(hoja.contains("porConfirmar: com.avoqado.pos.cashdrawer.data.PorConfirmarEnElCorte? = null"))
        assertTrue(hoja.contains("porConfirmar?.let"))
        assertTrue(hoja.contains("text = it.texto"))
        // Codex r1 #3: con retiros, la línea y el teclado abierto (o una ventana baja de WINDOWS), «Cerrar caja» no queda fuera.
        assertTrue(hoja.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `P9 parcial, Cerrar caja y el corte del cierre reciben la lista - el Historial no`() {
        val pantalla = fuente("presentation/CashDrawerScreen.kt")
        // Tableta, teléfono y corte parcial: cada `DailyReportView(` lleva su línea; las dos hojas de cierre, también.
        listOf("DailyReportView(" to 3, "CloseDrawerSheet(" to 2).forEach { (llamada, veces) ->
            val tramos = pantalla.split(llamada).drop(1).map { it.substringBefore("\n        )") }
            assertEquals(llamada, veces, tramos.size)
            tramos.forEach { assertTrue("$llamada sin porConfirmar", it.contains("porConfirmar = ")) }
        }
        // El corte de una sesión del Historial no la lleva; el que se enseña al cerrar, sí (tableta y teléfono).
        assertEquals(2, Regex("reportDelCierre = false\\s+reportSession = session").findAll(pantalla).count())
        assertEquals(2, Regex("reportDelCierre = true\\s+reportSession = corte\\.session").findAll(pantalla).count())
        assertEquals(2, Regex("val lineaPorConfirmar = if \\(reportDelCierre\\) PorConfirmarEnElCorte\\.de\\(vivas\\) else null").findAll(pantalla).count())
        // El papel se archiva: la lista se relee del disco al tocar «Imprimir» (Codex r2 #3), en los dos cortes del cierre y en el parcial.
        assertEquals(2, Regex("viewModel\\.printCorte\\([^)]*porConfirmar = if \\(reportDelCierre\\) dudas\\.lineaAlImprimir\\(", RegexOption.DOT_MATCHES_ALL)
            .findAll(pantalla).count())
        assertTrue(pantalla.contains("viewModel.printPartialCorte(partialSession, events, dudas.lineaAlImprimir())"))
        val vm = fuente("presentation/CashDrawerViewModel.kt")
        assertEquals(2, Regex("porConfirmar = porConfirmar").findAll(vm).count()) // printCorte → builder y printPartialCorte → printCorte
    }
}
