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
        val result = api.reschedule("res-1", RescheduleRequest(startsAt = "2026-04-30T15:00:00.000Z", endsAt = "2026-04-30T16:00:00.000Z"))
        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("2026-04-30T15:00"))
    }

    @Test
    fun `non-2xx surfaces failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        val result = api.get("res-1")
        assertTrue(result.isFailure)
    }
}
