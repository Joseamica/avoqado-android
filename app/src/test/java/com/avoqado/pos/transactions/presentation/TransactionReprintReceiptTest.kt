package com.avoqado.pos.transactions.presentation

import com.avoqado.pos.transactions.data.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class TransactionReprintReceiptTest {

    private val cuando = Date(1_788_380_000_000L)

    @Test
    fun `P1 la reimpresion lleva su hora y conserva la tarjeta como marca y ultimos 4`() {
        val tx = Transaction(id = "pay_123456", method = "CARD", amount = 45.0, cardBrand = "VISA", maskedPan = "411111******4242")
        val r = tx.toReceiptData(venueName = "Testarudo", reprintedAt = cuando)
        assertEquals(cuando, r.reprintedAt)
        assertEquals("Tarjeta", r.paymentMethod)
        assertEquals("VISA", r.cardBrand)
        assertEquals("4242", r.cardLastFour)
    }

    @Test
    fun `efectivo sigue siendo Efectivo y sin datos de tarjeta`() {
        val r = Transaction(id = "pay_1", method = "CASH", amount = 10.0).toReceiptData(venueName = "T", reprintedAt = cuando)
        assertEquals("Efectivo", r.paymentMethod)
        assertNull(r.cardLastFour)
        assertNull(r.cardBrand)
    }
}
