package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.di.registroDeRed
import com.avoqado.pos.payment.Q16PrecioCapturadoTest.Companion.VENUE
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.pos.data.model.AutorizacionDelDescuento
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.DESCUENTO_DEL_CLIENTE_SOURCE
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 (descuento del cliente, spec v3 puntos 15-16): la venta manda el descuento del cliente como lo que es, un descuento de cuenta
 * ya llenado (`discount` en ¢), MÁS su etiqueta `descuentoDelCliente {discountId, montoCents}` y, si hubo PIN de gerente,
 * `autorizacionDelDescuento`, todo en el CUERPO: nunca en `X-Permission-Override` ni en la llave (`externalId`). Y nunca va por
 * FAST: el cobro rápido no lleva descuento ni etiqueta, así que el SERVIDOR no sabría de quién fue el descuento ni lo autorizaría.
 * Un descuento manual de hoy arma el cuerpo exactamente igual que antes.
 */
class OrderPayloadDescuentoDelClienteTest {

    private val playera = OrderItemRequest(productId = "playera", name = "Playera", quantity = 1, unitPrice = 10000)
    private val importe = OrderItemRequest(productId = null, name = "Otro importe", quantity = 1, unitPrice = 10000)

    private fun pedido(
        items: List<OrderItemRequest> = listOf(playera),
        descuento: Int = 1000,
        delCliente: String? = "d-10",
        token: String? = null,
    ) = CreateOrderRequest(
        items = items,
        subtotal = 10000,
        discount = descuento,
        total = 10000 - descuento,
        paymentMethod = "CASH",
        descuentoDelClienteId = delCliente,
        autorizacionDelDescuento = token,
    )

    private fun cuerpo(request: CreateOrderRequest): JsonObject = Json.parseToJsonElement(
        OrderRepository.buildCreateOrderPayload(request = request, staffId = "s-1", customerId = "c-cecilia", externalId = "venta-1"),
    ).jsonObject

    // MARK: - 7a · el cuerpo

    @Test
    fun `7a el cuerpo trae discount, la etiqueta con su monto y el token`() {
        val c = cuerpo(pedido(token = "tok-1"))

        assertEquals(1000, c["discount"]!!.jsonPrimitive.int)
        val etiqueta = c["descuentoDelCliente"]!!.jsonObject
        assertEquals(setOf("discountId", "montoCents"), etiqueta.keys)
        assertEquals("d-10", etiqueta["discountId"]!!.jsonPrimitive.content)
        assertEquals("montoCents es el mismo discount (el SERVIDOR da 400 si no)", 1000, etiqueta["montoCents"]!!.jsonPrimitive.int)
        assertEquals("tok-1", c["autorizacionDelDescuento"]!!.jsonPrimitive.content)
        assertEquals("c-cecilia", c["customerId"]!!.jsonPrimitive.content)
        assertNull("nunca el camino del catálogo (recalcula)", c["orderDiscountId"])
        assertNull(c["couponCode"])
    }

    @Test
    fun `7a sin token no viaja autorizacionDelDescuento y el resto del cuerpo, la llave incluida, es identico`() {
        val conToken = cuerpo(pedido(token = "tok-1"))
        val sinToken = cuerpo(pedido(token = null))

        assertNull(sinToken["autorizacionDelDescuento"])
        assertNull(cuerpo(pedido(token = " "))["autorizacionDelDescuento"])
        assertEquals("venta-1", sinToken["externalId"]!!.jsonPrimitive.content)
        // El token sólo agrega su llave: no toca el `externalId` ni nada más del cuerpo.
        assertEquals(JsonObject(conToken - "autorizacionDelDescuento"), sinToken)
    }

