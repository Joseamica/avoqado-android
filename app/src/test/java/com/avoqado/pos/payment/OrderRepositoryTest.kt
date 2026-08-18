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

    // MARK: - remainingBalanceCents extraction (saldo AUTORITATIVO del server)

    /**
     * 🔴 DINERO. El saldo que queda por cobrar lo dice el SERVER, no la
     * aritmética del carrito: `payCashOrder` lo calcula como
     * `total - paidAmount` con la propina ya neteada de los dos lados.
     *
     * 🔴 Llega en CENTAVOS, igual que `amount` y `tipAmount` de esta misma
     * respuesta. NO se multiplica por 100.
     */
    @Test
    fun `extractRemainingBalanceCentsFromResponse lee centavos tal cual`() {
        val body = """
            {
              "success": true,
              "payment": {
                "paymentId": "cm9cashorderpayment456",
                "amount": 5800,
                "tipAmount": 0,
                "orderPaymentStatus": "PARTIAL",
                "orderTotalCents": 10000,
                "totalPaidCents": 5800,
                "remainingBalanceCents": 4200
              }
            }
        """.trimIndent()

        assertEquals(4200, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    @Test
    fun `extractRemainingBalanceCentsFromResponse acepta saldo cero`() {
        val body = """{"success":true,"payment":{"remainingBalanceCents":0}}"""

        // 0 NO es "no vino": es "ya no se debe nada". Distinguirlo de null es lo
        // que deja cerrar la venta en vez de arrastrar un saldo fantasma.
        assertEquals(0, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    /**
     * 🔴 Un sobrepago se ACOTA a 0, no se descarta: descartarlo devolvería null y
     * caería a la aritmética local del carrito, que es la fuente MENOS
     * autoritativa. "Ya no se debe nada" es la lectura correcta de un negativo.
     */
    @Test
    fun `extractRemainingBalanceCentsFromResponse acota un saldo negativo a cero`() {
        val body = """{"success":true,"payment":{"remainingBalanceCents":-350}}"""

        assertEquals(0, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    @Test
    fun `extractRemainingBalanceCentsFromResponse devuelve null en un server viejo`() {
        val body = """
            {
              "success": true,
              "payment": { "paymentId": "cm9cashorderpayment456" }
            }
        """.trimIndent()

        assertEquals(null, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    /**
     * El primer corte del server mandó `remainingBalance` en pesos. Ese contrato
     * NUNCA llegó a producción, así que no se acepta: leerlo como centavos
     * cobraría 100× de menos.
     */
    @Test
    fun `extractRemainingBalanceCentsFromResponse ignora el nombre viejo en pesos`() {
        val body = """{"success":true,"payment":{"remainingBalance":42.00}}"""

        assertEquals(null, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    /**
     * 🔴 El caso que de verdad ejercita el guard `isString` es un string
     * NUMÉRICO: `doubleOrNull` lo parsearía feliz y daríamos por autoritativo un
     * saldo que llegó con el tipo equivocado. Un `"cuatro mil doscientos"` cae
     * solo por `doubleOrNull` y no prueba nada.
     */
    @Test
    fun `extractRemainingBalanceCentsFromResponse rechaza un saldo numerico mandado como string`() {
        val body = """{"success":true,"payment":{"remainingBalanceCents":"4200"}}"""

        assertEquals(null, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    @Test
    fun `extractRemainingBalanceCentsFromResponse ignora un saldo que no es numero`() {
        val body = """{"success":true,"payment":{"remainingBalanceCents":"cuatro mil doscientos"}}"""

        assertEquals(null, OrderRepository.extractRemainingBalanceCentsFromResponse(body))
    }

    @Test
    fun `extractRemainingBalanceCentsFromResponse tambien lo busca en data y en la raiz`() {
        assertEquals(
            4200,
            OrderRepository.extractRemainingBalanceCentsFromResponse("""{"data":{"remainingBalanceCents":4200}}"""),
        )
        assertEquals(
            4200,
            OrderRepository.extractRemainingBalanceCentsFromResponse("""{"remainingBalanceCents":4200}"""),
        )
    }

    @Test
    fun `extractOrderPaymentStatusFromResponse lee el estado de la orden y lo normaliza`() {
        val body = """{"success":true,"payment":{"orderPaymentStatus":"partial"}}"""

        assertEquals("PARTIAL", OrderRepository.extractOrderPaymentStatusFromResponse(body))
    }

    @Test
    fun `extractOrderPaymentStatusFromResponse devuelve null en un server viejo`() {
        val body = """{"success":true,"payment":{"paymentId":"cm9pay"}}"""

        assertEquals(null, OrderRepository.extractOrderPaymentStatusFromResponse(body))
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

    // MARK: - El CLIENTE en el cobro rápido ("Otro importe")
    //
    // 🔴 El cuerpo del cobro rápido se arma A MANO (buildString), no con una data
    // class serializada. Estos tests corren sobre `buildFastPaymentPayload`, que es
    // EXACTAMENTE la función que usa `recordFastCashPayment`: agregar el campo por
    // otro camino (un modelo que nadie serializa aquí) lo dejaría fuera del JSON que
    // de verdad viaja, y el server volvería a registrar la venta sin cliente.

    @Test
    fun `el cobro rapido lleva el customerId que eligio el cajero`() {
        val body = OrderRepository.buildFastPaymentPayload(
            venueId = "venue-1",
            amount = 10000,
            tip = 0,
            splitType = "FULLPAYMENT",
            staffId = "user-456",
            idempotencyKey = "key-1",
            customerId = "cmcustomer123",
        )

        assertTrue(body.contains("\"customerId\":\"cmcustomer123\""))
    }

    @Test
    fun `sin cliente el cuerpo del cobro rapido queda IDENTICO al de hoy`() {
        // Regresión: la venta anónima es el 99% de los cobros. Un campo de más —aunque
        // sea `"customerId":null`— cambia el contrato de TODOS ellos.
        val body = OrderRepository.buildFastPaymentPayload(
            venueId = "venue-1",
            amount = 10000,
            tip = 0,
            splitType = "FULLPAYMENT",
            staffId = "user-456",
            idempotencyKey = "key-1",
            customerId = null,
        )

        assertEquals(
            """{"venueId":"venue-1","amount":10000,"tip":0,"status":"COMPLETED",""" +
                """"method":"CASH","splitType":"FULLPAYMENT","staffId":"user-456",""" +
                """"source":"AVOQADO_ANDROID","idempotencyKey":"key-1"}""",
            body,
        )
        assertFalse(body.contains("customerId"))
    }

    @Test
    fun `un customerId en blanco no viaja - vale como venta anonima`() {
        val body = OrderRepository.buildFastPaymentPayload(
            venueId = "venue-1",
            amount = 10000,
            tip = 0,
            splitType = "FULLPAYMENT",
            staffId = "user-456",
            idempotencyKey = "key-1",
            customerId = "   ",
        )

        assertFalse(body.contains("customerId"))
    }

    @Test
    fun `el cliente convive con el tipo de pago del catalogo`() {
        val body = OrderRepository.buildFastPaymentPayload(
            venueId = "venue-1",
            amount = 10000,
            tip = 0,
            splitType = "FULLPAYMENT",
            staffId = "user-456",
            idempotencyKey = "key-1",
            tenderType = com.avoqado.pos.payment.domain.TenderTypeOption(
                id = "tender-1",
                revision = 3,
                name = "Uber Eats",
                isSystem = false,
                baseMethod = "OTHER",
                captureTip = false,
                posSection = "MORE",
                displayOrder = 1,
            ),
            customerId = "cmcustomer123",
        )

        assertTrue(body.contains("\"tenderTypeId\":\"tender-1\""))
        assertTrue(body.contains("\"customerId\":\"cmcustomer123\""))
        assertFalse(body.contains("\"method\""))
    }

    // MARK: - customerLink de la respuesta (aviso, NUNCA error)

    @Test
    fun `un cliente que no existe en el negocio devuelve el aviso del server`() {
        val body = """
            {
              "success": true,
              "data": {
                "id": "cm9fastpaymentid123",
                "customerLink": {
                  "status": "NOT_FOUND",
                  "customerId": null,
                  "requestedCustomerId": "cmotrovenue999",
                  "warning": "El cliente seleccionado no existe en este negocio. La venta se registró sin cliente."
                }
              }
            }
        """.trimIndent()

        assertEquals(
            "El cliente seleccionado no existe en este negocio. La venta se registró sin cliente.",
            OrderRepository.extractCustomerLinkWarningFromResponse(body),
        )
    }

    @Test
    fun `CONFLICT y UNVERIFIED tambien se avisan`() {
        val conflicto = """
            {"success":true,"data":{"customerLink":{"status":"CONFLICT","customerId":"cmotro","requestedCustomerId":"cmpedido","warning":"La venta ya tenía otro cliente asignado; no se reasignó."}}}
        """.trimIndent()
        val sinVerificar = """
            {"success":true,"data":{"customerLink":{"status":"UNVERIFIED","customerId":null,"requestedCustomerId":"cmpedido","warning":"No se pudo verificar el cliente. La venta se registró sin cliente."}}}
        """.trimIndent()

        assertEquals(
            "La venta ya tenía otro cliente asignado; no se reasignó.",
            OrderRepository.extractCustomerLinkWarningFromResponse(conflicto),
        )
        assertEquals(
            "No se pudo verificar el cliente. La venta se registró sin cliente.",
            OrderRepository.extractCustomerLinkWarningFromResponse(sinVerificar),
        )
    }

    @Test
    fun `el camino feliz NO pinta nada`() {
        val vinculado = """
            {"success":true,"data":{"customerLink":{"status":"LINKED","customerId":"cmcustomer123","requestedCustomerId":"cmcustomer123","warning":null}}}
        """.trimIndent()
        val anonima = """
            {"success":true,"data":{"customerLink":{"status":"NOT_REQUESTED","customerId":null,"requestedCustomerId":null,"warning":null}}}
        """.trimIndent()

        assertEquals(null, OrderRepository.extractCustomerLinkWarningFromResponse(vinculado))
        assertEquals(null, OrderRepository.extractCustomerLinkWarningFromResponse(anonima))
    }

    @Test
    fun `un server viejo sin customerLink no rompe nada`() {
        val body = """{"success":true,"data":{"id":"cm9fastpaymentid123"}}"""
        assertEquals(null, OrderRepository.extractCustomerLinkWarningFromResponse(body))
        assertEquals(null, OrderRepository.extractCustomerLinkWarningFromResponse("no es json"))
    }
}
