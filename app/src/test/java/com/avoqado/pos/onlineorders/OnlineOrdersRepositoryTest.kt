package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.PlanTier
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Las rutas de «Pedidos en línea» contra un servidor HTTP de verdad (MockWebServer). Contrato: inbox.service.ts y el controlador móvil. */
class OnlineOrdersRepositoryTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private lateinit var repo: OnlineOrdersRepository

    @Before
    fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
    }

    @After
    fun apagar() {
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    private fun pedido(status: String = "PENDING_ACCEPTANCE", total: String = "185.50", remaining: String = "185.50") =
        """{"orderId":"o1","onlineOrderId":"oo1","orderNumber":"W-101","status":"$status","paymentMode":"PAY_AT_PICKUP",
        "customerName":"Ana","customerPhone":null,"note":null,"createdAt":"2026-10-21T12:00:00.000Z",
        "acceptDeadlineAt":"2026-10-21T12:05:00.000Z","estimatedReadyAt":null,"total":$total,"remaining":$remaining,
        "paymentStatus":"PENDING","needsPrint":true,
        "items":[{"productId":"p1","categoryId":null,"name":"Taco","quantity":2,"note":"sin cebolla","modifiers":[{"name":"Queso","quantity":1}]}]}"""

    private fun bandeja(orders: String = pedido(), store: String = """{"status":"OPEN"}""", extra: String = "") =
        """{"success":true,"data":{"serverTime":"2026-10-21T12:00:30.000Z","counts":{"pending":3,"active":2,"done":7,"accepted":1,"ready":1},
        "orders":[$orders],"truncated":false,"nextCursor":null$extra,"store":$store}}"""

    private fun conflicto(status: String) =
        MockResponse().setResponseCode(409).setBody("""{"success":false,"code":"ORDER_STATE_CHANGED","status":"$status","error":"cambió"}""")

    @Test
    fun `parses inbox pesos to exact cents`() = runTest {
        // Con Double*100, 185.5 salta bien pero 0.29 -> 28; se usa la cadena decimal exacta.
        server.enqueue(MockResponse().setBody(bandeja(pedido(total = "185.50", remaining = "0.29"))))
        val inbox = repo.inbox("all", comoBandeja = true).getOrThrow()
        val o = inbox.orders.single()
        assertEquals(18550L, o.totalCents)
        assertEquals(29L, o.remainingCents)
        assertEquals("Taco", o.items.single().name)
        assertEquals("Queso", o.items.single().modifiers.single().name)
        assertNull(o.items.single().categoryId)
        assertTrue(o.needsPrint)
        assertEquals(OnlineOrderStatus.PENDING_ACCEPTANCE, o.status)
        assertEquals(3, inbox.pending); assertEquals(2, inbox.active); assertEquals(7, inbox.done)
        assertFalse(inbox.storeOff)
    }

    @Test
    fun `exact money conversion including E-notation`() = runTest {
        server.enqueue(MockResponse().setBody(bandeja(
            pedido(total = "0.10", remaining = "185.5") + "," + pedido(total = "12345678.9", remaining = "0"))))
        val (a, b) = repo.inbox("all", true).getOrThrow().orders
        assertEquals(10L, a.totalCents); assertEquals(18550L, a.remainingCents)
        assertEquals(1234567890L, b.totalCents); assertEquals(0L, b.remainingCents)
    }

    @Test
    fun `a malformed order is skipped and the rest of the inbox survives`() = runTest {
        val sinId = pedido().replace("\"orderId\":\"o1\",", "")
        val malaFecha = pedido().replace("2026-10-21T12:00:00.000Z", "ayer")
        server.enqueue(MockResponse().setBody(bandeja(sinId + "," + pedido() + "," + malaFecha)))
        val inbox = repo.inbox("pending", true).getOrThrow()
        assertEquals(1, inbox.orders.size)
        assertEquals("o1", inbox.orders.single().orderId)
        assertEquals(3, inbox.pending); assertEquals(2, inbox.active)
    }

    @Test
    fun `unknown status maps to UNKNOWN`() = runTest {
        assertEquals(OnlineOrderStatus.UNKNOWN, OnlineOrderStatus.from("ALGO_NUEVO"))
        assertEquals(OnlineOrderStatus.UNKNOWN, OnlineOrderStatus.from(null))
        assertEquals(OnlineOrderStatus.READY, OnlineOrderStatus.from("READY"))
        server.enqueue(MockResponse().setBody(bandeja(pedido(status = "ALGO_NUEVO"))))
        assertEquals(OnlineOrderStatus.UNKNOWN, repo.inbox("all", true).getOrThrow().orders.single().status)
    }

    @Test
    fun `tray header only when comoBandeja`() = runTest {
        server.enqueue(MockResponse().setBody(bandeja()))
        server.enqueue(MockResponse().setBody(bandeja()))
        repo.inbox("all", comoBandeja = true).getOrThrow()
        repo.inbox("all", comoBandeja = false).getOrThrow()
        assertEquals("1", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-online-orders-tray"))
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-online-orders-tray"))
    }

    @Test
    fun `background header always on inbox`() = runTest {
        server.enqueue(MockResponse().setBody(bandeja()))
        server.enqueue(MockResponse().setBody(bandeja()))
        repo.inbox("pending", comoBandeja = false).getOrThrow()
        repo.inbox("pending", comoBandeja = true).getOrThrow()
        val a = server.takeRequest(1, TimeUnit.SECONDS)!!
        val b = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("1", a.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals("1", b.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertEquals("/api/v1/mobile/venues/v1/online-orders/inbox", a.requestUrl!!.encodedPath)
        assertEquals("pending", a.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun `status pending response still carries pending and active counts`() = runTest {
        server.enqueue(MockResponse().setBody(bandeja(orders = "")))
        val inbox = repo.inbox("pending", comoBandeja = false).getOrThrow()
        assertEquals(3, inbox.pending); assertEquals(2, inbox.active)
    }

    @Test
    fun `storeOff is true only when the store is disabled`() = runTest {
        server.enqueue(MockResponse().setBody(bandeja(store = """{"status":"DISABLED","reason":"NOT_ENABLED"}""")))
        assertTrue(repo.inbox("pending", false).getOrThrow().storeOff)
        server.enqueue(MockResponse().setBody(bandeja(store = """{"status":"OFFLINE","reason":"NO_DEVICE"}""")))
        assertFalse(repo.inbox("pending", false).getOrThrow().storeOff)
        server.enqueue(MockResponse().setBody(bandeja(store = "null")))
        assertFalse(repo.inbox("pending", false).getOrThrow().storeOff)
    }

    @Test
    fun `409 ORDER_STATE_CHANGED with wanted status is Hecho`() = runTest {
        server.enqueue(conflicto("ACCEPTED"))
        assertEquals(AccionResultado.Hecho, repo.accept("o1", 15))
        server.enqueue(conflicto("REJECTED"))
        assertEquals(AccionResultado.Hecho, repo.reject("o1", "CLOSED", null))
        server.enqueue(conflicto("READY"))
        assertEquals(AccionResultado.Hecho, repo.ready("o1"))
        server.enqueue(conflicto("COMPLETED"))
        assertEquals(AccionResultado.Hecho, repo.delivered("o1"))
        server.enqueue(conflicto("CANCELLED_BY_STAFF"))
        assertEquals(AccionResultado.Hecho, repo.cancel("o1"))
    }

    @Test
    fun `409 with other status is YaNoDisponible`() = runTest {
        server.enqueue(conflicto("EXPIRED"))
        assertEquals(AccionResultado.YaNoDisponible(OnlineOrderStatus.EXPIRED), repo.accept("o1", 15))
    }

    @Test
    fun `other 409 codes are Error with the server message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"code":"PAYMENT_REQUIRED","error":"Cobra el pedido antes de entregarlo."}"""))
        val r = repo.delivered("o1")
        assertEquals(AccionResultado.Error("Cobra el pedido antes de entregarlo."), r)
    }

    @Test
    fun `IOException is SinConexion`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(AccionResultado.SinConexion, repo.ready("o1"))
        server.shutdown()
        assertTrue(repo.inbox("all", true).exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `accept reject and pause send the expected bodies`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
        assertEquals(AccionResultado.Hecho, repo.accept("o1", 20))
        assertEquals(AccionResultado.Hecho, repo.reject("o1", "TOO_BUSY", "  "))
        assertEquals(AccionResultado.Hecho, repo.pause(30))
        assertEquals(AccionResultado.Hecho, repo.pause(null))
        val a = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/online-orders/o1/accept", a.requestUrl!!.encodedPath)
        assertEquals(20, JSONObject(a.body.readUtf8()).getInt("estimatedMinutes"))
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        val rb = JSONObject(r.body.readUtf8())
        assertEquals("TOO_BUSY", rb.getString("reason")); assertFalse(rb.has("note"))
        val p = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/online-orders/pause", p.requestUrl!!.encodedPath)
        assertEquals(30, JSONObject(p.body.readUtf8()).getInt("minutes"))
        val p2 = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertTrue(p2.has("minutes") && p2.isNull("minutes"))
    }

    @Test
    fun `print routes send x-device-id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"claimed":true}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"ok":false}}"""))
        assertTrue(repo.claimPrint("o1", "dev-9"))
        assertFalse(repo.confirmPrint("o1", "dev-9"))
        val c = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/online-orders/o1/print/claim", c.requestUrl!!.encodedPath)
        assertEquals("dev-9", c.getHeader("x-device-id"))
        val k = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/online-orders/o1/print/confirm", k.requestUrl!!.encodedPath)
        assertEquals("dev-9", k.getHeader("x-device-id"))
    }

    @Test
    fun `print failure is false never a throw`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertFalse(repo.claimPrint("o1", "dev-9"))
    }

    @Test
    fun `keeps truncated and nextCursor and sends the cursor`() = runTest {
        server.enqueue(MockResponse().setBody(
            bandeja().replace("\"truncated\":false", "\"truncated\":true").replace("\"nextCursor\":null", "\"nextCursor\":\"2026-10-21T11:00:00.000Z_abc\"")))
        val inbox = repo.inbox("active", true, cursor = "2026-10-21T12:00:00.000Z_zzz").getOrThrow()
        assertTrue(inbox.truncated)
        assertEquals("2026-10-21T11:00:00.000Z_abc", inbox.nextCursor)
        assertEquals("2026-10-21T12:00:00.000Z_zzz", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("cursor"))
    }

    @Test
    fun `saldoCents reads preBillBalance remainingBalanceCents from the order detail`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"order":{"id":"o1","preBillBalance":{"orderTotalCents":20000,"totalPaidCents":5000,"remainingBalanceCents":15000,"tipAmountCents":0}}}"""))
        assertEquals(15000L, repo.saldoCents("o1").getOrThrow())
        assertEquals("/api/v1/mobile/venues/v1/orders/o1", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
    }

    @Test
    fun `PlanManager maps ONLINE_ORDERING to PRO`() {
        assertEquals(PlanTier.PRO, PlanManager.FEATURE_REQUIRED_TIER["ONLINE_ORDERING"])
    }
}
