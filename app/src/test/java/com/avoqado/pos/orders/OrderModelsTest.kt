package com.avoqado.pos.orders

import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.data.model.OrderPage
import com.avoqado.pos.orders.data.model.OrderPaymentInfo
import com.avoqado.pos.orders.data.model.OrderStatus
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderModelsTest {

    // MARK: - OrderSummary

    @Test
    fun `orderStatus parses COMPLETED correctly`() {
        val order = OrderSummary(id = "1", status = "COMPLETED")
        assertEquals(OrderStatus.COMPLETED, order.orderStatus)
    }

    @Test
    fun `orderStatus defaults to PENDING for unknown status`() {
        val order = OrderSummary(id = "1", status = "UNKNOWN_STATUS")
        assertEquals(OrderStatus.PENDING, order.orderStatus)
    }

    @Test
    fun `orderPaymentStatus parses PAID correctly`() {
        val order = OrderSummary(id = "1", paymentStatus = "PAID")
        assertEquals(PaymentStatus.PAID, order.orderPaymentStatus)
    }

    @Test
    fun `formattedTotal formats with dollar sign and commas`() {
        val order = OrderSummary(id = "1", total = 1234.56)
        assertEquals("$1,234.56", order.formattedTotal)
    }

    // MARK: - OrderPaymentInfo

    @Test
    fun `payment methodLabel maps CASH to Efectivo`() {
        val payment = OrderPaymentInfo(id = "1", method = "CASH")
        assertEquals("Efectivo", payment.methodLabel)
    }

    @Test
    fun `payment methodLabel maps CARD to Tarjeta`() {
        val payment = OrderPaymentInfo(id = "1", method = "CARD")
        assertEquals("Tarjeta", payment.methodLabel)
    }

    // MARK: - OrderPage

    @Test
    fun `hasMore is true when page less than pageCount`() {
        val page = OrderPage(page = 1, pageCount = 3)
        assertTrue(page.hasMore)
    }

    @Test
    fun `hasMore is false when page equals pageCount`() {
        val page = OrderPage(page = 3, pageCount = 3)
        assertFalse(page.hasMore)
    }

    // MARK: - OrderDetail

    @Test
    fun `order detail has same computed properties as summary`() {
        val detail = OrderDetail(id = "1", status = "COMPLETED", paymentStatus = "PAID", total = 99.50)
        assertEquals(OrderStatus.COMPLETED, detail.orderStatus)
        assertEquals(PaymentStatus.PAID, detail.orderPaymentStatus)
        assertEquals("$99.50", detail.formattedTotal)
    }
}
