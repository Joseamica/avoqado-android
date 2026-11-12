package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D12 (Tarea 6, Codex r1 #6): quien crea una cuenta de Mostrador con saldo escribe la marca «la abrió aquí» con el `orderId` que
 * le dio el servidor, ANTES de seguir: `createOrder` (venta dividida o con tarjeta) y la cola que crea la orden al volver la red.
 * (Las de «Pagar después» están en `PagarDespuesFilaTest`.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarcaDeCuentaEnLaColaTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private val marcas = mockk<CuentasAbiertasAqui>(relaxed = true)

    @Test fun `createOrder marca la orden que el servidor creo`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<SecureStorage>(relaxed = true)
            every { storage.venueId } returns "venue-A"
            every { storage.accessToken } returns "token-QA"
            val repositorio = OrderRepository(storage, OkHttpClient(), cuentasAbiertasAqui = marcas).apply {
                baseUrl = server.url("/api/v1").toString().trimEnd('/')
            }
            val pedido = CreateOrderRequest(
                items = listOf(OrderItemRequest(productId = "product-A", name = "Artículo A", quantity = 1, unitPrice = 10000)),
                subtotal = 10000, total = 10000, paymentMethod = "CARD",
            )

            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"id":"o7","total":100}}"""))
            assertEquals("o7", repositorio.createOrder(pedido, "staff-A", capturedVenueId = "venue-B").getOrThrow().data?.id)
            coVerify(exactly = 1) { marcas.marcar("venue-B", "o7") } // la sucursal de la PETICIÓN, no la de la sesión

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"message":"El precio cambió"}"""))
            assertTrue(repositorio.createOrder(pedido, "staff-A").isFailure)
            coVerify(exactly = 1) { marcas.marcar(any(), any()) } // el 409 no marca
        } finally {
            server.shutdown()
        }
    }

    @Test fun `la cola marca la orden que crea al volver la red`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = ServidorFalso()
            var marcadaAlPagar: Boolean? = null
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    if (path.endsWith("/orders/o5/pay")) {
                        marcadaAlPagar = runCatching { coVerify(exactly = 1) { marcas.marcar(Q16PrecioCapturadoTest.VENUE, "o5") } }.isSuccess
                    }
                    return falso.dispatch(request).apply { if (path.endsWith("/orders")) setResponseCode(201) }
                }
            }
            val llave = f.venta("venta-A") // fila ORDER sin orderId: la cola crea la orden
            falso.ordenes[llave] = "o5" // el servidor le da el id o5
            val servicio = PaymentSyncService(f.dao, f.storage, f.client, f.network, f.drawer, f.outbox,
                mockk(relaxed = true), cuentasAbiertasAqui = marcas)
            servicio.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            try {
                servicio.start()
                servicio.esperarSincronizacionActualQ16()
            } finally {
                servicio.stop()
            }

            assertEquals(listOf("orders", "pay"), falso.peticiones.map { it.first })
            assertEquals("la marca ya estaba escrita cuando llegó el /pay", true, marcadaAlPagar)
            coVerify(exactly = 1) { marcas.marcar(Q16PrecioCapturadoTest.VENUE, "o5") }
        }
    }

    /**
     * D12 · Opción D (un solo dueño, ronda 1 I3): la cola que cobra una cuenta que YA existía —de otra caja— nunca la marca «abierta
     * aquí». El pedido lleva una cortesía CONFIRMADA (total y `externalId` por artículo, y el servidor la devuelve igual), así que la
     * cola sí re-resuelve la orden y llega a la marca: sin la condición «sólo si la cola la creó», esta prueba falla.
     */
    @Test fun `la cola nunca marca una cuenta que ya existía`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val falso = ServidorFalso()
            falso.mercancia["o-ajena"] = 10005
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.endsWith("/orders")) MockResponse().setResponseCode(201).setBody(
                        """{"success":true,"order":{"id":"o-ajena","total":100.05,""" +
                            """"items":[{"externalId":"l1","isCortesia":true,"cortesiaReason":"Regalo"}]}}""")
                    else falso.dispatch(request)
            }
            f.seed(id = "p1", order = "o-ajena", request = """{"externalId":"ext-ajena","total":10005,""" +
                """"items":[{"productId":"p","quantity":1,"externalId":"l1","isCortesia":true,"cortesiaReason":"Regalo"}]}""")
            val servicio = PaymentSyncService(f.dao, f.storage, f.client, f.network, f.drawer, f.outbox,
                mockk(relaxed = true), cuentasAbiertasAqui = marcas)
            servicio.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            try {
                servicio.start()
                servicio.esperarSincronizacionActualQ16()
            } finally {
                servicio.stop()
            }
            assertTrue("la cola sí re-resolvió la cuenta y siguió (no quedó FAILED)", f.row()["syncStatus"] != "FAILED")
            coVerify(exactly = 0) { marcas.marcar(any(), any()) }
        }
    }
}
