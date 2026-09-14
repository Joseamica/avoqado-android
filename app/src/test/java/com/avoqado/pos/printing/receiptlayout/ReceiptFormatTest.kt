package com.avoqado.pos.printing.receiptlayout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Valores sacados de `format.ts` y `resolveEmisor.ts` del servidor (11-sep). */
class ReceiptFormatTest {

    @Test
    fun `P1 money formatea centavos sin aritmetica de dinero`() {
        assertEquals("\$1,234.50", ReceiptFormat.money(123450))
        assertEquals("-\$5.00", ReceiptFormat.money(-500))
        assertEquals("\$0.05", ReceiptFormat.money(5))
        assertEquals("\$1,000,000.00", ReceiptFormat.money(100_000_000))
    }

    @Test
    fun `P1 la fecha es la de la VENTA en la zona del venue, no la del aparato`() {
        assertEquals("02/09/2026 12:05", ReceiptFormat.dateTime("2026-09-02T18:05:00.000Z", "America/Mexico_City"))
        assertEquals("02/09/2026 11:05", ReceiptFormat.dateTime("2026-09-02T18:05:00Z", "America/Tijuana"))
        assertEquals("31/12/2025 23:59", ReceiptFormat.dateTime("2026-01-01T05:59:00.000Z", "America/Mexico_City"))
    }

    @Test
    fun `amountInWords es el de la PAX`() {
        assertEquals("MIL DOSCIENTOS TREINTA Y CUATRO PESOS 50/100 M.N.", ReceiptFormat.amountInWords(123450))
        assertEquals("UN PESO 00/100 M.N.", ReceiptFormat.amountInWords(100))
        assertEquals("UN MILLÓN DE PESOS 00/100 M.N.", ReceiptFormat.amountInWords(100_000_000))
        assertEquals("CERO PESOS 00/100 M.N.", ReceiptFormat.amountInWords(0))
        assertEquals("VEINTIÚN MILLONES MIL QUINIENTOS PESOS 99/100 M.N.", ReceiptFormat.amountInWords(2_100_150_099))
    }

    private val a = ReceiptFiscalEmisor(id = "emA", legalName = "A SA", rfc = "AAA010101AAA", lugarExpedicion = "06600", merchantAccountIds = listOf("maA"))
    private val b = ReceiptFiscalEmisor(id = "emB", legalName = "B SA", rfc = "BBB010101BBB", lugarExpedicion = null, merchantAccountIds = listOf("maB"))
    private fun venue(emisors: List<ReceiptFiscalEmisor>, principal: String?, legacy: ReceiptLegacyFiscal = ReceiptLegacyFiscal()) =
        ReceiptVenueInfo(name = "V", hasLogo = false, fiscalEmisors = emisors, principalEmisorId = principal, legacy = legacy)

    @Test
    fun `P1 emisor - el del merchant que cobro, luego el principal, luego el legacy`() {
        assertEquals("BBB010101BBB", FiscalEmisorResolver.resolve(venue(listOf(a, b), "emA"), "maB")?.rfc)
        assertEquals("AAA010101AAA", FiscalEmisorResolver.resolve(venue(listOf(b, a), "emA"), null)?.rfc)
        assertEquals("BBB010101BBB", FiscalEmisorResolver.resolve(venue(listOf(b, a), null), "desconocido")?.rfc)
        assertEquals("LEG010101LEG", FiscalEmisorResolver.resolve(venue(emptyList(), null, ReceiptLegacyFiscal("Legacy SA", "LEG010101LEG")), null)?.rfc)
        assertNull(FiscalEmisorResolver.resolve(venue(emptyList(), null), null))
    }
}
