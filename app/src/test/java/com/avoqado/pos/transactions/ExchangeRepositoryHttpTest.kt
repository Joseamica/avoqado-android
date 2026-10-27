package com.avoqado.pos.transactions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.presentation.exchange.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ExchangeRepositoryHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var server: MockWebServer
    private lateinit var repo: ExchangeRepository
    private lateinit var store: ExchangePendingStore
    private lateinit var flow: ExchangeSubmission
    private val storage = mockk<SecureStorage>(relaxed=true)
    @Before fun setup() {
        server=MockWebServer(); server.start()
        every { storage.venueId } returns "venue"
        every { storage.userId } returns "actor"
        repo=ExchangeRepository(storage, OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        repo.baseUrl=server.url("/api/v1").toString().trimEnd('/')
        store=ExchangePendingStore(PreferenceDataStoreFactory.create(scope=scope, produceFile={ temporary.root.resolve("exchange.preferences_pb") }))
        flow=ExchangeSubmission(repo,store)
    }
    @After fun cleanup() { scope.cancel(); server.shutdown() }
    private fun pending()=PendingExchange("venue","payment","ORD-1","Ana", """{"idempotencyKey":"ABCDEFGHIJKLMNOPQRSTUVWXYZ123456","originalPaymentId":"payment","staffId":"actor","deviceId":"device","createdAtLocal":"2026-10-26T10:00:00-06:00","returned":[{"orderItemId":"line","quantity":1,"restock":true,"reason":"SIZE"}],"taken":[{"productId":"product","quantity":1,"displayedUnitPriceCents":2200000000}],"settlement":{"kind":"COLLECT","method":"CASH","expectedDifferenceCents":125,"cashDrawerSessionId":"drawer"}}""")
    private fun enqueue(status:String, code:Int=200) { server.enqueue(MockResponse().setResponseCode(code).setBody("""{"success":true,"data":{"exchange":{"id":"exchange","status":"$status","originalOrderId":"original","originalOrderNumber":"ORD-1","newOrderId":"new","newOrderNumber":"ORD-2","returnedCents":100,"takenCents":225,"differenceCents":125,"settlement":{"kind":"COLLECT","method":"CARD_TERMINAL","amountCents":125,"sources":[]},"lines":{"returned":[],"taken":[]},"amountToChargeCents":125}}}""")) }
    @Test fun `frozen body is durable before POST and retry sends identical integer bytes`()=runBlocking {
        val bytes=pending().body
        server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
            runBlocking { assertEquals(bytes, store.load("venue","payment")!!.body) }
            return MockResponse().setResponseCode(503).setBody("{}")
        } }
        flow.submit(pending())
        assertEquals(ExchangeSendPhase.UNCERTAIN,flow.state.value.phase)
        assertTrue(flow.state.value.locked)
        assertEquals(bytes,server.takeRequest().body.readUtf8())
        val reopened=ExchangeSubmission(repo,store)
        reopened.open("venue","payment")
        assertEquals(TextosCambio.CONFIRMAR_ESTADO,reopened.state.value.action)
        server.dispatcher=QueueDispatcher(); enqueue("COMPLETED")
        reopened.retry()
        assertEquals(bytes,server.takeRequest().body.readUtf8())
        assertEquals(ExchangeSendPhase.COMPLETED,reopened.state.value.phase)
        assertNull(store.load("venue","payment"))
    }
    @Test fun `network drop retains body and offers retry`()=runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        flow.submit(pending())
        assertEquals(TextosCambio.REINTENTAR,flow.state.value.action)
        assertEquals(pending().body,store.load("venue","payment")!!.body)
    }
    @Test fun `pending payment survives process death with order and amount without automatic request`()=runBlocking {
        enqueue("PENDING_PAYMENT",201); flow.submit(pending())
        server.takeRequest()
        val saved=store.load("venue","payment")!!
        assertEquals("new",saved.result!!.newOrderId); assertEquals(125L,saved.result!!.amountToChargeCents)
        val reopened=ExchangeSubmission(repo,store); reopened.open("venue","payment")
        assertTrue(reopened.state.value.locked); assertEquals(0,server.requestCount-1)
        enqueue("NEEDS_REVIEW"); reopened.retry()
        assertEquals(ExchangeSendPhase.REVIEW,reopened.state.value.phase)
        assertNull(store.load("venue","payment"))
    }
    @Test fun `reopening an unstarted pending payment offers to collect the difference`()=runBlocking {
        enqueue("PENDING_PAYMENT"); flow.submit(pending())
        assertEquals("Cobrar la diferencia",flow.state.value.action)
        val reopened=ExchangeSubmission(repo,store)
        reopened.open("venue","payment")
        assertEquals("Cobrar la diferencia",reopened.state.value.action)
        assertTrue(reopened.state.value.locked)
        assertEquals(1,server.requestCount)
    }
    @Test fun `reopening a started card payment only offers to confirm its state`()=runBlocking {
        enqueue("PENDING_PAYMENT"); flow.submit(pending()); flow.markCardStarted("request")
        val reopened=ExchangeSubmission(repo,store)
        reopened.open("venue","payment")
        assertEquals("Confirmar estado",reopened.state.value.action)
        assertEquals(1,server.requestCount)
    }
    @Test fun `actor mismatch and 403 never erase pending`()=runBlocking {
        for ((http,code) in listOf(400 to "ACTOR_MISMATCH",403 to "FORBIDDEN")) {
            server.enqueue(MockResponse().setResponseCode(http).setBody("""{"code":"$code","message":"blocked"}"""))
            if(store.load("venue","payment")==null) flow.submit(pending()) else flow.retry()
            assertEquals(pending().body,store.load("venue","payment")!!.body)
            assertTrue(flow.state.value.locked)
        }
    }
    @Test fun `difference changed reads calculation and releases only for explicit reconfirmation`()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"EXCHANGE_DIFFERENCE_CHANGED","message":"new price","details":{"calculation":{"kind":"PAYOUT","differenceCents":-50,"returnedCents":100,"takenCents":50,"lines":{"returned":[],"taken":[]}}}}"""))
        flow.submit(pending())
        assertEquals(ExchangeSendPhase.RECONFIRM,flow.state.value.phase)
        assertEquals(-50L,flow.state.value.calculation!!.differenceCents)
        assertEquals("PAYOUT",flow.state.value.calculation!!.kind)
        assertNull(store.load("venue","payment"))
        enqueue("COMPLETED")
        flow.submit(pending().copy(body=pending().body.replace("ABCDEFGHIJKLMNOPQRSTUVWXYZ123456","123456ABCDEFGHIJKLMNOPQRSTUVWXYZ").replace("125","-50").replace("COLLECT","PAYOUT")))
        val a=server.takeRequest().body.readUtf8(); val b=server.takeRequest().body.readUtf8()
        assertNotEquals(a,b)
    }
    @Test fun `422 message survives and status wins over repeated HTTP 200`()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"code":"EXCHANGE_ITEM_NOT_RETURNABLE","message":"No quedan piezas."}"""))
        flow.submit(pending()); assertEquals("No quedan piezas.",flow.state.value.message)
        assertNull(store.load("venue","payment"))
        for((status,phase) in listOf("CANCELLED" to ExchangeSendPhase.CANCELLED,"NEEDS_REVIEW" to ExchangeSendPhase.REVIEW,"FUTURE" to ExchangeSendPhase.REVIEW)) {
            enqueue(status); flow.submit(pending()); assertEquals(phase,flow.state.value.phase)
            if(status=="FUTURE") assertNotNull(store.load("venue","payment"))
        }
    }
    @Test fun `complete and cancel use exact mobile routes and reason body`()=runBlocking {
        enqueue("COMPLETED"); repo.complete("exchange")
        assertEquals("/api/v1/mobile/venues/venue/exchanges/exchange/complete",server.takeRequest().path)
        enqueue("CANCELLED"); repo.cancel("exchange","no charge")
        val req=server.takeRequest(); assertEquals("POST",req.method)
        assertEquals("/api/v1/mobile/venues/venue/exchanges/exchange/cancel",req.path)
        assertEquals("""{"reason":"no charge"}""",req.body.readUtf8())
    }
    @Test fun `even body preserves required null method and excludes unused fields`() {
        val draft=ExchangeDraft("payment",listOf(ExchangeReturnedInput("line",1,true,"SIZE")),listOf(ExchangeTakenInput("product",1,displayedUnitPriceCents=2200000000L)),ExchangeSettlementDraft("EVEN",0))
        val bytes=freezeExchange(draft,ExchangeSettlementInput("EVEN",null,0),"actor","device","2026-10-26T10:00:00Z")
        val obj=Json.parseToJsonElement(bytes).jsonObject
        assertTrue(obj["idempotencyKey"]!!.jsonPrimitive.content.matches(Regex("[A-Za-z0-9]{32}")))
        assertEquals(JsonNull,obj["settlement"]!!.jsonObject["method"])
        assertFalse(obj["settlement"]!!.jsonObject.containsKey("cashFromNonCash"))
        assertTrue(bytes.contains("2200000000")); assertFalse(bytes.contains("2200000000.0"))
        assertEquals(Json.parseToJsonElement("""{"originalPaymentId":"payment","returned":[{"orderItemId":"line","quantity":1,"restock":true,"reason":"SIZE"}],"taken":[{"productId":"product","quantity":1,"displayedUnitPriceCents":2200000000}],"staffId":"actor","deviceId":"device","createdAtLocal":"2026-10-26T10:00:00Z","settlement":{"kind":"EVEN","method":null,"expectedDifferenceCents":0}}""").jsonObject,
            JsonObject(obj - "idempotencyKey"))
    }
    @Test fun `storage failure prevents POST and a different body cannot replace an uncertain operation`()=runBlocking {
        val failing=mockk<ExchangePendingStore>()
        coEvery { failing.save(any()) } throws java.io.IOException("disk full")
        val guarded=ExchangeSubmission(repo,failing)
        assertTrue(runCatching { guarded.submit(pending()) }.isFailure)
        assertEquals(0,server.requestCount)
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        flow.submit(pending()); server.takeRequest()
        flow.submit(pending().copy(body="other body"))
        assertEquals(1,server.requestCount)
        assertEquals(pending().body,store.load("venue","payment")!!.body)
    }
    @Test fun `a 400 or 404 from an intermediary or with an unreadable body keeps the pending`()=runBlocking {
        for ((http,body) in listOf(404 to "<html><body>Tunnel not found</body></html>", 400 to "", 400 to """{"error":"bad gateway"}""")) {
            server.enqueue(MockResponse().setResponseCode(http).setBody(body))
            if(store.load("venue","payment")==null) flow.submit(pending()) else flow.retry()
            assertEquals(pending().body,store.load("venue","payment")!!.body)
            assertEquals(ExchangeSendPhase.UNCERTAIN,flow.state.value.phase)
            assertTrue(flow.state.value.locked)
        }
    }
    @Test fun `structured validation releases a 400 and key reuse is explicit`()=runBlocking {
        for ((code,body) in listOf(400 to """{"message":"invalid","details":[{"path":["body","taken"],"code":"too_small"}]}""",
            409 to """{"message":"reused","code":"IDEMPOTENCY_KEY_REUSED"}""")) {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            flow.submit(pending())
            assertNull(store.load("venue","payment"))
            if(code==409) assertEquals(TextosCambio.IDEMPOTENCY_KEY_REUSED,flow.state.value.message)
        }
    }
    @Test fun `error code and calculation are read from root rather than message`()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"changed","code":"EXCHANGE_DIFFERENCE_CHANGED","details":{"calculation":{"kind":"EVEN","differenceCents":0,"returnedCents":100,"takenCents":100,"lines":{"returned":[],"taken":[]}}}}"""))
        val error=runCatching { repo.create(pending().body) }.exceptionOrNull() as ExchangeApiException
        assertEquals(409,error.statusCode); assertEquals("EXCHANGE_DIFFERENCE_CHANGED",error.code)
        assertEquals(0,error.details!!.jsonObject["calculation"]!!.jsonObject["differenceCents"]!!.jsonPrimitive.int)
    }

    @Test fun `stale pending writer cannot forget that card authorization has started`()=runBlocking {
        store.save(pending().copy(cardStarted=true,cardRequestId="request"))
        store.save(pending())
        val saved=store.load("venue","payment")!!
        assertTrue(saved.cardStarted); assertEquals("request",saved.cardRequestId)
        assertTrue(runCatching { store.save(pending().copy(body="different")) }.isFailure)
        assertEquals(pending().body,store.load("venue","payment")!!.body)
    }

    private suspend fun assertDefinitiveRejection(status:Int, code:String?=null) {
        val body=buildJsonObject { put("message","rejected"); code?.let { put("code",it) } }.toString()
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        flow.submit(pending())
        assertEquals(ExchangeSendPhase.EDITING,flow.state.value.phase)
        assertEquals("rejected",flow.state.value.message)
        assertFalse(flow.state.value.locked)
        assertNull(flow.state.value.action)
        assertNull(flow.pending)
        assertNull(store.load("venue","payment"))
    }

    @Test fun `400 validation without details releases pending`()=runBlocking {
        assertDefinitiveRejection(400)
    }
    @Test fun `400 duplicate line releases pending`()=runBlocking {
        assertDefinitiveRejection(400,"EXCHANGE_DUPLICATE_LINE")
    }
    @Test fun `400 with another code releases pending`()=runBlocking {
        assertDefinitiveRejection(400,"OTHER")
    }
    @Test fun `404 releases pending`()=runBlocking {
        assertDefinitiveRejection(404)
    }
    @Test fun `422 disabled exchanges releases pending`()=runBlocking {
        assertDefinitiveRejection(422,"EXCHANGES_DISABLED")
    }
    @Test fun `422 unsellable taken item releases pending`()=runBlocking {
        assertDefinitiveRejection(422,"EXCHANGE_TAKEN_NOT_SELLABLE")
    }
    @Test fun `422 unavailable settlement releases pending`()=runBlocking {
        assertDefinitiveRejection(422,"EXCHANGE_SETTLEMENT_UNAVAILABLE")
    }
    @Test fun `server errors and nondefinitive conflict codes retain the exact pending body`()=runBlocking {
        for ((status,code) in listOf(500 to "INTERNAL_ERROR",503 to "UNAVAILABLE",
            409 to "OTHER",422 to "OTHER",404 to "ACTOR_MISMATCH")) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"$code","message":"retry later"}"""))
            if(flow.pending==null) flow.submit(pending()) else flow.retry()
            assertEquals(ExchangeSendPhase.UNCERTAIN,flow.state.value.phase)
            assertTrue(flow.state.value.locked)
            assertEquals(pending().body,store.load("venue","payment")!!.body)
        }
    }

}
