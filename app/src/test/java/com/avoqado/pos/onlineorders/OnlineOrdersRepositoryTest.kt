package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.PlanTier
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import com.avoqado.pos.onlineorders.domain.PrecioDeRenglon
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
    /** El caché del aparato, en memoria: lo que `save` guarda lo devuelve `load` (como `cached_payloads`). */
    private val guardado = mutableMapOf<String, String>()
    private val cache = mockk<PayloadCache>().also { c ->
        coEvery { c.save(any(), any(), any()) } answers { guardado["${firstArg<String>()}|${secondArg<String>()}"] = thirdArg() }
        coEvery { c.load(any(), any()) } answers { guardado["${firstArg<String>()}|${secondArg<String>()}"]?.let { PayloadCache.Cached(it, 0L) } }
    }
    private lateinit var repo: OnlineOrdersRepository

    @Before
    fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), cache)
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
    fun `saldoDelPedido reads preBillBalance remainingBalanceCents from the order detail`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"order":{"id":"o1","preBillBalance":{"orderTotalCents":20000,"totalPaidCents":5000,"remainingBalanceCents":15000,"tipAmountCents":0}}}"""))
        assertEquals(15000L, repo.saldoDelPedido("o1").getOrThrow().saldoCents)
        val pedida = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/mobile/venues/v1/orders/o1", pedida.requestUrl!!.encodedPath)
        // 🔴 DINERO (PR #53): un 403 con PIN de gerente no se espera DENTRO de la llamada de 8 s; si no, muere como `IOException`
        // y «Cobrar» lo toma por «sin red» y cobra el saldo viejo de la bandeja.
        assertEquals("1", pedida.getHeader(ForbiddenInterceptor.FAIL_FAST_HEADER))
    }

    @Test
    fun `PlanManager maps ONLINE_ORDERING to PRO`() {
        assertEquals(PlanTier.PRO, PlanManager.FEATURE_REQUIRED_TIER["ONLINE_ORDERING"])
    }

    // MARK: - Rappi 2b: «Rappi · por cobrar»

    private fun porCobrar(extra: String = "", fila: String = filaPorCobrar(), total: Int = 1, truncated: Boolean = false, cursor: String? = null) =
        """{"success":true,"data":{"orders":[$fila$extra],"total":$total,"truncated":$truncated,"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}}"""

    private fun filaPorCobrar(id: String = "o9") =
        """{"orderId":"$id","orderNumber":"881655210","provider":"RAPPI","status":"CONFIRMED","kitchenStatus":"PENDING","customerName":"Ana",
        "createdAt":"2026-10-23T18:00:00.000Z","remainingBalanceCents":20500,"saleCents":17500,"tipCents":2000,"thirdPartyCents":1000,"thirdPartyLabel":"Rappi"}"""

    @Test
    fun `porCobrar reads the delivery cash list in cents with its breakdown`() = runTest {
        server.enqueue(MockResponse().setBody(porCobrar()))
        val lista = repo.porCobrar().getOrThrow()
        val p = lista.pedidos.single()
        assertEquals("o9", p.orderId); assertEquals("881655210", p.orderNumber); assertEquals("Ana", p.customerName)
        assertEquals(20500L, p.remainingBalanceCents); assertEquals(17500L, p.saleCents); assertEquals(2000L, p.tipCents)
        assertEquals(1000L, p.thirdPartyCents); assertEquals("Rappi", p.thirdPartyLabel)
        assertEquals(1, lista.total); assertFalse(lista.truncated); assertNull(lista.nextCursor); assertTrue(lista.disponible)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/delivery-cash", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `porCobrar skips a malformed row and keeps the rest`() = runTest {
        val sinSaldo = filaPorCobrar("o8").replace("\"remainingBalanceCents\":20500,", "")
        server.enqueue(MockResponse().setBody(porCobrar(extra = ",$sinSaldo", total = 2)))
        assertEquals(listOf("o9"), repo.porCobrar().getOrThrow().pedidos.map { it.orderId })
    }

    @Test
    fun `porCobrar sends the cursor and keeps truncated and nextCursor`() = runTest {
        server.enqueue(MockResponse().setBody(porCobrar(total = 60, truncated = true, cursor = "abc")))
        server.enqueue(MockResponse().setBody(porCobrar(total = 60)))
        val primera = repo.porCobrar().getOrThrow()
        assertTrue(primera.truncated); assertEquals("abc", primera.nextCursor); assertEquals(60, primera.total)
        repo.porCobrar(cursor = primera.nextCursor).getOrThrow()
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/delivery-cash?cursor=abc", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `porCobrar on an old server or without permission is not available, without error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":"Not found"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"Forbidden"}"""))
        for (i in 0..1) {
            val lista = repo.porCobrar().getOrThrow()
            assertFalse(lista.disponible); assertTrue(lista.pedidos.isEmpty())
        }
    }

    @Test
    fun `porCobrar saves page one in the device and a network failure keeps it`() = runTest {
        server.enqueue(MockResponse().setBody(porCobrar()))
        repo.porCobrar().getOrThrow()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(repo.porCobrar().isFailure)
        assertEquals(listOf("o9"), repo.porCobrarGuardado()!!.pedidos.map { it.orderId })
    }

    @Test
    fun `porCobrar does not overwrite the saved list with a later page`() = runTest {
        server.enqueue(MockResponse().setBody(porCobrar()))
        server.enqueue(MockResponse().setBody(porCobrar(fila = filaPorCobrar("o7"))))
        repo.porCobrar().getOrThrow()
        repo.porCobrar(cursor = "abc").getOrThrow()
        assertEquals(listOf("o9"), repo.porCobrarGuardado()!!.pedidos.map { it.orderId })
    }

    @Test
    fun `porCobrarGuardado is null when nothing was ever saved`() = runTest {
        assertNull(repo.porCobrarGuardado())
    }

    // MARK: - D4 (28-oct): el precio de cada producto, sólo para «Ver productos»

    private fun detalle(saldo: Int, items: String, pagado: Int = 0) =
        """{"success":true,"order":{"id":"o1","preBillBalance":{"orderTotalCents":${saldo + pagado},"totalPaidCents":$pagado,"remainingBalanceCents":$saldo,"tipAmountCents":0},"items":[$items]}}"""

    @Test
    fun `saldoDelPedido trae el precio de cada producto`() = runTest {
        server.enqueue(MockResponse().setBody(detalle(10850,
            """{"productName":"Tacos de Pollo","quantity":2,"unitPrice":39.5,"total":79,"weightQuantity":null,"notes":"sin cebolla","modifiers":[]},""" +
            """{"productName":"Agua de Jamaica","quantity":1,"unitPrice":25,"total":29.5,"weightQuantity":null,"notes":null,"modifiers":[{"name":"Grande","price":4.5}]}""")))
        val leido = repo.saldoDelPedido("o1").getOrThrow()
        assertEquals(10850L, leido.saldoCents)
        assertEquals(
            listOf(PrecioDeRenglon("Tacos de Pollo", 2, 7900, "sin cebolla"), PrecioDeRenglon("Agua de Jamaica", 1, 2950, null, listOf("Grande"))),
            leido.precios,
        )
    }

    @Test
    fun `saldoDelPedido con un abono previo no trae precios aunque cuadren`() = runTest {
        // Codex r2 #2: cargo por servicio de $20 y abono de $20 dejan el saldo igual a los productos por casualidad.
        server.enqueue(MockResponse().setBody(detalle(10000, """{"productName":"A","quantity":1,"total":100,"weightQuantity":null}""", pagado = 2000)))
        val leido = repo.saldoDelPedido("o1").getOrThrow()
        assertEquals(10000L, leido.saldoCents)
        assertNull(leido.precios)
    }

    @Test
    fun `saldoDelPedido sin el total de un producto no inventa un cero y el saldo sale igual`() = runTest {
        // Codex r1 #3: un `total` ausente se leía como $0 y un renglón gratis cuadraba la suma.
        server.enqueue(MockResponse().setBody(detalle(10000,
            """{"productName":"A","quantity":1,"total":100,"weightQuantity":null},{"productName":"B","quantity":1,"weightQuantity":null}""")))
        val leido = repo.saldoDelPedido("o1").getOrThrow()
        assertEquals(10000L, leido.saldoCents)
        assertNull(leido.precios)
    }

    @Test
    fun `saldoDelPedido con un producto ilegible sigue dando el saldo`() = runTest {
        server.enqueue(MockResponse().setBody(detalle(7900, """{"quantity":"x"}""")))
        val leido = repo.saldoDelPedido("o1").getOrThrow()
        assertEquals(7900L, leido.saldoCents)
        assertNull(leido.precios)
    }
}
