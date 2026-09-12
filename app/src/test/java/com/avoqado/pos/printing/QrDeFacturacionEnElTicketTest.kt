package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.leyendaDelQr
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El QR de facturación del ticket — y la leyenda que lo acompaña.
 *
 * 🔴 Origen: Asana «POS - Reimpresion de Ticket sin QR de facturacion» (11-sep-2026). El ticket del
 * COBRO llevaba QR desde julio; el de la REIMPRESIÓN nunca, porque el dato no viajaba. Al cerrarlo
 * hay dos cosas que ninguna prueba vigilaba:
 *
 *  1. **Que los bytes del QR salgan de verdad.** Las pruebas del ticket comprobaban el encabezado
 *     fiscal y el logo; ninguna asertaba el QR, así que el bloque podía romperse en silencio.
 *  2. **Que la leyenda no prometa lo que el negocio no puede dar.** Decía siempre «y factura»,
 *     incluso en un venue sin autofacturación: eso manda al cliente a buscar un botón que no existe.
 *
 * Y el caso hermano que ya mordió en avoqado-tpv: una URL VACÍA no puede imprimir la leyenda sola.
 * Ahí el ticket salía con «Escanea para tu recibo y factura» y ningún QR debajo — peor que no decir
 * nada, porque el cliente se queda buscando un código que nunca se imprimió.
 */
class QrDeFacturacionEnElTicketTest {

    private fun receipt(
        receiptUrl: String? = null,
        autofacturaAvailable: Boolean = false,
    ) = ReceiptData(
        orderNumber = "42",
        orderType = "En tienda",
        items = listOf(ReceiptItem(name = "Galleta", quantity = 1, unitPrice = 4500, totalPrice = 4500)),
        subtotal = 4500,
        taxAmount = 0,
        total = 4500,
        venueName = "Testarudo Cafe",
        receiptUrl = receiptUrl,
        autofacturaAvailable = autofacturaAvailable,
    )

    private fun printedText(data: ReceiptData): String =
        String(ESCPOSPrinter(PaperWidth.MM80).generateReceipt(data), Charsets.ISO_8859_1)

    private val URL = "https://dashboardv2.avoqado.io/receipts/public/llave-abc"

    @Test
    fun `P1 con liga del recibo el ticket lleva el QR`() {
        val text = printedText(receipt(receiptUrl = URL, autofacturaAvailable = true))

        assertTrue("la URL va dentro del QR", text.contains(URL))
        assertTrue(text.contains("Escanea para tu recibo y factura"))
    }

    @Test
    fun `P1 sin liga no se imprime ni QR ni leyenda`() {
        val text = printedText(receipt(receiptUrl = null))

        assertFalse(text.contains("Escanea para tu recibo"))
        assertFalse(text.contains("/receipts/public/"))
    }

    @Test
    fun `P1 una liga VACIA no deja la leyenda huerfana`() {
        // El defecto hermano de avoqado-tpv: cadena vacía ⇒ leyenda sin QR debajo.
        val text = printedText(receipt(receiptUrl = "", autofacturaAvailable = true))

        assertFalse("nunca la leyenda sin su QR", text.contains("Escanea para tu recibo"))
    }

    @Test
    fun `P1 sin autofactura la leyenda no promete factura`() {
        val text = printedText(receipt(receiptUrl = URL, autofacturaAvailable = false))

        assertTrue("el QR sale igual: lleva al recibo", text.contains(URL))
        assertTrue(text.contains("Escanea para tu recibo digital"))
        assertFalse("no se promete lo que el negocio no tiene prendido", text.contains("y factura"))
    }

    // MARK: - La regla sola, sin impresora

    @Test
    fun `leyendaDelQr distingue los dos casos`() {
        assertEquals("Escanea para tu recibo y factura", leyendaDelQr(true))
        assertEquals("Escanea para tu recibo digital", leyendaDelQr(false))
    }
}
