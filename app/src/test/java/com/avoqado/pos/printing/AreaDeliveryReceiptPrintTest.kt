package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaDeliveryReceiptPrintTest {

    private fun receipt(deliveryCode: String? = null) = ReceiptData(
        orderNumber = "A-101",
        orderType = "En tienda",
        items = listOf(
            ReceiptItem(
                name = "Jamón",
                quantity = 1,
                unitPrice = 100,
                totalPrice = 100,
                areaSourceLabel = deliveryCode?.let { "Cremería · Vale 9016719357" },
            ),
        ),
        subtotal = 100,
        taxAmount = 0,
        total = 100,
        paymentMethod = "Efectivo",
        venueName = "Abarrotes El Sol",
        areaDeliveryCode = deliveryCode,
    )

    private fun renderBytes(receipt: ReceiptData): ByteArray =
        ESCPOSPrinter(PaperWidth.MM58).generateReceipt(receipt)

    private fun render(receipt: ReceiptData): String =
        String(renderBytes(receipt), Charsets.ISO_8859_1)

    @Test
    fun `recibo pagado de vales imprime codigo de entrega en barras y texto`() {
        val code = "8427993264"
        val paidReceipt = receipt(code)
        val output = render(paidReceipt)

        assertTrue(output.contains("ENTREGA POR ÁREA"))
        assertTrue(output.contains("Presenta este comprobante en el área"))
        assertTrue(output.contains("Cremería · Vale 9016719357"))
        assertTrue("El código debe poder teclearse si la pistola falla", output.contains(code))
        assertTrue(
            "Debe incluir la orden ESC POS GS k para CODE128",
            renderBytes(paidReceipt)
                .toList()
                .windowed(3)
                .any { it == listOf(0x1D.toByte(), 0x6B.toByte(), 0x49.toByte()) },
        )
    }

    @Test
    fun `recibo normal no muestra instrucciones de entrega`() {
        val output = render(receipt())

        assertFalse(output.contains("ENTREGA POR ÁREA"))
        assertFalse(output.contains("Presenta este comprobante en el área"))
        assertFalse(output.contains("Cremería · Vale"))
    }
}
