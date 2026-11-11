package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.ComprobanteDeRetiro
import com.avoqado.pos.cashdrawer.data.RetiroTicketBuilder
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que sale IMPRESO en el comprobante del retiro: los bytes ESC/POS decodificados, como en `CorteTicketBuilderTest`. */
class RetiroTicketBuilderTest {

    private val base = ComprobanteDeRetiro(
        venueId = "v1", localId = "l1", venueName = "Restaurante El Atole",
        nombreDelAparato = "Windows 10 (2)", fechaTexto = "10/11/2026", horaTexto = "16:42",
        concepto = "Bajada a caja fuerte", amountCents = 500_000,
        hizo = "Ana", rolDeQuienHizo = "CASHIER", autorizadoPor = "Daniel",
    )

    private fun papel(
        c: ComprobanteDeRetiro = base,
        reimpresion: Boolean = false,
        ancho: PaperWidth = PaperWidth.MM80,
    ) = String(RetiroTicketBuilder.build(c, reimpresion, ancho), Charsets.ISO_8859_1)

    @Test
    fun `trae todo en orden`() {
        val t = papel()
        val orden = listOf(
            "Restaurante El Atole", "RETIRO DE EFECTIVO", "10/11/2026", "16:42", "Caja: Windows 10 (2)",
            "Concepto:", "Bajada a caja fuerte", "Importe:", "\$5,000.00", "Lo hizo:", "Ana (Cajero)",
            "Autorizado por:", "Daniel", "Firma de quien entrega", "Firma de quien recibe",
        )
        var desde = 0
        for (s in orden) {
            val i = t.indexOf(s, desde)
            assertTrue("falta o está fuera de orden: $s", i >= 0)
            desde = i + s.length
        }
        assertFalse(t.contains("REIMPRESIÓN"))
    }

    @Test
    fun `sin aparato no hay Caja`() = assertFalse(papel(base.copy(nombreDelAparato = null)).contains("Caja:"))

    @Test
    fun `reimpresion lo dice bajo el titulo`() {
        val t = papel(reimpresion = true)
        assertTrue(t.indexOf("RETIRO DE EFECTIVO") < t.indexOf("REIMPRESIÓN"))
        assertTrue(t.indexOf("REIMPRESIÓN") < t.indexOf("10/11/2026"))
    }

    @Test
    fun `sin rol no hay parentesis`() {
        val t = papel(base.copy(rolDeQuienHizo = null))
        assertTrue(t.contains("Ana"))
        assertFalse(t.contains("Ana ("))
    }

    @Test
    fun `roles en espanol`() {
        assertTrue(papel(base.copy(rolDeQuienHizo = "MANAGER")).contains("Ana (Gerente)"))
        assertTrue(papel(base.copy(rolDeQuienHizo = "CASHIER")).contains("Ana (Cajero)"))
    }

    @Test
    fun `acentos y enie en windows 1252`() {
        val t = papel(base.copy(venueName = "Café Ñandú", concepto = "Depósito"))
        assertTrue(t.contains("Café Ñandú"))
        assertTrue(t.contains("Depósito"))
    }

    @Test
    fun `ningun renglon pasa del ancho`() {
        for (ancho in listOf(PaperWidth.MM58, PaperWidth.MM80)) {
            val t = papel(base.copy(concepto = "Z".repeat(100), hizo = "Q".repeat(60)), ancho = ancho)
            val corridaA = Regex("Z+").findAll(t).maxOf { it.value.length }
            val corridaB = Regex("Q+").findAll(t).maxOf { it.value.length }
            assertTrue(corridaA <= ancho.charsPerLine)
            assertTrue(corridaB <= ancho.charsPerLine)
            assertEquals(100, Regex("Z").findAll(t).count())
        }
    }
}
