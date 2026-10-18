package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.payment.data.model.*
import com.avoqado.pos.payment.domain.TenderTypeOption
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** VM/repositories, OkHttp/HTTP and file SQLite are real. DAO relay binds the production
 * result/hold/TTL SQL to JDBC; Room generation/reservation guards have separate real Room tests.
 * Never run this fixture against the app database. No transport or capturedPayments mock result. */
@OptIn(ExperimentalCoroutinesApi::class)
class Q15OnlinePaymentTest {
    @get:Rule val main = MainDispatcherRule()

    private class Fixture : AutoCloseable {
        val directory=Files.createTempDirectory("q15-online-owned").toFile()
        private val db:Connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("online.sqlite").absolutePath)
        val dao=mockk<PendingPaymentDao>(relaxed=true)
        val storage=mockk<SecureStorage>(relaxed=true)
        var venue=VENUE; var actor="original-staff"
        var beforeInsert:suspend (PendingPaymentEntity)->Unit={}
        val releases=mutableListOf<PendingPaymentEntity>()
        val released=CompletableDeferred<PendingPaymentEntity>()
        val entered=CompletableDeferred<RecordedRequest>()
        val answer=CountDownLatch(1)
        val server=MockWebServer()
        var body="""{"success":true,"data":{"id":"server-payment","orderId":"fast-order","amount":"35.00","tipAmount":"10.00","status":"COMPLETED"}}"""
        val cash:CashPaymentRepository
        val order:OrderRepository
        val client:OkHttpClient
        init {
            sql("CREATE TABLE pending_payments(id TEXT PRIMARY KEY,venueId TEXT NOT NULL,syncStatus TEXT NOT NULL,createdAt INTEGER NOT NULL, original TEXT NOT NULL,retryCount INTEGER NOT NULL DEFAULT 0,lastRetryAt INTEGER,lastError TEXT)")
            sql("CREATE TABLE pos_sync_intents(id TEXT PRIMARY KEY,venue_id TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,type TEXT NOT NULL)")
            Q15_MIGRATION_SQL.forEach { sql(it) }
            every { storage.venueId } answers { venue };every { storage.userId } answers { actor }
            every { storage.venueName } returns "FULLTEST-Q15";every { storage.userFirstName } returns "Prueba"
            every { storage.userLastName } returns "Propia";every { storage.userEmail } returns "fulltest@example.invalid"
            // Insert is only raw fixture storage, not a reimplementation of the Q15 writer/guard.
            // The actual CashPaymentRepository constructs every original capture in these tests.
            coEvery { dao.insert(any()) } coAnswers { val row=firstArg<PendingPaymentEntity>();beforeInsert(row);insert(row) }
            coEvery { dao.findForVenue(any(),any()) } answers { row(firstArg(),secondArg()) }
            coEvery { dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } answers {
                sql(Q15_PAYMENT_RESULT_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"aplicado" to if(arg<Boolean>(2)) 1 else 0,"motivo" to arg<String?>(3),"resultadoJson" to arg<String?>(4),"ahora" to arg<Long>(5)))
            }
            coEvery { dao.retenerPagoQ15(any(),any(),any(),any()) } answers { sql(Q15_PAYMENT_HOLD_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"motivo" to arg<String>(2),"ahora" to arg<Long>(3))) }
            coEvery { dao.borrarSiEnVuelo(any()) } answers {
                val r=rows().singleOrNull { it.id==firstArg<String>() }
                if(r!=null) { assertEquals("SYNCED",r.syncStatus);assertNotNull(r.q15ResultJson);releases+=r }
                val deleted=sql("DELETE FROM pending_payments WHERE id=:id AND syncStatus='EN_VUELO' AND 0",mapOf("id" to firstArg<String>()))
                if(r!=null) released.complete(r)
                deleted
            }
            coEvery { dao.getPendingPayments(any()) } answers { rows().filter { it.syncStatus=="PENDING" }.take(firstArg()) }
            coEvery { dao.deleteSynced(any()) } answers { sql(Q15_PAYMENT_TTL_SQL,mapOf("olderThan" to firstArg<Long>()));Unit }
            every { dao.getPendingCount() } returns flowOf(0);every { dao.getFailedCount() } returns flowOf(0)
            every { dao.observarEstado(any()) } answers { flowOf(rows().singleOrNull { it.id==firstArg<String>() }?.syncStatus) }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    entered.complete(request)
                    check(answer.await(5,TimeUnit.SECONDS)) { "El test no liberó su respuesta HTTP" }
                    return MockResponse().setResponseCode(200).setBody(body)
                }
            }
            server.start()
            client=OkHttpClient.Builder().addInterceptor { chain -> val request=chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            cash=CashPaymentRepository(storage,dao);order=OrderRepository(storage,client,dao)
        }
        @Synchronized fun sql(text:String,args:Map<String,Any?> = emptyMap()):Int=statement(text,args).use { it.executeUpdate() }
        @Synchronized private fun query(text:String,args:Map<String,Any?> = emptyMap()):List<Map<String,Any?>> =statement(text,args).use { s -> s.executeQuery().use { r -> buildList {
            while(r.next()) add((1..r.metaData.columnCount).associate { r.metaData.getColumnLabel(it) to r.getObject(it) })
        } } }
        private fun statement(text:String,args:Map<String,Any?>):java.sql.PreparedStatement {
            val values=mutableListOf<Any?>();val bound=Regex(":([a-zA-Z][a-zA-Z0-9]*)").replace(text){ values.add(args[it.groupValues[1]]);"?" }
            return db.prepareStatement(bound).also { s -> values.forEachIndexed { i,v -> s.setObject(i+1,v) } }
        }
        private fun insert(r:PendingPaymentEntity) {
            val original=JSONObject().put("staff",r.staffId).put("amount",r.amountCents).put("tip",r.tipCents).put("method",r.method)
                .put("kind",r.paymentType).put("order",r.orderId ?: JSONObject.NULL).put("split",r.splitType)
                .put("tender",r.tenderTypeId ?: JSONObject.NULL).put("revision",r.tenderRevision ?: JSONObject.NULL)
                .put("customer",r.customerId ?: JSONObject.NULL).put("request",r.orderRequestJson ?: JSONObject.NULL).toString()
            sql("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt,original,q15CaptureJson,capturedOrderRef) VALUES(:id,:v,:s,:t,:o,:c,:r)",
                mapOf("id" to r.id,"v" to r.venueId,"s" to r.syncStatus,"t" to r.createdAt,"o" to original,"c" to r.q15CaptureJson,"r" to r.capturedOrderRef))
        }
        private fun decode(r:Map<String,Any?>):PendingPaymentEntity {
            val p=JSONObject(r["original"] as String);fun nullable(k:String)=if(p.isNull(k)) null else p.getString(k)
            return PendingPaymentEntity(r["id"] as String,r["venueId"] as String,p.getString("staff"),p.getInt("amount"),p.getInt("tip"),p.getString("method"),p.getString("kind"),
                orderId=nullable("order"),splitType=p.getString("split"),tenderTypeId=nullable("tender"),tenderRevision=if(p.isNull("revision")) null else p.getInt("revision"),
                customerId=nullable("customer"),orderRequestJson=nullable("request"),syncStatus=r["syncStatus"] as String,
                q15CaptureJson=r["q15CaptureJson"] as String?,q15InitialResultJson=r["q15InitialResultJson"] as String?,q15ResultJson=r["q15ResultJson"] as String?,
                q15Reason=r["q15Reason"] as String?,q15DetectedAt=(r["q15DetectedAt"] as? Number)?.toLong(),capturedOrderRef=r["capturedOrderRef"] as String?)
        }
        fun rows()=query("SELECT * FROM pending_payments ORDER BY createdAt,id").map(::decode)
        fun row(v:String,id:String)=query("SELECT * FROM pending_payments WHERE venueId=:v AND id=:id",mapOf("v" to v,"id" to id)).singleOrNull()?.let(::decode)
        suspend fun arrival():RecordedRequest=withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
        suspend fun awaitRelease(id:String)=withContext(Dispatchers.Default) { withTimeout(5000) {
            assertEquals(id,released.await().id)
        } }
        fun vm():PaymentFlowViewModel {
            val terminal=mockk<TerminalPaymentService>(relaxed=true)
            every { terminal.intentoEnVuelo() } returns null;every { terminal.contextoDe(any()) } returns null
            coEvery { terminal.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
            val settings=mockk<TpvSettingsRepository>();every { settings.getCurrentSettings() } returns TpvSettings(showReviewScreen=false,showTipScreen=true)
            val area=mockk<AreaTicketRepository>(relaxed=true);every { area.session.current() } returns null
            return PaymentFlowViewModel(tableSession=com.avoqado.pos.tables.data.TableSession(),syncOutbox=mockk(relaxed=true),
                orderRepository=order,cashPaymentRepository=cash,tenderTypeRepository=mockk(relaxed=true),terminalPaymentService=terminal,
                tpvSettingsRepository=settings,paymentSyncService=mockk(relaxed=true),cashDrawerRepository=mockk(relaxed=true),
                kdsRepository=mockk(relaxed=true),kdsOrderBus=mockk(relaxed=true),printerService=mockk(relaxed=true),secureStorage=storage,
                comandaDispatcher=mockk(relaxed=true),comandasPendientesStore=ComandasPendientesStore(AlmacenEnMemoria()),replayDeComandas=mockk(relaxed=true),
                customerDisplay=com.avoqado.pos.customerdisplay.CustomerDisplayState(),areaTicketRepository=area,cancelacionDeCobro=mockk(relaxed=true),
                savedStateHandle=androidx.lifecycle.SavedStateHandle())
        }
        fun TestScope.verifyNoReplayOfApplied() {
            val network=mockk<ConnectivityMonitor>(relaxed=true)
            every { network.isConnected } returns MutableStateFlow(true);every { network.isServerReachable } returns MutableStateFlow(true)
            val drawer=mockk<CashDrawerRepository>(relaxed=true);coEvery { drawer.sincronizarCajonPrimero() } returns true
            val service=PaymentSyncService(dao,storage,client,network,drawer,mockk<SyncOutbox>(relaxed=true))
            service.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
            try { service.start();runCurrent();service.syncNow();runCurrent();assertEquals(1,server.requestCount) } finally { service.stop() }
        }
        override fun close() { answer.countDown();server.shutdown();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown();db.close();directory.deleteRecursively() }
    }
    private fun open(vm:PaymentFlowViewModel,cents:Int=10000,part:Int=4000,tip:Int=500) {
        vm.setSplitConfig("CUSTOMAMOUNT",customAmountCents=part)
        vm.startPaymentFlow(CartState(items=listOf(CartItem(id="owned-line",type=CartItemType.CustomAmount,name="Importe de prueba",unitPrice=cents))),customerId="original-customer")
        vm.submitTip(tip);vm.selectPaymentMethod(PaymentMethod.CASH)
    }
    private suspend fun terminalState(vm:PaymentFlowViewModel)=withContext(Dispatchers.Default) { withTimeout(5000) {
        vm.state.first { it is PaymentFlowState.Success || it is PaymentFlowState.Error }
    } }
    @Test fun `ORDER real preserva original y resultado aplicado antes soltar y no reproduce`() = runTest {
        Fixture().use { f ->
            val key="original-order-payment";val tender=TenderTypeOption("original-tender",7,"Vale de prueba",false,"CASH",true,"MORE",1)
            val request=CreateOrderRequest(emptyList(),subtotal=10000,tip=500,total=10500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,3,orderId="original-order",customerId="original-customer",idempotencyKey=key,
                tenderType=tender,amountCents=4000,tipCents=500,splitType="CUSTOMAMOUNT",venueId=VENUE)
            f.body="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"original-order","amount":3500,"tipAmount":1000,"status":"COMPLETED","tenderTypeId":"original-tender","tenderRevision":7,"splitType":"CUSTOMAMOUNT"}}"""
            val payment=async { f.order.recordCashPayment("original-order",4000,"original-staff",500,"CUSTOMAMOUNT",key,tenderType=tender,capturedVenueId=VENUE) }
            runCurrent();val http=f.arrival();val reserved=f.row(VENUE,key)!!
            assertEquals("EN_VUELO",reserved.syncStatus);assertNull(reserved.q15ResultJson)
            val original=ConciliacionDelCobro.leerCaptura(reserved.q15CaptureJson,VENUE,key)!!
            assertEquals(4000,original.amountCents);assertEquals(500,original.tipCents);assertEquals("original-customer",original.customerId)
            val sent=JSONObject(http.body.readUtf8());assertEquals(key,sent.getString("idempotencyKey"));assertEquals(4000,sent.getInt("amount"));assertEquals(500,sent.getInt("tip"))
            assertEquals("CUSTOMAMOUNT",sent.getString("splitType"));assertEquals("original-staff",sent.getString("staffId"));assertEquals("original-tender",sent.getString("tenderTypeId"));assertEquals(7,sent.getInt("tenderRevision"));assertFalse(sent.has("method"))
            f.venue="OTHER-VENUE";f.answer.countDown();assertTrue(payment.await().isSuccess)
            val applied=f.row(VENUE,key)!!;assertEquals("SYNCED",applied.syncStatus);assertEquals(ConciliacionDelCobro.DIFERENCIA,applied.q15Reason)
            assertEquals(reserved.q15CaptureJson,applied.q15CaptureJson);assertNotNull(applied.q15InitialResultJson);assertEquals(applied.q15InitialResultJson,applied.q15ResultJson)
            assertNull(f.row("OTHER-VENUE",key));f.cash.soltarReserva(key);assertEquals(1,f.releases.size)
            with(f) { verifyNoReplayOfApplied() };assertEquals(applied.q15CaptureJson,f.row(VENUE,key)?.q15CaptureJson)
        }
    }
    @Test fun `FAST VM real guarda antes POST y conserva diferencia antes liberacion`() = runTest {
        Fixture().use { f -> val vm=f.vm();open(vm);vm.processCashPayment(5000);runCurrent()
            val http=f.arrival();val reserved=f.rows().single();assertEquals("EN_VUELO",reserved.syncStatus)
            val capture=ConciliacionDelCobro.leerCaptura(reserved.q15CaptureJson,VENUE,reserved.id)!!
            assertEquals(4000,capture.amountCents);assertEquals(500,capture.tipCents);assertEquals("CUSTOMAMOUNT",capture.splitType)
            val sent=JSONObject(http.body.readUtf8());assertEquals(reserved.id,sent.getString("idempotencyKey"));assertEquals(4000,sent.getInt("amount"));assertEquals(500,sent.getInt("tip"));assertEquals("original-customer",sent.getString("customerId"))
            f.answer.countDown();assertTrue(terminalState(vm) is PaymentFlowState.Success);runCurrent();f.awaitRelease(reserved.id)
            val applied=f.rows().single();assertEquals("SYNCED",applied.syncStatus);assertEquals(ConciliacionDelCobro.DIFERENCIA,applied.q15Reason)
            assertEquals(reserved.q15CaptureJson,applied.q15CaptureJson);assertNotNull(applied.q15ResultJson);assertEquals(1,f.releases.size)
            with(f) { verifyNoReplayOfApplied() }
        }
    }
    @Test fun `fallo reserva VM es visible sin POST sin Success y permite mismo intento`() = runTest {
        Fixture().use { f -> val vm=f.vm();open(vm);var failed:PendingPaymentEntity?=null
            f.beforeInsert={ failed=it;throw java.io.IOException("disco propio") }
            vm.processCashPayment(5000);runCurrent()
            val error=vm.state.value as? PaymentFlowState.Error;assertNotNull(error);assertTrue(error!!.message.contains("No se pudo guardar el cobro"))
            assertEquals(0,f.server.requestCount);assertTrue(f.rows().isEmpty());assertTrue(f.releases.isEmpty())
            f.beforeInsert={};vm.processCashPayment(5000);runCurrent();val http=f.arrival();val row=f.rows().single()
            assertEquals(failed!!.id,row.id);assertEquals(failed!!.q15CaptureJson,row.q15CaptureJson)
            assertEquals(4000,row.amountCents);assertEquals(500,row.tipCents);assertEquals(row.id,JSONObject(http.body.readUtf8()).getString("idempotencyKey"))
            f.answer.countDown();assertTrue(terminalState(vm) is PaymentFlowState.Success);runCurrent();f.awaitRelease(row.id)
        }
    }
    @Test fun `fallo reserva A tardio no cambia venta B ni libera su reserva`() = runTest {
        Fixture().use { f -> val vm=f.vm();val a=CompletableDeferred<Unit>();val b=CompletableDeferred<Unit>()
            f.beforeInsert={ row -> if(row.venueId==VENUE) { a.await();throw java.io.IOException("A propia") } else b.await() }
            open(vm);vm.processCashPayment(5000);runCurrent();assertEquals(0,f.server.requestCount)
            f.venue="OTHER-VENUE";f.actor="new-staff";open(vm,cents=8000,part=6000,tip=0);vm.processCashPayment(6000);runCurrent()
            val stateB=vm.state.value;assertTrue(stateB is PaymentFlowState.Processing)
            a.complete(Unit);runCurrent();assertEquals(stateB,vm.state.value);assertTrue(f.releases.isEmpty());assertEquals(0,f.server.requestCount)
            f.body="""{"success":true,"data":{"id":"server-B","orderId":"order-B","amount":"60.00","tipAmount":"0.00","status":"COMPLETED"}}"""
            b.complete(Unit);runCurrent();val http=f.arrival();val reserved=f.rows().single();assertEquals("OTHER-VENUE",reserved.venueId)
            assertEquals("new-staff",reserved.staffId);assertEquals(6000,reserved.amountCents);assertEquals(0,reserved.tipCents)
            assertEquals(reserved.id,JSONObject(http.body.readUtf8()).getString("idempotencyKey"));f.answer.countDown()
            assertTrue(terminalState(vm) is PaymentFlowState.Success);runCurrent();f.awaitRelease(reserved.id);assertEquals(1,f.releases.size)
            assertEquals(reserved.id,f.releases.single().id);assertEquals("OTHER-VENUE",f.releases.single().venueId)
        }
    }
    @Test fun `fallo al conservar respuesta online no pierde captura ni afirma exito`() = runTest {
        Fixture().use { f -> val key="result-store-failure";val request=CreateOrderRequest(emptyList(),subtotal=4000,tip=500,total=4500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,null,idempotencyKey=key,amountCents=4000,tipCents=500,venueId=VENUE)
            val original=f.row(VENUE,key)!!
            f.sql("CREATE TRIGGER result_failure BEFORE UPDATE OF q15ResultJson ON pending_payments BEGIN SELECT RAISE(FAIL,'owned store failure'); END")
            val payment=async { f.order.recordFastCashPayment(4000,"original-staff",tip=500,idempotencyKey=key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown();assertTrue(payment.await().isFailure)
            val preserved=f.row(VENUE,key)!!;assertEquals("EN_VUELO",preserved.syncStatus);assertEquals(original.q15CaptureJson,preserved.q15CaptureJson)
            assertNull(preserved.q15ResultJson);assertTrue(f.releases.isEmpty());assertEquals(1,f.server.requestCount)
        }
    }
    // Q16 ronda 1 (controlador): la regla del TOTAL es SÓLO de la cola sin red. El efectivo EN LÍNEA de mostrador reserva su
    // captura con orderId nulo ANTES de crear la orden (`reservarEfectivo`); aunque el servidor la valore más cara, queda como hoy.
    @Test fun `ORDER en linea reservado sin orden con precio que subio no estrena aviso de total`() = runTest {
        Fixture().use { f ->
            val key="online-sin-orden"
            val request=CreateOrderRequest(listOf(OrderItemRequest(productId="product-A",name="Artículo A",quantity=1,unitPrice=10000)),subtotal=10000,tip=500,total=10500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",10500,0,null,orderExternalId=key,idempotencyKey=key,amountCents=10000,tipCents=500,venueId=VENUE)
            val reserved=f.row(VENUE,key)!!;assertNull(ConciliacionDelCobro.leerCaptura(reserved.q15CaptureJson,VENUE,key)!!.orderId)
            f.body="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"o-nueva","amount":10000,"tipAmount":500,"method":"CASH","status":"COMPLETED","orderTotalCents":12500,"remainingBalanceCents":2000,"orderPaymentStatus":"PARTIAL"}}"""
            val payment=async { f.order.recordCashPayment("o-nueva",10000,"original-staff",500,"FULLPAYMENT",key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown();assertTrue(payment.await().isSuccess)
            val applied=f.row(VENUE,key)!!
            assertEquals("SYNCED",applied.syncStatus);assertNull(applied.q15Reason)
            assertFalse(applied.q15ResultJson!!.contains("totalDeLaOrden"));assertFalse(applied.q15ResultJson!!.contains("totalAlCobrarCents"))
        }
    }
    /** Founder 18-oct («A»): un precio que el servidor bajó EN LÍNEA ya se le dijo al cajero; no es alerta para el gerente. */
    @Test fun `ORDER en linea con el importe que el cajero acepto por cambio de precio no es diferencia y la captura no cambia`() = runTest {
        Fixture().use { f ->
            val key="online-precio-bajo"
            val request=CreateOrderRequest(listOf(OrderItemRequest(productId="product-A",name="Artículo A",quantity=1,unitPrice=8000)),subtotal=8000,tip=0,total=8000,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",10000,2000,null,orderExternalId=key,idempotencyKey=key,amountCents=8000,tipCents=0,venueId=VENUE)
            val reserved=f.row(VENUE,key)!!
            f.body="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"o-nueva","amount":6000,"tipAmount":0,"method":"CASH","status":"COMPLETED"}}"""
            val payment=async { f.order.recordCashPayment("o-nueva",6000,"original-staff",0,"FULLPAYMENT",key,capturedVenueId=VENUE,importeAceptadoCents=6000) }
            runCurrent();f.arrival();f.answer.countDown();assertTrue(payment.await().isSuccess)
            val applied=f.row(VENUE,key)!!
            assertEquals("SYNCED",applied.syncStatus);assertNull(applied.q15Reason)
            assertEquals("la captura original es evidencia y no se reescribe",reserved.q15CaptureJson,applied.q15CaptureJson)
            assertEquals(8000,ConciliacionDelCobro.leerCaptura(applied.q15CaptureJson,VENUE,key)!!.amountCents)
        }
    }
    @Test fun `ORDER en linea que registra otro importe del aceptado sigue siendo diferencia`() = runTest {
        Fixture().use { f ->
            val key="online-precio-otro"
            val request=CreateOrderRequest(listOf(OrderItemRequest(productId="product-A",name="Artículo A",quantity=1,unitPrice=8000)),subtotal=8000,tip=0,total=8000,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",10000,2000,null,orderExternalId=key,idempotencyKey=key,amountCents=8000,tipCents=0,venueId=VENUE)
            f.body="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"o-nueva","amount":5500,"tipAmount":0,"method":"CASH","status":"COMPLETED"}}"""
            val payment=async { f.order.recordCashPayment("o-nueva",6000,"original-staff",0,"FULLPAYMENT",key,capturedVenueId=VENUE,importeAceptadoCents=6000) }
            runCurrent();f.arrival();f.answer.countDown();assertTrue(payment.await().isSuccess)
            assertEquals(ConciliacionDelCobro.DIFERENCIA,f.row(VENUE,key)!!.q15Reason)
        }
    }
    companion object { private const val VENUE="FULLTEST-Q15-ORIGINAL" }
}
