package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprobante impreso de una merma, para firmar (nunca automático: lo pide el cajero). Lo que no puede
 * fallar: que diga qué, cuánto, por qué y quién; que una merma guardada SIN red diga «Pendiente de subir»
 * (regla `todo-funciona-sin-red.md`); y el renglón de firma. Espejo de `ComprobanteDeMermaTests.swift`.
 */
class ComprobanteDeMermaTest {

    private val registrada = MermaRegistrada(
        folio = "3f9c2c1e-5b7a-4c1d-9e8f-0a1b2c3d4e5f",
        articulo = "Leche",
        cantidad = "2.5",
        unit = "LITER",
        motivo = "Se echó a perder",
        nota = "se cortó",
        registro = "Ana Pérez",
        creadaEn = 0L,
    )

    private fun papel(c: ComprobanteDeMerma) = ESCPOSPrinter(PaperWidth.MM58).generateWasteReceipt(c).toString(Charsets.ISO_8859_1)

    @Test
    fun `el comprobante lleva folio corto, cantidad con unidad y todo lo capturado`() {
        val c = ComprobanteDeMerma.desde(registrada, negocio = "Testarudo", fecha = "23/09/2026 09:30", pendiente = false)
        assertEquals("3F9C2C1E", c.folio)
        assertEquals("2.5 L", c.cantidad)
        val t = papel(c)
        assertTrue(t.contains("Testarudo"))
        assertTrue(t.contains("COMPROBANTE DE MERMA"))
        assertTrue(t.contains("Folio 3F9C2C1E"))
        assertTrue(t.contains("23/09/2026 09:30"))
        assertTrue(t.contains("Registró: Ana Pérez"))
        assertTrue(t.contains("Artículo: Leche"))
        assertTrue(t.contains("Cantidad: 2.5 L"))
        assertTrue(t.contains("Motivo: Se echó a perder"))
        assertTrue(t.contains("Nota: se cortó"))
        assertTrue(t.contains("Revisó"))
        assertFalse(t.contains("PENDIENTE DE SUBIR"))
    }

    /** 🔴 Guardada sin red: el papel no puede afirmar que ya está en el sistema. */
    @Test
    fun `una merma que sigue en la cola dice pendiente de subir`() {
        val t = papel(ComprobanteDeMerma.desde(registrada, negocio = null, fecha = "x", pendiente = true))
        assertTrue(t.contains("PENDIENTE DE SUBIR"))
    }

    @Test
    fun `sin nota no hay renglon de nota`() {
        val t = papel(ComprobanteDeMerma.desde(registrada.copy(nota = null), negocio = null, fecha = "x", pendiente = false))
        assertFalse(t.contains("Nota:"))
    }
}
