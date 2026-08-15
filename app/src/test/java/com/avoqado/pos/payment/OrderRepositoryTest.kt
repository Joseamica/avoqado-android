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

    // MARK: - inventoryWarning extraction (toast ámbar post-cobro, Square-parity)

    @Test
    fun `extractInventoryWarningMessageFromResponse reads payment inventoryWarning shape`() {
        val body = """
            {
              "success": true,
              "payment": {
                "paymentId": "cm9cashorderpayment456",
                "inventoryWarning": {
                  "code": "INSUFFICIENT_INVENTORY",
                  "inventoryDeducted": true,
                  "message": "El cobro quedó registrado. Cerveza Corona quedó en negativo."
                }
              }
            }
        """.trimIndent()

        val message = OrderRepository.extractInventoryWarningMessageFromResponse(body)
        assertEquals("El cobro quedó registrado. Cerveza Corona quedó en negativo.", message)
    }

    @Test
    fun `extractInventoryWarningMessageFromResponse returns null when warning is absent`() {
        val body = """
            {
              "success": true,
              "payment": { "paymentId": "cm9cashorderpayment456" }
            }
        """.trimIndent()

        assertEquals(null, OrderRepository.extractInventoryWarningMessageFromResponse(body))
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
    fun `buildCreateOrderPayload emits weightQuantity and forces quantity 1 for weighted lines`() {
        val request = CreateOrderRequest(
            items = listOf(
                OrderItemRequest(
                    productId = "jamon_serrano",
                    name = "Jamón serrano",
                    quantity = 1,
                    unitPrice = 42000, // precio por kg (el server lo ignora en líneas con productId)
                    weightQuantity = 0.435,
                ),
            ),
            subtotal = 18270,
            tip = 0,
            total = 18270,
            paymentMethod = "CASH",
        )

        val payload = OrderRepository.buildCreateOrderPayload(request = request, staffId = "staff_1")

        assertTrue(payload.contains("\"productId\":\"jamon_serrano\""))
        assertTrue(payload.contains("\"weightQuantity\":0.435"))
        assertTrue(payload.contains("\"quantity\":1"))
        // Nunca se manda precio en líneas con productId (el server recalcula desde price/kg).
        assertFalse(payload.contains("\"unitPrice\""))
    }

    @Test
    fun `buildCreateOrderPayload omits weightQuantity for normal product lines`() {
        val request = CreateOrderRequest(
            items = listOf(
                OrderItemRequest(
                    productId = "burger",
                    name = "Hamburguesa",
                    quantity = 3,
                    unitPrice = 15000,
                ),
            ),
            subtotal = 45000,
            tip = 0,
            total = 45000,
            paymentMethod = "CASH",
        )

        val payload = OrderRepository.buildCreateOrderPayload(request = request, staffId = "staff_1")

        assertFalse(payload.contains("weightQuantity"))
        assertTrue(payload.contains("\"quantity\":3"))
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

    @Test
    fun `buildCreateOrderPayload includes stable externalId for safe retry`() {
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
            paymentMethod = "CASH",
        )

        val payload = OrderRepository.buildCreateOrderPayload(
            request = request,
            staffId = "staff_123",
            externalId = "order-session-789",
        )

        assertTrue(payload.contains("\"externalId\":\"order-session-789\""))
    }

    // MARK: - 4xx de infraestructura vs 4xx de negocio

    @Test
    fun `un 404 de tunel caido NO es rechazo de negocio`() {
        // 🔴 Reproducido en la T3 el 2026-08-09: con el túnel abajo, ngrok
        // contestó 404 y el cobro encolado se marcó FAILED permanente con el
        // texto "la orden ya no existe". El efectivo YA estaba en el cajón y la
        // venta nunca llegó al server: el corte no cuadra al cierre y nadie
        // sabe por qué. En un local real el mismo papel lo hace el portal
        // cautivo del WiFi de la plaza.
        assertTrue(OrderRepository.isTransient4xx(404, "text/html", "ERR_NGROK_3200", "<html>..."))
    }

    @Test
    fun `una pagina HTML de proxy NO es rechazo de negocio`() {
        assertTrue(OrderRepository.isTransient4xx(403, "text/html; charset=utf-8", null, "<!DOCTYPE html>"))
    }

    @Test
    fun `un cuerpo que no es JSON NO puede ser un rechazo de nuestra API`() {
        assertTrue(OrderRepository.isTransient4xx(400, "text/plain", null, "Blocked by proxy"))
        assertTrue(OrderRepository.isTransient4xx(400, null, null, ""))
    }

    @Test
    fun `408 y 429 son transitorios por definicion`() {
        assertTrue(OrderRepository.isTransient4xx(408, "application/json", null, "{}"))
        assertTrue(OrderRepository.isTransient4xx(429, "application/json", null, "{}"))
    }

    @Test
    fun `un rechazo REAL de la API sigue yendo a cuarentena`() {
        // Lo que NO debe cambiar: "Order is already paid" es negocio y tiene que
        // seguir siendo permanente, o el cobro se reintentaría para siempre.
        val json = """{"message":"Order is already paid","errorName":"Error"}"""
        assertFalse(OrderRepository.isTransient4xx(400, "application/json; charset=utf-8", null, json))
        assertFalse(OrderRepository.isTransient4xx(403, "application/json", null, """{"message":"Sin permiso"}"""))
        assertFalse(OrderRepository.isTransient4xx(404, "application/json", null, """{"message":"Order not found"}"""))
    }

    @Test
    fun `los 5xx no pasan por esta regla, ya eran reintentables`() {
        assertFalse(OrderRepository.isTransient4xx(500, "text/html", "ERR", "<html>"))
        assertFalse(OrderRepository.isTransient4xx(200, "application/json", null, "{}"))
    }
}
