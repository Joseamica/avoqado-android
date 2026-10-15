package com.avoqado.pos.payment

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.di.NetworkModule
import com.avoqado.pos.loyalty.data.SaleStampOutcome
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
}
