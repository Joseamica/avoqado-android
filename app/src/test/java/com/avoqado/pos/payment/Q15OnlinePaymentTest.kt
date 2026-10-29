package com.avoqado.pos.payment

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel

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
    /** Una prueba que se cuelga FALLA con la pila del hilo atorado, en vez de colgar la corrida entera (como Q16PrecioCapturadoTest). */
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private class Fixture(retenerSello: Boolean = false) : AutoCloseable {
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
        val selloEntrado=CompletableDeferred<RecordedRequest>()
        val respuestaDelSello=CountDownLatch(if (retenerSello) 1 else 0)
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
            // D2 v7: la SQL de producción, sobre el mismo SQLite.
            coEvery { dao.descartarReservaRechazada(any()) } answers { sql(DESCARTAR_RESERVA_RECHAZADA_SQL,mapOf("id" to firstArg<String>())) }
            coEvery { dao.enVueloAPendiente(any()) } answers { sql(EN_VUELO_A_PENDIENTE_SQL,mapOf("id" to firstArg<String>())) }
            coEvery { dao.enVueloDelVenue(any()) } answers { rows().filter { it.venueId==firstArg<String>() && it.syncStatus=="EN_VUELO" } }
            coEvery { dao.getPendingPayments(any()) } answers { rows().filter { it.syncStatus=="PENDING" }.take(firstArg()) }
            coEvery { dao.deleteSynced(any()) } answers { sql(Q15_PAYMENT_TTL_SQL,mapOf("olderThan" to firstArg<Long>()));Unit }
            every { dao.getPendingCount() } returns flowOf(0);every { dao.getFailedCount() } returns flowOf(0)
            every { dao.observarEstado(any()) } answers { flowOf(rows().singleOrNull { it.id==firstArg<String>() }?.syncStatus) }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if (request.path?.substringBefore('?')?.endsWith("/loyalty") == true) {
                        selloEntrado.complete(request)
                        check(respuestaDelSello.await(5,TimeUnit.SECONDS)) { "La prueba no liberó su GET de sello" }
                    }
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
        /** Los ViewModels de la prueba: al cerrar se cancela su scope, como `onCleared` al salir de la pantalla. Sin esto, lo que
         *  sigue vivo tras la prueba (la consulta del sello de una venta completa) truena en la SIGUIENTE como
         *  `UncaughtExceptionsBeforeTest` (D2 etapa 2: el vehículo FAST ahora es una venta completa con cliente). */
        private val vms=mutableListOf<PaymentFlowViewModel>()
        fun vm():PaymentFlowViewModel = crearVm().also { vms+=it }
        private fun crearVm():PaymentFlowViewModel {
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
        /** [esperadas]: lo que ya llegó al servidor antes de sincronizar; la cola no debe sumar ni una petición más. */
        fun TestScope.verifyNoReplayOfApplied(esperadas:Int=1) {
            val network=mockk<ConnectivityMonitor>(relaxed=true)
            every { network.isConnected } returns MutableStateFlow(true);every { network.isServerReachable } returns MutableStateFlow(true)
            val drawer=mockk<CashDrawerRepository>(relaxed=true);coEvery { drawer.sincronizarCajonPrimero() } returns true
            val service=PaymentSyncService(dao,storage,client,network,drawer,mockk<SyncOutbox>(relaxed=true),cash)
            service.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
            try { service.start();runCurrent();service.syncNow();runCurrent();assertEquals(esperadas,server.requestCount) } finally { service.stop() }
        }
        // Cancelar no espera el retorno de IO: todos los scopes deben terminar antes de retirar Main.
        suspend fun cerrarTrabajosPropios() {
            val trabajos=vms.map { it.viewModelScope.coroutineContext[Job]!! }
            trabajos.forEach { it.cancel() }
            trabajos.forEach { it.join() }
        }
        override fun close() { answer.countDown();respuestaDelSello.countDown();runBlocking { cerrarTrabajosPropios() };server.shutdown();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown();db.close();directory.deleteRecursively() }
    }
    // D2 etapa 2 (27-oct): estas pruebas son del canal FAST. Una venta rápida DIVIDIDA ya lleva orden, así que el vehículo es una
    // venta rápida de UNA sola parte por el importe que antes era la parte.
    private fun open(vm:PaymentFlowViewModel,part:Int=4000,tip:Int=500) {
        vm.setSplitType("FULLPAYMENT")
        vm.startPaymentFlow(CartState(items=listOf(CartItem(id="owned-line",type=CartItemType.CustomAmount,name="Importe de prueba",unitPrice=part))),customerId="original-customer")
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
            assertEquals(4000,capture.amountCents);assertEquals(500,capture.tipCents);assertEquals("FULLPAYMENT",capture.splitType)
            val sent=JSONObject(http.body.readUtf8());assertEquals(reserved.id,sent.getString("idempotencyKey"));assertEquals(4000,sent.getInt("amount"));assertEquals(500,sent.getInt("tip"));assertEquals("original-customer",sent.getString("customerId"))
            f.answer.countDown();assertTrue(terminalState(vm) is PaymentFlowState.Success);runCurrent();f.awaitRelease(reserved.id)
            val applied=f.rows().single();assertEquals("SYNCED",applied.syncStatus);assertEquals(ConciliacionDelCobro.DIFERENCIA,applied.q15Reason)
            assertEquals(reserved.q15CaptureJson,applied.q15CaptureJson);assertNotNull(applied.q15ResultJson);assertEquals(1,f.releases.size)
            // D2 etapa 2: la venta ya es completa y con cliente, así que tras el cobro se consulta su sello (una petición más).
            with(f) { verifyNoReplayOfApplied(esperadas=2) }
        }
    }
    @Test fun `fallo reserva VM es visible sin POST sin Success y permite mismo intento`() = runTest {
        Fixture().use { f -> val vm=f.vm();open(vm);var failed:PendingPaymentEntity?=null
            f.beforeInsert={ failed=it;throw java.io.IOException("disco propio") }
            vm.processCashPayment(5000);runCurrent()
            val error=vm.state.value as? PaymentFlowState.Error;assertNotNull(error);assertTrue(error!!.message.contains("No se pudo guardar el cobro"))
            assertEquals(0,f.server.requestCount);assertTrue(f.rows().isEmpty());assertTrue(f.releases.isEmpty())
            f.beforeInsert={};vm.processCashPayment(5000);runCurrent();val http=f.arrival();val row=f.rows().single()
            assertEquals(failed!!.id,row.id)
            // Codex r11 #1: la captura es la misma salvo `primeraCapturaEn`, que es la hora del primer intento que llegó al disco (el
            // primero se cayó antes de guardar y antes del POST: no pudo dejar gemela en el servidor). No es evidencia: `evaluar` no la mira.
            fun sinHora(json:String?)=ConciliacionDelCobro.leerCaptura(json,VENUE,row.id)!!.copy(primeraCapturaEn=null)
            assertEquals(sinHora(failed!!.q15CaptureJson),sinHora(row.q15CaptureJson))
            assertTrue(ConciliacionDelCobro.leerCaptura(row.q15CaptureJson,VENUE,row.id)!!.primeraCapturaEn!! <= row.createdAt)
            assertEquals(4000,row.amountCents);assertEquals(500,row.tipCents);assertEquals(row.id,JSONObject(http.body.readUtf8()).getString("idempotencyKey"))
            f.answer.countDown();assertTrue(terminalState(vm) is PaymentFlowState.Success);runCurrent();f.awaitRelease(row.id)
        }
    }
    @Test fun `fallo reserva A tardio no cambia venta B ni libera su reserva`() = runTest {
        Fixture().use { f -> val vm=f.vm();val a=CompletableDeferred<Unit>();val b=CompletableDeferred<Unit>()
            f.beforeInsert={ row -> if(row.venueId==VENUE) { a.await();throw java.io.IOException("A propia") } else b.await() }
            open(vm);vm.processCashPayment(5000);runCurrent();assertEquals(0,f.server.requestCount)
            f.venue="OTHER-VENUE";f.actor="new-staff";open(vm,part=6000,tip=0);vm.processCashPayment(6000);runCurrent()
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
    /** Codex r14 A5: el 2xx de la orden se comprobó y lo local falló después: el respaldo conoce lo que el servidor registró. */
    @Test fun `2xx confirmado de la orden con el guardado local fallido trae el importe que registro el servidor`() = runTest {
        Fixture().use { f -> val key="order-result-store-failure"
            val request=CreateOrderRequest(emptyList(),subtotal=4000,tip=500,total=4500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,null,orderId="original-order",idempotencyKey=key,amountCents=4000,tipCents=500,venueId=VENUE)
            f.sql("CREATE TRIGGER result_failure BEFORE UPDATE OF q15ResultJson ON pending_payments BEGIN SELECT RAISE(FAIL,'owned store failure'); END")
            f.body="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"original-order","amount":3000,"tipAmount":500,"status":"COMPLETED","splitType":"FULLPAYMENT"}}"""
            val payment=async { f.order.recordCashPayment("original-order",4000,"original-staff",500,"FULLPAYMENT",key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown();val outcome=payment.await()
            val error=outcome.exceptionOrNull() as? OrderRepository.CobroRegistradoSinGuardar
            assertNotNull("es cobrado, no un error cancelable",error)
            assertEquals("lo que el servidor registró: 30.00 + 5.00",3500,error!!.importeCobradoCents)
        }
    }
    @Test fun `fallo al conservar respuesta online no pierde captura ni afirma exito`() = runTest {
        Fixture().use { f -> val key="result-store-failure";val request=CreateOrderRequest(emptyList(),subtotal=4000,tip=500,total=4500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,null,idempotencyKey=key,amountCents=4000,tipCents=500,venueId=VENUE)
            val original=f.row(VENUE,key)!!
            f.sql("CREATE TRIGGER result_failure BEFORE UPDATE OF q15ResultJson ON pending_payments BEGIN SELECT RAISE(FAIL,'owned store failure'); END")
            val payment=async { f.order.recordFastCashPayment(4000,"original-staff",tip=500,idempotencyKey=key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown();val outcome=payment.await();assertTrue(outcome.isFailure)
            // D2 v7 punto 7: el servidor contestó 2xx. No es un error cancelable: el VM lo trata como cobrado y encolado.
            assertTrue(outcome.exceptionOrNull() is OrderRepository.CobroRegistradoSinGuardar)
            val preserved=f.row(VENUE,key)!!;assertEquals("EN_VUELO",preserved.syncStatus);assertEquals(original.q15CaptureJson,preserved.q15CaptureJson)
            assertNull(preserved.q15ResultJson);assertTrue(f.releases.isEmpty());assertEquals(1,f.server.requestCount)
        }
    }
    /**
     * Guarda Q15/H2 (11-oct) que D2 v7 NO cambia (ruling del coordinador, 24-oct; espejo de iOS
     * `testIncompleteTwoHundredKeepsCaptureVisibleAndCreatesNoDrawerEvent`): un 2xx cuyo cuerpo NO confirma el cobro puede venir
     * de un intermediario. No es «2xx = cobrado»: la fila queda en la cola con «resultado no comprobado», la pantalla muestra el
     * error de hoy y «Cancelar» no la borra (ya no está EN_VUELO). Sólo un 2xx confirmado con lo local fallido es cobrado.
     */
    @Test fun `2xx que no confirma el cobro sigue como hoy - error visible, en la cola por revisar y Cancelar no la borra`() = runTest {
        Fixture().use { f -> f.body="""{"success":true}"""
            val vm=f.vm();open(vm);vm.processCashPayment(5000);runCurrent()
            f.arrival();f.answer.countDown()
            val error=terminalState(vm) as? PaymentFlowState.Error;assertNotNull(error)
            assertTrue(error!!.message.contains("por confirmar"))
            val held=f.rows().single();assertEquals("PENDING",held.syncStatus);assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,held.q15Reason)
            vm.cancelarVenta();runCurrent()
            val after=f.rows().single();assertEquals("PENDING",after.syncStatus);assertEquals(held.q15CaptureJson,after.q15CaptureJson)
        }
    }
    /** D2 v7 punto 7, de punta a punta: un 2xx CONFIRMADO cuyo resultado no se pudo escribir es cobrado (encolado), nunca un error cancelable. */
    @Test fun `VM 2xx confirmado con el guardado local fallido muestra cobrado encolado y la fila pasa a PENDING`() = runTest {
        Fixture().use { f ->
            f.sql("CREATE TRIGGER result_failure BEFORE UPDATE OF q15ResultJson ON pending_payments BEGIN SELECT RAISE(FAIL,'owned store failure'); END")
            val vm=f.vm();open(vm);vm.processCashPayment(5000);runCurrent()
            f.arrival();f.answer.countDown()
            val exito=terminalState(vm) as? PaymentFlowState.Success;assertNotNull(exito);assertTrue(exito!!.isQueued)
            val row=f.rows().single();assertEquals("PENDING",row.syncStatus);assertEquals(ColaDelCobro.Pagos(row.id),exito.colaDelCobro)
            vm.cancelarVenta();runCurrent();assertEquals("PENDING",f.rows().single().syncStatus)
        }
    }
    /**
     * Decisión del coordinador (24-oct): el DOBLE fallo —2xx que no confirma el cobro Y su escritura también falla— no es «cobrado».
     * Sigue como hoy: error visible y marca «resultado no comprobado» (aquí por la retención de respaldo), y la cola no la da por
     * cobrada aunque quede EN_VUELO y sin dueño.
     */
    @Test fun `doble fallo - 2xx sin comprobar cuyo guardado tambien falla sigue como no comprobado y la cola no lo da por cobrado`() = runTest {
        Fixture().use { f -> f.body="""{"success":true}"""
            f.sql("CREATE TRIGGER result_failure BEFORE UPDATE OF q15ResultJson ON pending_payments BEGIN SELECT RAISE(FAIL,'owned store failure'); END")
            val vm=f.vm();open(vm);vm.processCashPayment(5000);runCurrent()
            f.arrival();f.answer.countDown()
            val error=terminalState(vm) as? PaymentFlowState.Error;assertNotNull("no se pinta «cobrado»",error)
            assertTrue(error!!.message.contains("por confirmar"))
            val held=f.rows().single();assertEquals("EN_VUELO",held.syncStatus);assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,held.q15Reason)
            // Ronda 1 (I1): «Cancelar» NO borra la evidencia de un 2xx no comprobado: se suelta y queda en la revisión Q15, como hoy.
            vm.cancelarVenta();runCurrent()
            val kept=f.rows().single();assertEquals("EN_VUELO",kept.syncStatus);assertEquals(held.q15CaptureJson,kept.q15CaptureJson)
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,kept.q15Reason)
            open(vm,part=6000,tip=0) // otra venta: suelta la propiedad
            assertEquals(0,f.cash.darPorCobradasLasAbandonadas());assertEquals("EN_VUELO",f.rows().single().syncStatus)
        }
    }
    /**
     * Ronda final (A4, regla única): un 2xx cuya captura no se pudo LEER tampoco comprobó el cobro. Error de hoy y retención de
     * respaldo con «resultado no comprobado»: «Cancelar» sólo la suelta (no la borra) y el barrido no la toma.
     */
    @Test fun `2xx con la captura ilegible - error, retenida por revisar y Cancelar no la borra`() = runTest {
        Fixture().use { f ->
            val vm=f.vm();open(vm);vm.processCashPayment(5000);runCurrent()
            f.arrival()
            coEvery { f.dao.findForVenue(any(),any()) } throws IllegalStateException("disco ilegible")
            f.answer.countDown()
            val error=terminalState(vm) as? PaymentFlowState.Error;assertNotNull("no se pinta «cobrado»",error)
            val held=f.rows().single();assertEquals("EN_VUELO",held.syncStatus);assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,held.q15Reason)
            vm.cancelarVenta();runCurrent()
            assertEquals("«Cancelar» no la borra","EN_VUELO",f.rows().single().syncStatus)
            open(vm,part=6000,tip=0)
            assertEquals(0,f.cash.darPorCobradasLasAbandonadas());assertEquals("EN_VUELO",f.rows().single().syncStatus)
        }
    }
    @Test fun `doble fallo en el repositorio devuelve ResultadoNoComprobado, nunca CobroRegistradoSinGuardar`() = runTest {
        Fixture().use { f -> val key="double-failure";val request=CreateOrderRequest(emptyList(),subtotal=4000,tip=500,total=4500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,null,idempotencyKey=key,amountCents=4000,tipCents=500,venueId=VENUE)
            f.sql("CREATE TRIGGER result_failure BEFORE UPDATE OF q15ResultJson ON pending_payments BEGIN SELECT RAISE(FAIL,'owned store failure'); END")
            f.body="""{"success":true}"""
            val payment=async { f.order.recordFastCashPayment(4000,"original-staff",tip=500,idempotencyKey=key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown();val outcome=payment.await()
            assertTrue(outcome.exceptionOrNull() is OrderRepository.ResultadoNoComprobado)
            assertFalse(outcome.exceptionOrNull() is OrderRepository.CobroRegistradoSinGuardar)
        }
    }
    /** El 2xx SÍ comprobó el cobro y su escritura no quedó (0 filas): es cobrado sin guardar, no un error cancelable. */
    @Test fun `2xx comprobado cuya escritura no queda devuelve CobroRegistradoSinGuardar`() = runTest {
        Fixture().use { f -> val key="result-not-written";val request=CreateOrderRequest(emptyList(),subtotal=4000,tip=500,total=4500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,null,idempotencyKey=key,amountCents=4000,tipCents=500,venueId=VENUE)
            coEvery { f.dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } returns 0
            val payment=async { f.order.recordFastCashPayment(4000,"original-staff",tip=500,idempotencyKey=key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown()
            assertTrue(payment.await().exceptionOrNull() is OrderRepository.CobroRegistradoSinGuardar)
        }
    }
    @Test fun `2xx que no confirma el cobro no es CobroRegistradoSinGuardar en el repositorio`() = runTest {
        Fixture().use { f -> val key="result-unverified";val request=CreateOrderRequest(emptyList(),subtotal=4000,tip=500,total=4500,paymentMethod="CASH")
            f.cash.reservarCobro(request,"original-staff",5000,500,null,idempotencyKey=key,amountCents=4000,tipCents=500,venueId=VENUE)
            f.body="""{"success":true}"""
            val payment=async { f.order.recordFastCashPayment(4000,"original-staff",tip=500,idempotencyKey=key,capturedVenueId=VENUE) }
            runCurrent();f.arrival();f.answer.countDown();val outcome=payment.await()
            assertTrue(outcome.exceptionOrNull() is OrderRepository.ResultadoNoComprobado)
            assertFalse(outcome.exceptionOrNull() is OrderRepository.CobroRegistradoSinGuardar)
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
    /** Task13: no modifica ningún oráculo económico. */
    @Test fun `el cierre no termina mientras la GET real del sello sigue en IO`() = runTest {
        val f=Fixture(retenerSello=true)
        val vm=f.vm()
        val trabajo=vm.viewModelScope.coroutineContext[Job]!!
        var cierre: Job?=null
        try {
            open(vm)
            vm.processCashPayment(5000)
            runCurrent()
            f.arrival()
            f.answer.countDown()
            assertTrue(terminalState(vm) is PaymentFlowState.Success)
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { f.selloEntrado.await() } }
            // UNDISPATCHED ejecuta hasta retornar o suspender: no hay espera por tiempo para decidir el RED.
            cierre=launch(start=CoroutineStart.UNDISPATCHED) { f.cerrarTrabajosPropios() }
            assertTrue("premisa: cancelar ya fue solicitado", trabajo.isCancelled)
            assertFalse("premisa: la GET retenida todavía pertenece al scope", trabajo.isCompleted)
            assertFalse("el cierre debe esperar al scope antes de devolver el control a resetMain", cierre.isCompleted)
            f.respuestaDelSello.countDown()
            cierre.join()
            assertTrue("al volver del cierre, todas sus tareas finalizaron", trabajo.isCompleted)
        } finally {
            // Incluso RED libera el HTTP real y espera su scope ANTES de MainDispatcherRule.finished.
            f.respuestaDelSello.countDown()
            cierre?.join()
            trabajo.cancelAndJoin()
            f.close()
        }
    }

}
