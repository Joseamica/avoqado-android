package com.avoqado.pos.kiosk

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.data.PackCheckout
import com.avoqado.pos.kiosk.data.PackStatus
import com.avoqado.pos.kiosk.domain.KioskPerson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * La compra de paquete del kiosco contra un servidor HTTP de verdad (D3 del pizarrón).
 *
 * 🔴 Antes el kiosco mandaba sólo `packId` y el servidor contestaba `url: null` siempre; y
 * sin contacto en la sesión, Stripe habría cobrado sin que se entregaran las clases.
 */
class KioskCheckInApiTest {

    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() = server.shutdown()

    private fun api(): KioskCheckInApi {
        val storage = mockk<SecureStorage> { every { venueId } returns "v1" }
        return KioskCheckInApi(storage, OkHttpClient()) { server.url("/api/v1").toString().trimEnd('/') }
    }

    @Test
    fun `packCheckout manda packId y telefono y lee url, sessionId y expiresAt`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://pay/x","sessionId":"cs_1","expiresAt":"2026-10-28T18:00:00.000Z"}"""))

        assertEquals(
            PackCheckout("https://pay/x", "cs_1", "2026-10-28T18:00:00.000Z"),
            api().packCheckout("p1", "+525512345678").getOrThrow(),
        )
        val req = server.takeRequest()
        assertEquals("/api/v1/mobile/venues/v1/kiosk/pack-checkout", req.path)
        assertEquals("""{"packId":"p1","phone":"+525512345678"}""", req.body.readUtf8())
    }

    @Test
    fun `packCheckout sin url es falla, nunca un QR vacio`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":null,"sessionId":"cs_1"}"""))

        assertTrue(api().packCheckout("p1", "+525512345678").isFailure)
    }

    @Test
    fun `packCheckout con error del servidor es falla`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"No podemos venderte este paquete aquí."}"""))

        assertTrue(api().packCheckout("p1", "+525512345678").isFailure)
    }

    @Test
    fun `packCheckoutStatus lee PAID`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"PAID"}"""))

        assertEquals(PackStatus.PAID, api().packCheckoutStatus("cs_1").getOrThrow())
        assertEquals("/api/v1/mobile/venues/v1/kiosk/pack-checkout/cs_1/status", server.takeRequest().path)
    }

    @Test
    fun `packCheckoutStatus con un estado desconocido es falla, no un pago`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"COMPLETE"}"""))

        assertTrue(api().packCheckoutStatus("cs_1").isFailure)
    }

    // MARK: - La lista y el check-in propios del kiosco (Modo Autoservicio 0.e)

    private val C = "2026-10-28T19:20:00.000Z"

    @Test
    fun `roster lee la clase, su gente y cuanto le queda, y null cuando no hay clase`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"roster":{"classTitle":"Yoga","startsAt":"2026-10-28T19:10:00.000Z","staffFirstName":"María",
                "people":[{"reservationId":"r1","displayName":"Ana R.","checkedIn":true,"spotLabel":"Tapete 4"}],"closesAt":"2026-10-28T19:20:00.000Z","closesInMs":1200000}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"roster":null}"""))

        val r = api().roster().getOrThrow()!!
        assertEquals(listOf(KioskPerson("r1", "Ana R.", true, "Tapete 4")), r.people)
        assertEquals(1_200_000L, r.closesInMs)
        assertEquals("2026-10-28T19:20:00.000Z", r.closesAt)
        assertEquals("Yoga", r.classTitle)
        assertEquals("María", r.staffFirstName)
        assertEquals("/api/v1/mobile/venues/v1/kiosk/roster", server.takeRequest().path)
        assertNull(api().roster().getOrThrow())
    }

    @Test
    fun `checkInReservation va a la sucursal que le dicen, manda el cierre visto y msBeforeClose, y 422 es OutsideWindow`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"El check-in solo esta disponible desde 20 minutos antes","code":"CHECK_IN_OUTSIDE_WINDOW"}"""))

        assertTrue(api().checkInReservation("vA", "r1", C, 900_000).exceptionOrNull() is KioskCheckInApi.OutsideWindow)
        val req = server.takeRequest()
        assertEquals("/api/v1/mobile/venues/vA/kiosk/reservations/r1/check-in", req.path)
        assertEquals("""{"closesAt":"2026-10-28T19:20:00.000Z","msBeforeClose":900000}""", req.body.readUtf8())
    }

    @Test
    fun `409 es Rejected con el texto del servidor, y red caida es IOException sin envolver`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"message":"Ana R. llegó a las 13:05 sin conexión…","code":"RESERVATION_NOT_CHECKINABLE"}"""),
        )
        val e = api().checkInReservation("v1", "r1", C, 1).exceptionOrNull() as KioskCheckInApi.Rejected
        assertEquals(409, e.status)
        assertEquals("Ana R. llegó a las 13:05 sin conexión…", e.message)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(api().checkInReservation("v1", "r1", C, 1).exceptionOrNull() is IOException)
    }

    @Test
    fun `la lista y el check-in usan el cliente de la app, con tope de 10 s por llamada`() = runTest {
        val storage = mockk<SecureStorage> { every { venueId } returns "v1" }
        val deLaApp = OkHttpClient.Builder()
            .addInterceptor { it.proceed(it.request().newBuilder().header("X-De-La-App", "1").build()) }
            .build()
        val api = KioskCheckInApi(storage, deLaApp) { server.url("/api/v1").toString().trimEnd('/') }
        server.enqueue(MockResponse().setBody("""{"roster":null}"""))

        api.roster()

        // Conserva lo del cliente inyectado (sesión, ForbiddenInterceptor, conexiones) y le pone un tope total.
        assertEquals("1", server.takeRequest().getHeader("X-De-La-App"))
        assertEquals(10_000, api.kioskClient.callTimeoutMillis)
        assertEquals(deLaApp.interceptors, api.kioskClient.interceptors)
        assertTrue(deLaApp.connectionPool === api.kioskClient.connectionPool)
    }

    @Test
    fun `la lista y el check-in van como tarea de fondo - un 403 no abre el dialogo del cajero`() = runTest {
        server.enqueue(MockResponse().setBody("""{"roster":null}"""))
        server.enqueue(MockResponse().setBody("""{}"""))

        api().roster()
        api().checkInReservation("v1", "r1", C, 1)

        assertEquals("1", server.takeRequest().getHeader("X-Avoqado-Background"))
        assertEquals("1", server.takeRequest().getHeader("X-Avoqado-Background"))
    }
}
