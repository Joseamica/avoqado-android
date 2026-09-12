package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * El DELETE de la orden, con RESULTADO TIPADO.
 *
 * Antes devolvía `Result<Unit>` con un `Exception("Error al cancelar orden")` para todo lo que no
 * fuera 2xx: el 409 `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE` —que dice QUÉ cobro bloquea— se leía
 * igual que una cuenta ya pagada o que un túnel caído, y el cuerpo de la respuesta ni se cerraba.
 * La cancelación durable necesita distinguirlos: sólo uno de ellos significa «vuelve a esperar».
 */
class CancelarOrdenHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: OrderRepository
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        repository = OrderRepository(secureStorage, OkHttpClient())
        repository.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `P1 una cancelacion aceptada viaja con el motivo y sin abrir el teclado del PIN`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"message":"Orden cancelada exitosamente"}"""))

        val resultado = repository.cancelOrder("order-1", venueId = "venue-9", reason = "Cobro cancelado")

        assertEquals(ResultadoDeCancelarOrden.Cancelada, resultado)
        val peticion = server.takeRequest()
        assertEquals("DELETE", peticion.method)
        assertEquals("/api/v1/mobile/venues/venue-9/orders/order-1", peticion.path)
        assertEquals("1", peticion.getHeader(ForbiddenInterceptor.FAIL_FAST_HEADER))
        assertEquals("Cobro cancelado", JSONObject(peticion.body.readUtf8()).getString("reason"))
    }

    @Test
    fun `P1 un 409 con el cobro que bloquea se lee con su solicitud`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"message":"Hay un cobro en curso en la terminal para esta orden.",
                    "code":"ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE","details":{"requestId":"req-1"}}""",
            ),
        )

        val resultado = repository.cancelOrder("order-1")

        assertEquals(
            ResultadoDeCancelarOrden.BloqueadaPorCobro("req-1", "Hay un cobro en curso en la terminal para esta orden."),
            resultado,
        )
    }

    @Test
    fun `un 409 sin codigo no se confunde con el cobro que bloquea`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Conflicto"}"""))

        assertEquals(ResultadoDeCancelarOrden.RechazoDeNegocio(409, null, "Conflicto"), repository.cancelOrder("order-1"))
    }

    @Test
    fun `P1 una cuenta con dinero se distingue de todo lo demas`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Cannot cancel a paid order"}"""))

        assertEquals(
            ResultadoDeCancelarOrden.RechazoDeNegocio(400, null, "Cannot cancel a paid order"),
            repository.cancelOrder("order-1"),
        )
    }

    @Test
    fun `una orden que ya no existe es NoExiste`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Order not found"}"""))

        assertEquals(ResultadoDeCancelarOrden.NoExiste, repository.cancelOrder("order-1"))
    }

    @Test
    fun `P1 un 404 de un tunel caido NO es una orden inexistente`() = runBlocking {
        // Mismo defecto que ya costó una venta encolada: un 4xx de un intermediario no dice nada
        // de la orden. Si se leyera como «ya no existe», la intención se cerraría sin cancelar nada.
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setHeader("ngrok-error-code", "ERR_NGROK_3200")
                .setBody("<html>Tunnel not found</html>"),
        )

        assertEquals(ResultadoDeCancelarOrden.SinRed, repository.cancelOrder("order-1"))
    }

    @Test
    fun `un 500 es error del servidor y se reintenta despues`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(ResultadoDeCancelarOrden.ErrorDeServidor(500), repository.cancelOrder("order-1"))
    }

    @Test
    fun `P1 sin red la cancelacion de la orden no concluye nada`() = runBlocking {
        server.shutdown()

        assertEquals(ResultadoDeCancelarOrden.SinRed, repository.cancelOrder("order-1"))
    }

    @Test
    fun `sin sesion no se inventa un desenlace`() = runBlocking {
        every { secureStorage.accessToken } returns null

        assertEquals(ResultadoDeCancelarOrden.SinRed, repository.cancelOrder("order-1"))
    }
}
