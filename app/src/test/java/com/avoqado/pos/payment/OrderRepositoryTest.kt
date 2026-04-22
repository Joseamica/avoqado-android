package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.OrderModifierRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderRepositoryTest {

    // MARK: - isQueueableError tests

    @Test
    fun `UnknownHostException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.net.UnknownHostException()))
    }

    @Test
    fun `ConnectException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.net.ConnectException()))
    }

    @Test
    fun `SocketTimeoutException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.net.SocketTimeoutException()))
    }

    @Test
    fun `IOException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.io.IOException()))
    }

    @Test
    fun `IllegalArgumentException is not queueable`() {
        assertFalse(OrderRepository.isQueueableError(IllegalArgumentException()))
    }

    @Test
    fun `RuntimeException is not queueable`() {
        assertFalse(OrderRepository.isQueueableError(RuntimeException()))
    }

    // MARK: - isQueueableHttpCode tests

    @Test
    fun `500 is queueable`() {
        assertTrue(OrderRepository.isQueueableHttpCode(500))
    }

    @Test
    fun `503 is queueable`() {
        assertTrue(OrderRepository.isQueueableHttpCode(503))
    }

    @Test
    fun `400 is not queueable`() {
        assertFalse(OrderRepository.isQueueableHttpCode(400))
    }

    @Test
    fun `404 is not queueable`() {
        assertFalse(OrderRepository.isQueueableHttpCode(404))
    }

    @Test
    fun `200 is not queueable`() {
        assertFalse(OrderRepository.isQueueableHttpCode(200))
    }

    // MARK: - ServerException

    @Test
    fun `ServerException carries status code`() {
        val ex = OrderRepository.ServerException(503, "Service Unavailable")
        assertEquals(503, ex.code)
        assertEquals("Service Unavailable", ex.message)
    }

    // MARK: - paymentId extraction

    @Test
    fun `extractPaymentIdFromResponse supports fast payment data id shape`() {
        val body = """
            {
              "success": true,
              "data": {
                "id": "cm9fastpaymentid123",
                "method": "CASH"
              }
            }
        """.trimIndent()

        val paymentId = OrderRepository.extractPaymentIdFromResponse(body)
        assertEquals("cm9fastpaymentid123", paymentId)
    }

    @Test
    fun `extractPaymentIdFromResponse supports order pay payment paymentId shape`() {
        val body = """
            {
              "success": true,
              "payment": {
                "paymentId": "cm9cashorderpayment456",
                "orderId": "cm9order123"
              }
            }
        """.trimIndent()

        val paymentId = OrderRepository.extractPaymentIdFromResponse(body)
        assertEquals("cm9cashorderpayment456", paymentId)
    }

    @Test
    fun `extractPaymentIdFromResponse returns null when payment id is missing`() {
        val body = """
            {
              "success": true,
              "data": {
                "orderId": "cm9order123"
              }
            }
        """.trimIndent()

        val paymentId = OrderRepository.extractPaymentIdFromResponse(body)
        assertEquals(null, paymentId)
    }

    @Test
    fun `buildCreateOrderPayload keeps only product items and expected fields`() {
        val request = CreateOrderRequest(
            items = listOf(
                OrderItemRequest(
                    productId = "prod_123",
                    name = "Hamburguesa",
                    quantity = 2,
                    unitPrice = 15900,
                    modifiers = listOf(
                        OrderModifierRequest(
                            modifierId = "mod_cheese",
                            name = "Queso",
                            price = 1500,
                        ),
                    ),
                    note = "Sin cebolla",
                ),
                OrderItemRequest(
                    productId = null,
                    name = "Custom amount",
                    quantity = 1,
                    unitPrice = 5000,
                ),
            ),
            subtotal = 36800,
            tip = 0,
            total = 36800,
            paymentMethod = "CARD",
        )

        val payload = OrderRepository.buildCreateOrderPayload(
            request = request,
            staffId = "staff_123",
        )

        assertTrue(payload.contains("\"staffId\":\"staff_123\""))
        assertTrue(payload.contains("\"orderType\":\"TAKEOUT\""))
        assertTrue(payload.contains("\"source\":\"AVOQADO_ANDROID\""))
        assertTrue(payload.contains("\"productId\":\"prod_123\""))
        assertTrue(payload.contains("\"quantity\":2"))
        assertTrue(payload.contains("\"modifierIds\":[\"mod_cheese\"]"))
        assertTrue(payload.contains("\"notes\":\"Sin cebolla\""))
        assertTrue(payload.contains("\"name\":\"Custom amount\""))
        assertTrue(payload.contains("\"unitPrice\":5000"))
        assertFalse(payload.contains("\"paymentMethod\""))
    }

    @Test
    fun `buildCreateOrderPayload includes customerId when provided`() {
        val request = CreateOrderRequest(
            items = listOf(
                OrderItemRequest(
                    productId = "prod_123",
                    name = "Hamburguesa",
                    quantity = 1,
                    unitPrice = 15900,
                ),
            ),
            subtotal = 15900,
            tip = 0,
            total = 15900,
            paymentMethod = "PAY_LATER",
        )

        val payload = OrderRepository.buildCreateOrderPayload(
            request = request,
            staffId = "staff_123",
            customerId = "customer_789",
        )

        assertTrue(payload.contains("\"customerId\":\"customer_789\""))
    }
}
