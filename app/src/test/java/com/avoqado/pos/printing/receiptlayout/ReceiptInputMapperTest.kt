package com.avoqado.pos.printing.receiptlayout

import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.tpvsettings.data.ReceiptInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * `ReceiptData` (lo que arman las pantallas) → `ReceiptInput` (lo que entiende el intérprete).
 * Es el único lugar donde se decide qué tipo de pago es y de dónde sale el emisor.
 */
class ReceiptInputMapperTest {

    private fun receipt(
        paymentMethod: String? = "Efectivo",
        cardLastFour: String? = null,
        cardBrand: String? = null,
        cashTendered: Int? = 10000,
        changeAmount: Int? = 5500,
        reprintedAt: Date? = null,
        legalName: String? = null,
    ) = ReceiptData(
        orderNumber = "42",
        orderType = "En tienda",
        items = listOf(ReceiptItem(name = "Galleta", quantity = 2, unitPrice = 2250, totalPrice = 4500, isComboHeader = true)),
        subtotal = 4500,
        taxAmount = 621,
        tipAmount = 500,
        discountAmount = 300,
        total = 4700,
        paymentMethod = paymentMethod,
        cardLastFour = cardLastFour,
        cardBrand = cardBrand,
        venueName = "Testarudo Cafe",
        cashierName = "Ana",
        date = Date(1_788_372_300_000L),
        transactionId = "pay_1",
        cashTendered = cashTendered,
        changeAmount = changeAmount,
        receiptUrl = "https://r/x",
        autofacturaAvailable = true,
        areaDeliveryCode = "9016719357",
        venueLegalName = legalName,
        venueRfc = legalName?.let { "TCA2501231A6" },
        reprintedAt = reprintedAt,
    )

    private val info = ReceiptInfo(
        address = "Nápoles 47", city = "Cuauhtémoc", state = "Ciudad de México", zipCode = "06600", phone = "5512345678",
        fiscalEmisors = listOf(ReceiptFiscalEmisor(id = "emA", legalName = "TESTARUDO CAFE", rfc = "TCA2501231A6", merchantAccountIds = listOf("maA"))),
        principalEmisorId = "emA",
        legacy = ReceiptLegacyFiscal("Viejo SA", "VIE010101VIE"),
    )

    private fun map(r: ReceiptData, i: ReceiptInfo? = info) =
        ReceiptInputMapper.map(r, i, hasLogo = true, timezone = "America/Mexico_City", appVersion = "2.19.0")

    @Test
    fun `P1 los centavos pasan tal cual - sin aritmetica de dinero`() {
        val s = map(receipt()).sale
        assertEquals(4500L, s.subtotalCents)
        assertEquals(621L, s.taxCents)
        assertEquals(300L, s.discountCents)
        assertEquals(500L, s.tipCents)
        assertEquals(4700L, s.totalCents)
        assertEquals(4500L, s.items.single().totalPriceCents)
        assertEquals(true, s.items.single().isComboHeader)
    }

    @Test
    fun `P1 efectivo lleva recibido y cambio`() {
        val t = map(receipt()).sale.tender!!
        assertEquals("CASH", t.kind)
        assertEquals(10000L, t.tenderedCents)
        assertEquals(5500L, t.changeCents)
    }

    @Test
    fun `P1 tarjeta lleva marca y ultimos 4 - sin autorizacion ni referencia que el POS no tiene`() {
        val t = map(receipt(paymentMethod = "Tarjeta", cardLastFour = "4242", cardBrand = "VISA", cashTendered = null, changeAmount = null)).sale.tender!!
        assertEquals("CARD", t.kind)
        assertEquals("VISA", t.cardBrand)
        assertEquals("4242", t.cardLastFour)
        assertNull(t.authCode)
        assertNull(t.tenderedCents)
    }

    @Test
    fun `P1 pre-cuenta - sin metodo de pago no hay tender`() {
        assertNull(map(receipt(paymentMethod = null)).sale.tender)
    }

    @Test
    fun `otro metodo es OTHER con su etiqueta`() {
        val t = map(receipt(paymentMethod = "Transferencia", cashTendered = null, changeAmount = null)).sale.tender!!
        assertEquals("OTHER", t.kind)
        assertEquals("Transferencia", t.label)
    }

    @Test
    fun `P1 la fecha es la de la venta y la reimpresion lleva la suya`() {
        val s = map(receipt(reprintedAt = Date(1_788_380_000_000L))).sale
        assertEquals("2026-09-02T18:05:00Z", s.occurredAt)
        assertEquals("2026-09-02T20:13:20Z", s.reprint?.printedAt)
        assertNull(map(receipt()).sale.reprint)
    }

    @Test
    fun `P1 el venue sale del cache de settings - emisores, principal, legacy y direccion en partes`() {
        val v = map(receipt()).venue
        assertEquals("Testarudo Cafe", v.name)
        assertEquals("Cuauhtémoc", v.city)
        assertEquals("emA", v.principalEmisorId)
        assertEquals("TESTARUDO CAFE", v.fiscalEmisors.single().legalName)
        assertEquals("VIE010101VIE", v.legacy.rfc)
        assertEquals(true, v.hasLogo)
    }

    @Test
    fun `lo que la pantalla ya puso en ReceiptData gana al cache`() {
        val v = map(receipt(legalName = "PUESTO POR LA PANTALLA")).venue
        assertEquals("PUESTO POR LA PANTALLA", v.fiscalEmisors.single().legalName)
    }

    @Test
    fun `sin cache de settings el venue queda con lo que trae ReceiptData`() {
        val v = map(receipt(), i = null).venue
        assertEquals(emptyList<ReceiptFiscalEmisor>(), v.fiscalEmisors)
        assertNull(v.address)
    }

    @Test
    fun `la venta trae url, autofactura, vale, cajero, version y zona`() {
        val s = map(receipt()).sale
        assertEquals("https://r/x", s.receiptUrl)
        assertEquals(true, s.autofacturaAvailable)
        assertEquals("9016719357", s.areaDeliveryCode)
        assertEquals("Ana", s.staffName)
        assertEquals("2.19.0", s.appVersion)
        assertEquals("America/Mexico_City", s.timezone)
    }
}
