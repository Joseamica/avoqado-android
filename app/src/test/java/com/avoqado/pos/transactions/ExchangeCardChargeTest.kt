package com.avoqado.pos.transactions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.presentation.exchange.*
import io.mockk.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ExchangeCardChargeTest {
    @get:Rule val temporary=TemporaryFolder()
    private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private val terminal=mockk<TerminalPaymentService>(relaxed=true)
    private lateinit var server:MockWebServer
    private lateinit var flow:ExchangeSubmission
    private lateinit var card:ExchangeCardCharge
    private lateinit var store:ExchangePendingStore
    private lateinit var repo:ExchangeRepository
    @Before fun setup() {
        server=MockWebServer(); server.start()
        val storage=mockk<SecureStorage>(relaxed=true)
        every { storage.venueId } returns "venue"
        repo=ExchangeRepository(storage,OkHttpClient()).also { it.baseUrl=server.url("/api/v1").toString().trimEnd('/') }
        store=ExchangePendingStore(PreferenceDataStoreFactory.create(scope=scope,produceFile={temporary.root.resolve("card.preferences_pb")}))
        flow=ExchangeSubmission(repo,store); card=ExchangeCardCharge(terminal,flow)
        every { terminal.pendienteDeLaVenta(any()) } returns null
    }
    @After fun cleanup(){ scope.cancel(); server.shutdown() }
    private fun enqueue(status:String) { server.enqueue(MockResponse().setBody("""{"data":{"exchange":{"id":"exchange","status":"$status","originalOrderId":"old","originalOrderNumber":"ORD-1","newOrderId":"new","newOrderNumber":"ORD-2","returnedCents":100,"takenCents":225,"differenceCents":125,"amountToChargeCents":125,"settlement":{"kind":"COLLECT","method":"CARD_TERMINAL","amountCents":125},"lines":{"returned":[],"taken":[]}}}}""")) }
    private suspend fun reserve() {
        enqueue("PENDING_PAYMENT")
        flow.submit(PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"actor","settlement":{"terminalId":"terminal"}}"""))
        server.takeRequest()
    }
    @Test fun `card sends reserved amount zero tip skip review by existing service and completes`()=runBlocking {
        reserve()
        coEvery { terminal.sendPaymentToTerminal(terminalId="terminal",amountCents=125,tipCents=0,orderId="new",processedByStaffId="actor") } coAnswers {
            assertTrue(store.load("venue","payment")!!.cardStarted)
            TerminalPaymentResult.Success(paymentId="pay",requestId="request")
        }
        enqueue("COMPLETED"); card.run()
        assertEquals("/api/v1/mobile/venues/venue/exchanges/exchange/complete",server.takeRequest().path)
        assertEquals(ExchangeSendPhase.COMPLETED,flow.state.value.phase)
        coVerify(exactly=1) { terminal.sendPaymentToTerminal(terminalId="terminal",amountCents=125,tipCents=0,orderId="new",processedByStaffId="actor") }
        // skipReview is fixed true by TerminalPaymentService; its HTTP test fixes the actual bytes.
    }
    @Test fun `both never created and durable declined cancel and never recharge cancelled order`()=runBlocking {
        for(neverCreated in listOf(true,false)) {
            reserve(); coEvery { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) } returns TerminalPaymentResult.Error("declined","request",neverCreated)
            enqueue("CANCELLED"); card.run()
            assertTrue(server.takeRequest().path!!.endsWith("/cancel"))
            assertEquals(ExchangeSendPhase.CANCELLED,flow.state.value.phase)
            assertNull(flow.pending)
            card.run()
        }
        coVerify(exactly=2) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `undetermined resolves without cancelling or charging again including reopening`()=runBlocking {
        reserve()
        coEvery { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) } returns TerminalPaymentResult.Undetermined("unknown","request")
        coEvery { terminal.resolveOutcome("request") } returns TerminalPaymentResult.Undetermined("unknown","request")
        card.run(); card.run()
        assertEquals(1,server.requestCount)
        assertEquals(TextosCambio.COBRO_EN_REVISION,flow.state.value.message)
        assertTrue(flow.pending!!.cardStarted)
        coVerify(exactly=1) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
        coVerify(exactly=2) { terminal.resolveOutcome("request") }
    }
    @Test fun `cancel with charge exists rereads create while unresolved stays protected`()=runBlocking {
        reserve(); coEvery { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) } returns TerminalPaymentResult.Error("declined","request",true)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"EXCHANGE_CHARGE_EXISTS","message":"charged"}""")); enqueue("NEEDS_REVIEW")
        card.run()
        assertTrue(server.takeRequest().path!!.endsWith("/cancel")); assertTrue(server.takeRequest().path!!.endsWith("/exchanges"))
        assertEquals(ExchangeSendPhase.REVIEW,flow.state.value.phase)
        reserve()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"EXCHANGE_CHARGE_UNRESOLVED","message":"unknown"}"""))
        card.run(); assertTrue(flow.state.value.locked); assertNotNull(flow.pending)
    }
    @Test fun `reopening after card start never sends a second authorization without a local request`()=runBlocking {
        reserve(); flow.markCardStarted(); flow.open("venue","payment")
        enqueue("NEEDS_REVIEW"); card.run()
        assertTrue(server.takeRequest().path!!.endsWith("/complete"))
        assertEquals(ExchangeSendPhase.REVIEW,flow.state.value.phase)
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `existing request is resolved before a new charge and unresolved complete never cancels`()=runBlocking {
        reserve(); flow.markCardStarted()
        every { terminal.pendienteDeLaVenta("new") } returns "existing"
        coEvery { terminal.resolveOutcome("existing") } returns TerminalPaymentResult.Success(paymentId="pay",requestId="existing")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"EXCHANGE_CHARGE_UNRESOLVED","message":"unknown"}"""))
        card.run()
        assertTrue(server.takeRequest().path!!.endsWith("/complete"))
        assertNotNull(flow.pending)
        assertEquals(TextosCambio.COBRO_EN_REVISION,flow.state.value.message)
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }

    @Test fun `lost card start is cancelled only after server confirms no charge`()=runBlocking {
        reserve(); flow.markCardStarted(); flow.open("venue","payment")
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"EXCHANGE_NOT_CHARGED","message":"no charge"}"""))
        enqueue("CANCELLED"); card.run()
        assertTrue(server.takeRequest().path!!.endsWith("/complete"))
        assertTrue(server.takeRequest().path!!.endsWith("/cancel"))
        assertEquals(ExchangeSendPhase.CANCELLED,flow.state.value.phase)
        assertNull(flow.pending)
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }

    private suspend fun assertPositiveChargeRetained() {
        var requestSavedBeforeComplete=false
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse =
                if (request.path!!.endsWith("/complete")) {
                    requestSavedBeforeComplete=runBlocking { store.load("venue","payment")?.cardRequestId == "request" }
                    MockResponse().setResponseCode(409).setBody("""{"code":"EXCHANGE_NOT_CHARGED","message":"not visible yet"}""")
                } else MockResponse().setBody("""{"data":{"exchange":{"id":"exchange","status":"CANCELLED","originalOrderId":"old","originalOrderNumber":"ORD-1","newOrderId":"new","newOrderNumber":"ORD-2","returnedCents":100,"takenCents":225,"differenceCents":125,"settlement":{"kind":"COLLECT","method":"CARD_TERMINAL","amountCents":125},"lines":{"returned":[],"taken":[]}}}}""")
        }
        card.run()
        assertEquals("/api/v1/mobile/venues/venue/exchanges/exchange/complete",server.takeRequest().path)
        assertEquals(2,server.requestCount)
        assertTrue(requestSavedBeforeComplete)
        assertEquals(ExchangeSendPhase.UNCERTAIN,flow.state.value.phase)
        assertEquals(TextosCambio.COBRO_EN_REVISION,flow.state.value.message)
        assertTrue(flow.state.value.locked)
        assertEquals("request",store.load("venue","payment")!!.cardRequestId)

        flow=ExchangeSubmission(repo,store)
        flow.open("venue","payment")
        card=ExchangeCardCharge(terminal,flow)
        clearMocks(terminal,answers=false)
        coEvery { terminal.resolveOutcome("request") } returns TerminalPaymentResult.Success(paymentId="pay",requestId="request")
        card.run()
        assertEquals("/api/v1/mobile/venues/venue/exchanges/exchange/complete",server.takeRequest().path)
        assertEquals(3,server.requestCount)
        assertEquals(ExchangeSendPhase.UNCERTAIN,flow.state.value.phase)
        coVerify(exactly=1) { terminal.resolveOutcome("request") }
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
        verify(exactly=0) { terminal.reconocerCobro(any()) }
    }

    @Test fun `success followed by not charged stays under review and reopens the same request`()=runBlocking {
        reserve()
        coEvery { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) } returns
            TerminalPaymentResult.Success(paymentId="pay",requestId="request")
        assertPositiveChargeRetained()
    }

    @Test fun `resolved existing success cannot be cancelled by a late not charged response`()=runBlocking {
        reserve()
        flow.markCardStarted("request")
        coEvery { terminal.resolveOutcome("request") } returns TerminalPaymentResult.Success(paymentId="pay",requestId="request")
        assertPositiveChargeRetained()
    }

    @Test fun `success after an undetermined charge retains its request before completing`()=runBlocking {
        reserve()
        coEvery { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) } returns
            TerminalPaymentResult.Undetermined("unknown","request")
        coEvery { terminal.resolveOutcome("request") } returns TerminalPaymentResult.Success(paymentId="pay",requestId="request")
        assertPositiveChargeRetained()
    }

}
