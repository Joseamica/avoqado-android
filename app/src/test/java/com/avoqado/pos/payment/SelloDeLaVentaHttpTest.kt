package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.di.NetworkModule
import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.data.SaleStampReason
import com.avoqado.pos.payment.data.OrderRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sellos tardíos: el servidor dice en la respuesta de la liga si la venta sumó sello (campo `loyalty`, OPCIONAL).
 * Un servidor viejo no lo manda y la liga tiene que seguir siendo un éxito igual.
 */
class SelloDeLaVentaHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: OrderRepository
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    /** Las preferencias del aparato: sobreviven a que la app se reinicie (un `OrderRepository` nuevo). */
    private val preferencias = mutableMapOf<String, Boolean>()

    private val selloSumado =
        """{"stampsEnabled":true,"stamped":true,"reason":null,"stampsEarned":3,"stampsRequired":7,"rewardReady":false}"""

    /** Un `loyalty` mal formado: un número que el contrato nunca manda nulo. */
    private val selloConNumeroNulo =
        """{"stampsEnabled":true,"stamped":true,"reason":null,"stampsEarned":null,"stampsRequired":7,"rewardReady":false}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token-1"
        every { secureStorage.programaDeSellosActivo(any()) } answers { preferencias[firstArg<String>()] }
        every { secureStorage.setProgramaDeSellosActivo(any(), any()) } answers {
            preferencias[firstArg<String>()] = secondArg<Boolean>()
        }
        repository = OrderRepository(secureStorage, OkHttpClient())
        repository.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `P1 la liga tardia devuelve el sello que dijo el servidor`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"id":"orden-1","total":"120.00","loyalty":$selloSumado},"message":"ok"}"""))

        val sello = repository.attachCustomerToPayment("pago-1", "cm-dany").getOrThrow()

        assertEquals(SaleStampOutcome(true, true, null, 3, 7, false), sello)
        assertEquals("/api/v1/mobile/venues/venue-1/payments/pago-1/customer", server.takeRequest().path)
        assertEquals(true, repository.programaDeSellosActivo("venue-1"))
    }

    @Test
    fun `P1 un servidor viejo sin loyalty liga igual y no trae sello`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"id":"orden-1"},"message":"ok"}"""))

        val resultado = repository.attachCustomerToPayment("pago-1", "cm-dany")

        assertTrue(resultado.isSuccess)
        assertNull(resultado.getOrNull())
        assertNull("sin respuesta del servidor no se inventa si hay programa", repository.programaDeSellosActivo("venue-1"))
    }

    @Test
    fun `loyalty null o ilegible no tumba la liga`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"id":"orden-1","loyalty":null}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"id":"orden-1","loyalty":"raro"}}"""))

        assertNull(repository.attachCustomerToPayment("pago-1", "cm-dany").getOrThrow())
        assertNull(repository.attachCustomerToPayment("pago-1", "cm-dany").getOrThrow())
    }

    @Test
    fun `P1 un loyalty con un numero nulo no tumba la liga ni inventa un sello`() = runBlocking {
        // Premisa (revisión de A3): el Json compartido de la app (NetworkModule) NO trae `coerceInputValues`, así que
        // leer este objeto tal cual TRUENA. Si la liga lo leyera sin defensa, el cliente quedaría ligado en el servidor
        // y la pantalla diría que falló. Y coercionarlo tampoco sirve: `stampsEarned` null → 0 con `stamped` true diría
        // «¡Completó su tarjeta!» sin que fuera cierto.
        assertTrue(
            "premisa: el Json de NetworkModule rechaza un número nulo",
            runCatching {
                NetworkModule.provideJson().decodeFromString(SaleStampOutcome.serializer(), selloConNumeroNulo)
            }.isFailure,
        )
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"id":"orden-1","loyalty":$selloConNumeroNulo}}"""))

        val resultado = repository.attachCustomerToPayment("pago-1", "cm-dany")

        assertTrue("la liga sí quedó: es un éxito", resultado.isSuccess)
        assertNull("un sello ilegible no se pinta", resultado.getOrNull())
        assertNull(repository.programaDeSellosActivo("venue-1"))
    }

    // Revisión final (Minor #7): el recuerdo vivía sólo en memoria; tras reiniciar la app SIN red, la venta encolada
    // con cliente nunca decía «El sello se suma al sincronizar» (spec §4.1).
    @Test
    fun `P1 tras reiniciar la app se recuerda si el negocio tiene sellos, y por negocio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"id":"orden-1","loyalty":$selloSumado}}"""))
        repository.attachCustomerToPayment("pago-1", "cm-dany").getOrThrow()

        val trasReiniciar = OrderRepository(secureStorage, OkHttpClient())

        assertEquals(true, trasReiniciar.programaDeSellosActivo("venue-1"))
        assertNull("otro negocio no hereda el recuerdo", trasReiniciar.programaDeSellosActivo("venue-2"))
    }

    @Test
    fun `un programa de sellos apagado tambien se recuerda tras reiniciar`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"stampsEnabled":false,"stamped":false,"reason":null,"stampsEarned":0,"stampsRequired":0,"rewardReady":false}}""",
            ),
        )
        repository.consultarSelloDeLaVenta("pago-1")

        assertEquals(false, OrderRepository(secureStorage, OkHttpClient()).programaDeSellosActivo("venue-1"))
    }

    @Test
    fun `la liga a la ultima venta tambien lee el sello`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"loyalty":$selloSumado}}"""))

        val sello = repository.attachCustomerToLatestPayment("cm-dany", amountCents = 12_000, tipCents = 0, staffId = "staff-1").getOrThrow()

        assertEquals(3, sello?.stampsEarned)
        assertEquals("/api/v1/mobile/venues/venue-1/payments/customer", server.takeRequest().path)
    }

    @Test
    fun `un rechazo del servidor sigue siendo un fallo con su mensaje`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Pago no encontrado"}"""))

        val error = repository.attachCustomerToPayment("pago-1", "cm-dany").exceptionOrNull()

        assertEquals("Pago no encontrado", error?.message)
    }

    // MARK: - A5: consulta del sello

    @Test
    fun `P1 la consulta del sello va como tarea de fondo para que un 403 no abra el teclado del PIN`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":$selloSumado}"""))

        val sello = repository.consultarSelloDeLaVenta("pago-1")

        assertEquals(3, sello?.stampsEarned)
        val peticion = server.takeRequest()
        assertEquals("GET", peticion.method)
        assertEquals("/api/v1/mobile/venues/venue-1/payments/pago-1/loyalty", peticion.path)
        assertEquals("1", peticion.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals(true, repository.programaDeSellosActivo("venue-1"))
    }

    @Test
    fun `pendiente se lee como PENDING para que la pantalla reintente`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"stampsEnabled":true,"stamped":false,"reason":"PENDING","stampsEarned":2,"stampsRequired":7,"rewardReady":false}}"""))

        assertEquals(SaleStampReason.PENDING, repository.consultarSelloDeLaVenta("pago-1")?.reasonCode)
    }

    @Test
    fun `venta sin cliente, servidor viejo o sin permiso no traen sello y no lanzan`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":null}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not found"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden","message":"Permission 'loyalty:read' required"}"""))

        assertNull(repository.consultarSelloDeLaVenta("pago-1"))
        assertNull(repository.consultarSelloDeLaVenta("pago-1"))
        assertNull(repository.consultarSelloDeLaVenta("pago-1"))
    }

    @Test
    fun `P1 una consulta con un numero nulo no lanza ni pinta un sello inventado`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":$selloConNumeroNulo}"""))

        assertNull(repository.consultarSelloDeLaVenta("pago-1"))
        assertNull("una lectura descartada no dice nada del programa", repository.programaDeSellosActivo("venue-1"))
    }

    @Test
    fun `sin red la consulta devuelve null en vez de lanzar`() = runBlocking {
        repository.baseUrl = "http://127.0.0.1:1/api/v1"

        assertNull(repository.consultarSelloDeLaVenta("pago-1"))
    }
}
