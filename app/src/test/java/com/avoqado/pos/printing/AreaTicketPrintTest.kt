package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vale de área (AREA_TICKETS) — el papel que el área le da al cliente y que la caja escanea.
 *
 * Lo que estos tests protegen no es la estética del ticket, son tres cosas que dejan a alguien
 * parado en el mostrador sin poder pagar:
 *  - que el código salga IMPRESO EN TEXTO además de en barras (la pistola falla, el papel se
 *    moja, y teclear 10 dígitos es lo único que salva la venta);
 *  - que un código de barras inválido NO cancele el vale entero;
 *  - que el peso del granel se vea, porque es lo que el cliente compara contra la báscula.
 */
class AreaTicketPrintTest {

    private val CODE = "9470000015"

    private fun jamon() = ReceiptItem(
        name = "LOMO CANADIENSE",
        quantity = 1,
        unitPrice = 16400,
        totalPrice = 3674,
        weightSummary = "0.224 kg × \$164.00/kg",
    )

    private fun ticket(
        items: List<ReceiptItem> = listOf(jamon()),
        code: String = CODE,
        showPrices: Boolean = true,
        holdsProduct: Boolean = true,
    ) = AreaTicketData(
        areaTicketCode = code,
        areaName = "Cremería",
        items = items,
        totalCents = items.sumOf { it.totalPrice },
        venueName = "Abarrotes El Sol",
        staffName = "Rosa",
        showPrices = showPrices,
        holdsProduct = holdsProduct,
    )

    private fun render(t: AreaTicketData, paper: PaperWidth = PaperWidth.MM58): String =
        String(ESCPOSPrinter(paperWidth = paper).generateAreaTicket(t), Charsets.ISO_8859_1)

    // MARK: - Lo que no puede faltar

    @Test
    fun `el codigo aparece en TEXTO, no solo dentro del codigo de barras`() {
        val out = render(ticket())
        // Los bytes del CODE128 son binarios: el código legible tiene que estar aparte,
        // como texto plano, o el cajero no tiene qué teclear cuando la pistola no lee.
        val comoTexto = Regex(Regex.escape(CODE)).findAll(out).count()
        assertTrue("El código debe imprimirse como texto legible", comoTexto >= 1)
    }

    @Test
    fun `un codigo invalido deja el vale SIN barras pero NO sin vale`() {
        // "ABC" no es CODE128-C: printBarcode devuelve false sin escribir bytes.
        val out = render(ticket(code = "ABC"))
        assertTrue("El vale debe imprimirse igual", out.contains("CREMERÍA"))
        assertTrue("El código debe quedar legible para teclear", out.contains("ABC"))
        assertTrue("Debe seguir diciendo qué hacer", out.contains("Presenta este vale en caja"))
    }

    @Test
    fun `el peso del granel se imprime bajo el nombre`() {
        val out = render(ticket())
        assertTrue(out.contains("LOMO CANADIENSE"))
        assertTrue("El peso es lo que el cliente compara con la báscula", out.contains("0.224 kg"))
        assertTrue(out.contains("164.00/kg"))
    }

    @Test
    fun `el area es el titulo — el cliente trae tres vales en la mano`() {
        assertTrue(render(ticket()).contains("CREMERÍA"))
    }

    // MARK: - Configurable por venue (§5.3)

    @Test
    fun `sin precios el vale sigue siendo escaneable y no muestra total`() {
        val out = render(ticket(showPrices = false))
        assertTrue("El código sigue ahí", out.contains(CODE))
        assertTrue(out.contains("LOMO CANADIENSE"))
        assertFalse("No debe filtrar el total", out.contains("TOTAL"))
    }

    @Test
    fun `IMMEDIATE no promete que el area guarda el producto`() {
        val guarda = render(ticket(holdsProduct = true))
        val seLoLleva = render(ticket(holdsProduct = false))
        assertTrue(guarda.contains("Tu producto te espera aquí"))
        assertFalse(
            "Si se lo lleva al momento, prometerle que lo esperamos es mentira",
            seLoLleva.contains("Tu producto te espera aquí"),
        )
    }

    // MARK: - Papel

    @Test
    fun `el vale entra igual en 58 y en 80 mm`() {
        for (paper in listOf(PaperWidth.MM58, PaperWidth.MM80)) {
            val out = render(ticket(), paper)
            assertTrue("Falta el código en $paper", out.contains(CODE))
            assertTrue("Falta el área en $paper", out.contains("CREMERÍA"))
        }
    }

    @Test
    fun `varios renglones de granel salen todos`() {
        val out = render(
            ticket(
                items = listOf(
                    jamon(),
                    ReceiptItem(
                        name = "QUESO MANCHEGO",
                        quantity = 1,
                        unitPrice = 23350,
                        totalPrice = 7145,
                        weightSummary = "0.306 kg × \$233.50/kg",
                    ),
                ),
            ),
        )
        assertTrue(out.contains("LOMO CANADIENSE"))
        assertTrue(out.contains("QUESO MANCHEGO"))
        assertTrue(out.contains("0.306 kg"))
    }

    @Test
    fun `el total suma los renglones al centavo`() {
        // Los dos renglones reales del ticket del cliente (§4.3).
        val t = ticket(
            items = listOf(
                jamon(), // 36.74
                ReceiptItem("QUESO MANCHEGO", 1, 23350, 7145, weightSummary = "0.306 kg"), // 71.45
            ),
        )
        assertEquals(10819, t.totalCents)
        assertEquals("\$108.19", t.formattedTotal)
        assertTrue(render(t).contains("108.19"))
    }
}
