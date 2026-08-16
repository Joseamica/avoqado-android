package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ReservationApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ReservationApi
    private val secureStorage: SecureStorage = mockk(relaxed = true)

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        every { secureStorage.venueId } returns "v1"
        val baseUrl = server.url("/api/v1").toString().removeSuffix("/")
        api = ReservationApi(secureStorage, OkHttpClient(), { baseUrl })
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `list builds correct URL with filters`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data": [], "pagination": {"page":1,"pageSize":50,"total":0,"totalPages":0}}"""))

        val result = api.list(ReservationFilters(page = 2, pageSize = 25, statuses = listOf(ReservationStatus.CONFIRMED), dateFrom = "2026-04-29"))

        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val url = req.path!!
        assertTrue(url.contains("/dashboard/venues/v1/reservations"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("pageSize=25"))
        assertTrue(url.contains("status=CONFIRMED"))
        assertTrue(url.contains("dateFrom=2026-04-29"))
    }

    @Test
    fun `confirm posts to confirm subroute`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/fixtures/reservation_single.json").readText()))
        val result = api.confirm("res-1")
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/reservations/res-1/confirm"))
    }

    @Test
    fun `cancel sends DELETE with body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = api.cancel("res-1", CancelReservationRequest(reason = "Cliente no llegó"))
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.endsWith("/reservations/res-1"))
        assertTrue(req.body.readUtf8().contains("Cliente no llegó"))
    }

    @Test
    fun `reschedule posts isoDate body`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/fixtures/reservation_single.json").readText()))
        val result = api.reschedule(
            "res-1",
            RescheduleRequest(
                startsAt = "2026-04-30T15:00:00.000Z",
                endsAt = "2026-04-30T16:00:00.000Z",
                allowOverCapacity = true,
            ),
        )
        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("2026-04-30T15:00"))
        assertTrue(body.contains("\"allowOverCapacity\":true"))
    }

    @Test
    fun `non-2xx surfaces failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        val result = api.get("res-1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `availability preserves full slots and sends staff aware contract`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"date":"2026-07-22","slots":[
              {"startsAt":"2026-07-22T15:00:00.000Z","endsAt":"2026-07-22T16:00:00.000Z","available":true},
              {"startsAt":"2026-07-22T16:00:00.000Z","endsAt":"2026-07-22T17:00:00.000Z","available":false,"reason":"FULL"}
            ]}
        """.trimIndent()))

        val result = api.availability(
            date = "2026-07-22",
            durationMin = 60,
            productId = "product-1",
            staffId = "staff-1",
            includeFull = true,
            windowSemantics = "base",
        ).getOrThrow()

        assertEquals(2, result.size)
        assertTrue(result[0].available)
        assertFalse(result[1].available)
        assertEquals("FULL", result[1].reason)
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("productId=product-1"))
        assertTrue(path.contains("productIds=product-1"))
        assertTrue(path.contains("staffId=staff-1"))
        assertTrue(path.contains("includeFull=true"))
        assertTrue(path.contains("windowSemantics=base"))
    }

    @Test
    fun `settings and product staff decode opt in contract`() = runTest {
        server.enqueue(MockResponse().setBody("""{"scheduling":{"capacityMode":"per_staff"},"publicBooking":{"showStaffPicker":false}}"""))
        server.enqueue(MockResponse().setBody("""{"productId":"product-1","staffVenueIds":["sv-1"],"staff":[{"staffVenueId":"sv-1","staffId":"staff-1"}],"explicit":true}"""))

        val settings = api.settings().getOrThrow()
        val mapping = api.productStaff("product-1").getOrThrow()

        assertTrue(settings.isStaffAware)
        assertEquals(listOf("staff-1"), mapping.staff.map { it.staffId })
    }

    // MARK: - Lo que corre solo no interrumpe a nadie
    //
    // El calendario se recarga en el arranque de la pantalla y cada 30 s, y el
    // retrier reproduce solo las acciones encoladas. Esas peticiones no las pidió
    // nadie: su 403 se cuenta en línea (o en la cuarentena), nunca como un modal
    // encima de la pantalla en la que esté el mesero.

    @Test
    fun `una carga automatica del calendario viaja marcada como tarea de fondo`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))

        api.calendar("2026-08-16", "2026-08-16", background = true)

        assertEquals(
            "1",
            server.takeRequest()
                .getHeader(com.avoqado.pos.core.data.network.ForbiddenInterceptor.BACKGROUND_HEADER),
        )
    }

    @Test
    fun `un reintento automatico del retrier tambien va marcado`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/fixtures/reservation_single.json").readText()))

        api.confirm("res-1", background = true)

        assertEquals(
            "1",
            server.takeRequest()
                .getHeader(com.avoqado.pos.core.data.network.ForbiddenInterceptor.BACKGROUND_HEADER),
        )
    }

    @Test
    fun `una accion del usuario NO va marcada — ese 403 si tiene que verse`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/fixtures/reservation_single.json").readText()))

        api.confirm("res-1")

        assertNull(
            server.takeRequest()
                .getHeader(com.avoqado.pos.core.data.network.ForbiddenInterceptor.BACKGROUND_HEADER),
        )
    }

    @Test
    fun `structured business error keeps status code and details`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Confirma sobrecupo","code":"OVER_CAPACITY_CONFIRMATION_REQUIRED","details":{"preview":"2 de 1"}}"""))

        val error = api.get("res-1").exceptionOrNull() as ReservationApiException

        assertEquals(409, error.status)
        assertEquals("OVER_CAPACITY_CONFIRMATION_REQUIRED", error.code)
        assertEquals("2 de 1", error.preview)
    }
}
