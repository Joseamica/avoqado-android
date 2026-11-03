package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.printing.data.model.CopiaDelTicket
import com.avoqado.pos.printing.data.model.ReceiptItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 «fotocopia del ticket» (28-oct): la copia del papel de una parte viaja en el body de `/pay` (bien escapada), se guarda en la fila
 * de la cola ANTES de la red, un reemplazo de la reserva la conserva y el reenvío la manda. Nunca frena el cobro.
 */
class CopiaDelTicketEnElCobroTest {

    private val nombreRaro = "Café \"La Güera\" \\ <b>"
    private val copia = requireNotNull(
        CopiaDelTicket.armar(listOf(ReceiptItem(nombreRaro, 1, 3000, 3000)), subtotalBruto = 3000, cobradoSinPropina = 2700),
    )

    private fun JsonObject.copiaDelBody(): JsonObject = getValue("lineasDelTicket").jsonObject

    // MARK: - El body de `/pay` (OrderRepository.recordCashPayment)

    private fun conServidor(prueba: suspend (MockWebServer, OrderRepository) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val storage = mockk<SecureStorage>(relaxed = true)
            every { storage.venueId } returns "venue-1"
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            prueba(server, OrderRepository(storage, client))
        } finally {
            server.shutdown()
        }
    }

    private fun pagado() = MockResponse().setBody("""{"success":true,"payment":{"id":"pay-1"}}""")

    @Test
    fun `el body de pay lleva la copia como objeto y el nombre con comillas regresa igual`() = conServidor { server, repo ->
        server.enqueue(pagado())
        repo.recordCashPayment("orden-1", amount = 2700, staffId = "staff-1", splitType = "BYPRODUCT", idempotencyKey = "llave-1",
            lineasDelTicket = copia)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(Json.parseToJsonElement(copia), body.copiaDelBody())
        assertEquals(nombreRaro, body.copiaDelBody().getValue("lineas").jsonArray.single().jsonObject.getValue("nombre").jsonPrimitive.content)
        assertEquals("llave-1", body.getValue("idempotencyKey").jsonPrimitive.content)
        assertEquals(2700, body.getValue("amount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `sin copia el body no trae la llave, y una copia rota no viaja pero el cobro sale`() = conServidor { server, repo ->
        server.enqueue(pagado())
        server.enqueue(pagado())
        repo.recordCashPayment("orden-1", amount = 3000, staffId = "staff-1", idempotencyKey = "llave-1")
        assertFalse(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("lineasDelTicket"))
        val rota = repo.recordCashPayment("orden-1", amount = 3000, staffId = "staff-1", idempotencyKey = "llave-2",
            lineasDelTicket = "{\"lineas\":[")
        assertTrue(rota.isSuccess)
        assertFalse(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("lineasDelTicket"))
    }

    // MARK: - La fila de la cola y su reenvío

    private fun fila(paymentType: String = "ORDER", lineas: String? = copia) = PendingPaymentEntity(
        id = "llave-1", venueId = "venue-1", staffId = "staff-1", amountCents = 2700, tipCents = 0, method = "CASH",
        paymentType = paymentType, orderId = "orden-1", splitType = "BYPRODUCT", lineasDelTicketJson = lineas,
    )

    @Test
    fun `el reenvio a pay manda la copia de la fila, a fast no, y una columna ilegible no viaja`() {
        val aPay = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(fila(), "venue-1", esCobroDeOrden = true)).jsonObject
        assertEquals(Json.parseToJsonElement(copia), aPay.copiaDelBody())
        val aFast = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(fila("FAST"), "venue-1", esCobroDeOrden = false)).jsonObject
        assertFalse(aFast.containsKey("lineasDelTicket"))
        val rota = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(fila(lineas = "no es json"), "venue-1", true)).jsonObject
        assertFalse(rota.containsKey("lineasDelTicket"))
        val sin = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(fila(lineas = null), "venue-1", true)).jsonObject
        assertFalse(sin.containsKey("lineasDelTicket"))
    }

    @Test
    fun `un reemplazo de la reserva sin copia conserva la de disco y uno con copia trae la suya`() {
        val reserva = fila().copy(syncStatus = "EN_VUELO")
        assertEquals(copia, filaAlReemplazar(reserva, reserva.copy(syncStatus = "PENDING", lineasDelTicketJson = null)).lineasDelTicketJson)
        // La copia sigue al importe de ESA escritura: un intento nuevo con otro importe trae la que cuadra con él.
        assertEquals("{}", filaAlReemplazar(reserva, reserva.copy(amountCents = 2600, lineasDelTicketJson = "{}")).lineasDelTicketJson)
        assertNull(filaAlReemplazar(null, reserva.copy(lineasDelTicketJson = null)).lineasDelTicketJson)
    }

    @Test
    fun `la reserva y la cola guardan la copia en su columna propia, nunca en itemsJson`() = runBlocking {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "venue-1"
        val dao = mockk<PendingPaymentDao>(relaxed = true)
        val escritas = mutableListOf<PendingPaymentEntity>()
        coEvery { dao.findForVenue(any(), any()) } coAnswers { escritas.lastOrNull { it.id == secondArg<String>() } }
        coEvery { dao.insert(any()) } coAnswers { escritas += firstArg<PendingPaymentEntity>() }
        val repo = CashPaymentRepository(storage, dao)
        val pedido = CreateOrderRequest(
            items = listOf(OrderItemRequest(productId = "prod-cafe", name = "Café", quantity = 1, unitPrice = 3000)),
            subtotal = 3000, total = 3000, paymentMethod = "CASH", splitType = "BYPRODUCT",
        )
        repo.reservarCobro(pedido, "staff-1", 5000, 2300, null, orderId = "orden-1", idempotencyKey = "llave-1", amountCents = 2700,
            tipCents = 0, splitType = "BYPRODUCT", venueId = "venue-1", lineasDelTicket = copia)
        repo.queueCashPayment(pedido, "staff-1", 5000, 2300, null, orderId = "orden-1", idempotencyKey = "llave-2", amountCents = 2700,
            tipCents = 0, splitType = "BYPRODUCT", venueId = "venue-1", lineasDelTicket = copia)
        assertEquals(listOf("EN_VUELO", "PENDING"), escritas.map { it.syncStatus })
        escritas.forEach {
            assertEquals(copia, it.lineasDelTicketJson)
            assertNull(it.itemsJson)
        }
        // Lo que sube al volver la red es justo esa copia.
        val reenvio = Json.parseToJsonElement(PaymentSyncService.buildReplayBody(escritas.last(), "venue-1", true)).jsonObject
        assertEquals(Json.parseToJsonElement(copia), reenvio.copiaDelBody())
    }
}