    @Test
    fun `7a createOrder lleva el token en el cuerpo y nunca en X-Permission-Override`() = runBlocking {
        conRepositorio { server, repositorio ->
            server.enqueue(creada())
            assertTrue(repositorio.createOrder(pedido(token = "tok-1"), "staff-A", customerId = "c-cecilia", externalId = "venta-1").isSuccess)
            val sinPin = server.takeRequest()
            assertNull(sinPin.getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
            val cuerpoSinPin = JSONObject(sinPin.body.readUtf8())
            assertEquals("tok-1", cuerpoSinPin.getString("autorizacionDelDescuento"))
            assertEquals("venta-1", cuerpoSinPin.getString("externalId"))

            // Con el PIN de la cortesía (otro permiso, `orders:comp`), el encabezado lleva ESE y el del descuento sigue en el cuerpo.
            server.enqueue(creada())
            assertTrue(
                repositorio.createOrder(
                    pedido(token = "tok-1"), "staff-A", customerId = "c-cecilia", externalId = "venta-1", permisoDeGerente = "pin-cortesia",
                ).isSuccess,
            )
            val conPin = server.takeRequest()
            assertEquals("pin-cortesia", conPin.getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
            assertEquals("tok-1", JSONObject(conPin.body.readUtf8()).getString("autorizacionDelDescuento"))
        }
    }

    /**
     * El token vive 7 días y ahora viaja en el CUERPO: el log de red de depuración (nivel BODY, `registroDeRed`) lo taparía en el
     * encabezado pero no aquí. Pasa por el cliente del dinero (crear la venta) y por el de «Pagar después», que nacen del compartido.
     */
    @Test
    fun `7a el log de depuracion nunca copia el token del descuento, y el servidor si lo recibe`() = runBlocking {
        val lineas = mutableListOf<String>()
        val conLog = OkHttpClient.Builder().addInterceptor(registroDeRed(depuracion = true) { lineas += it }).build()
        conRepositorio(conLog) { server, repositorio ->
            server.enqueue(creada())
            server.enqueue(creada())
            repositorio.createOrder(pedido(token = "tok-secreto"), "staff-A", customerId = "c-cecilia", externalId = "venta-1")
            repositorio.enviarPagarDespues(
                "venue-A",
                OrderRepository.buildCreateOrderPayload(pedido(token = "tok-secreto"), "staff-A", "c-cecilia", externalId = "pay-later:1"),
            )

            repeat(2) { assertTrue("el cuerpo sí lleva el token", "tok-secreto" in server.takeRequest().body.readUtf8()) }
            assertTrue("no se registró ningún cuerpo", lineas.count { "\"autorizacionDelDescuento\"" in it } == 2)
            assertFalse(lineas.joinToString("\n"), lineas.any { "tok-secreto" in it })
        }
    }

    @Test
    fun `7a el carrito dice que etiqueta y que token viajan`() {
        val linea = CartItem(id = "l", type = CartItemType.ProductItem("playera"), name = "Playera", unitPrice = 10000)
        val delCliente = Discount(id = "d-10", name = "Cliente frecuente", value = 10.0, source = DESCUENTO_DEL_CLIENTE_SOURCE)
        val pin = AutorizacionDelDescuento(token = "tok-1", topeCents = 1000, customerId = "c-cecilia", discountId = "d-10")
        val carrito = CartState(items = listOf(linea), orderDiscount = delCliente, autorizacionDelDescuento = pin)

        assertEquals("d-10", carrito.descuentoDelClienteQueViaja)
        assertEquals("tok-1", carrito.autorizacionQueViaja)
        // Sin PIN viaja la etiqueta sola (permiso propio).
        assertEquals("d-10", carrito.copy(autorizacionDelDescuento = null).descuentoDelClienteQueViaja)
        assertNull(carrito.copy(autorizacionDelDescuento = null).autorizacionQueViaja)
        // Un PIN de otro descuento no viaja.
        assertNull(carrito.copy(autorizacionDelDescuento = pin.copy(discountId = "d-otro")).autorizacionQueViaja)
        // Sin monto no hay nada que etiquetar ni autorizar.
        assertNull(carrito.copy(items = emptyList()).descuentoDelClienteQueViaja)
        assertNull(carrito.copy(items = emptyList()).autorizacionQueViaja)
    }

    // MARK: - 7c · nunca FAST

    @Test
    fun `7c importes con el descuento del cliente siempre llevan orden`() {
        assertTrue(OrderRepository.aceptaCrearOrden(pedido(listOf(importe))))
        // Hoy: importes sin etiqueta (sin descuento o con uno manual) siguen siendo cobro rápido.
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(listOf(importe), descuento = 0, delCliente = null)))
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(listOf(importe), delCliente = null)))
        // Sin monto la etiqueta no viaja: tampoco cambia el camino.
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(listOf(importe), descuento = 0)))
        assertFalse(OrderRepository.aceptaCrearOrden(pedido(emptyList())))
    }

    @Test
    fun `7c createOrder acepta importes con el descuento del cliente y manda la etiqueta`() = runBlocking {
        conRepositorio { server, repositorio ->
            server.enqueue(creada())
            val resultado = repositorio.createOrder(pedido(listOf(importe)), "staff-A", customerId = "c-cecilia", externalId = "venta-1")
            assertTrue(resultado.toString(), resultado.isSuccess)
            val enviado = server.takeRequest()
            assertTrue(enviado.path, enviado.path!!.endsWith("/venues/venue-A/orders"))
            val body = JSONObject(enviado.body.readUtf8())
            assertEquals("Otro importe", body.getJSONArray("items").getJSONObject(0).getString("name"))
            assertEquals(1000, body.getInt("discount"))
            assertEquals(1000, body.getJSONObject("descuentoDelCliente").getInt("montoCents"))
        }
    }

    @Test
    fun `7c sin red la cola guarda una ORDEN con la etiqueta, el token e isOfflineReplay`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).queueCashPayment(pedido(listOf(importe), token = "tok-1"), "staff-A", 9000, 0, null,
                orderExternalId = "venta-1", customerId = "c-cecilia", idempotencyKey = "cobro-1", venueId = VENUE)

            val fila = f.row("cobro-1")
            assertEquals("ORDER", fila["paymentType"])
            comprobarCongelado(JSONObject(JSONObject(fila["original"] as String).getString("orderRequest")))
        }
    }

    @Test
    fun `7c la reserva del efectivo, antes de la red, tambien es ORDEN con la etiqueta`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            CashPaymentRepository(f.storage, f.dao).reservarCobro(pedido(listOf(importe), token = "tok-1"), "staff-A", 9000, 0, null,
                orderExternalId = "venta-1", customerId = "c-cecilia", idempotencyKey = "cobro-1", venueId = VENUE)

            val fila = f.row("cobro-1")
            assertEquals("ORDER", fila["paymentType"])
            comprobarCongelado(JSONObject(JSONObject(fila["original"] as String).getString("orderRequest")))
            assertEquals(0, f.server.requestCount)
        }
    }

    private fun comprobarCongelado(congelado: JSONObject) {
        assertTrue(congelado.getBoolean("isOfflineReplay"))
        assertEquals("venta-1", congelado.getString("externalId"))
        assertEquals("c-cecilia", congelado.getString("customerId"))
        assertEquals(1000, congelado.getInt("discount"))
        val etiqueta = congelado.getJSONObject("descuentoDelCliente")
        assertEquals("d-10", etiqueta.getString("discountId"))
        assertEquals(1000, etiqueta.getInt("montoCents"))
        assertEquals("tok-1", congelado.getString("autorizacionDelDescuento"))
    }

    // MARK: - 7d · regresión

    @Test
    fun `7d un descuento manual arma el cuerpo exactamente como antes`() {
        val manual = pedido(delCliente = null, token = "tok-suelto")
        assertEquals(
            """{"items":[{"productId":"playera","quantity":1}],"staffId":"s-1","orderType":"TAKEOUT","source":"AVOQADO_ANDROID",""" +
                """"externalId":"venta-1","subtotal":10000,"tip":0,"total":9000,"discount":1000,"cortesiaDeCuentaConPin":true,""" +
                """"customerId":"c-cecilia","paymentMethod":"CASH"}""",
            OrderRepository.buildCreateOrderPayload(request = manual, staffId = "s-1", customerId = "c-cecilia", externalId = "venta-1"),
        )
        // Y el carrito con un descuento manual no etiqueta nada aunque guarde un PIN viejo.
        val linea = CartItem(id = "l", type = CartItemType.ProductItem("playera"), name = "Playera", unitPrice = 10000)
        val carrito = CartState(
            items = listOf(linea),
            orderDiscount = Discount(id = "m-5", name = "Manual 5 %", value = 5.0),
            autorizacionDelDescuento = AutorizacionDelDescuento("tok-1", 1000, "c-cecilia", "m-5"),
        )
        assertNull(carrito.descuentoDelClienteQueViaja)
        assertNull(carrito.autorizacionQueViaja)
    }

    private fun creada() = MockResponse().setResponseCode(201)
        .setBody("""{"success":true,"data":{"id":"orden-1","orderNumber":"ORD-7","total":90}}""")

    private suspend fun conRepositorio(cliente: OkHttpClient = OkHttpClient(), block: suspend (MockWebServer, OrderRepository) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<SecureStorage>(relaxed = true)
            every { storage.venueId } returns "venue-A"
            every { storage.accessToken } returns "token-QA"
            val repositorio = OrderRepository(storage, cliente).apply {
                baseUrl = server.url("/api/v1").toString().trimEnd('/')
            }
            block(server, repositorio)
        } finally { server.shutdown() }
    }
}
