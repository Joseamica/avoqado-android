package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.*
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

/** Servicio, HTTP/OkHttp y SQLite reales. El adaptador DAO sólo liga SQL a JDBC;
 * Q16PrecioCapturadoRoomTest verifica además la generación de Room. No es un test Room/JVM. */
@OptIn(ExperimentalCoroutinesApi::class)
class Q16PrecioCapturadoTest {
    @get:Rule val main = MainDispatcherRule()
    class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("q16-payment-owned").toFile()
        var db: Connection = connect()
        val dao = mockk<PendingPaymentDao>(relaxed = true)
        val server = MockWebServer()
        val network = mockk<ConnectivityMonitor>(relaxed = true)
        val storage = mockk<SecureStorage>(relaxed = true)
        val drawer = mockk<CashDrawerRepository>(relaxed = true)
        val outbox = mockk<SyncOutbox>(relaxed = true)
        val service: PaymentSyncService
        var response = """{"error":"owned-no-effect"}"""
        var code = 503
        private fun connect() = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("q16.sqlite").absolutePath)
        init {
            sql("CREATE TABLE pending_payments(id TEXT PRIMARY KEY,venueId TEXT NOT NULL,syncStatus TEXT NOT NULL,createdAt INTEGER NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0,lastRetryAt INTEGER,lastError TEXT,orderId TEXT,original TEXT NOT NULL)")
            sql("CREATE TABLE pos_sync_intents(id TEXT PRIMARY KEY,venue_id TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,type TEXT NOT NULL,seq INTEGER NOT NULL DEFAULT 1,payload_json TEXT NOT NULL DEFAULT '{}',staff_id TEXT)")
            Q15_MIGRATION_SQL.forEach { sql(it) }
            every { storage.venueId } returns VENUE
            every { network.isConnected } returns MutableStateFlow(true)
            every { network.isServerReachable } returns MutableStateFlow(true)
            coEvery { drawer.sincronizarCajonPrimero() } returns true
            coEvery { outbox.aliasDurableQ16(any(),any()) } returns null
            every { dao.getPendingCount() } returns flowOf(0); every { dao.getFailedCount() } returns flowOf(0)
            coEvery { dao.getPendingPayments(any()) } answers { query("SELECT * FROM pending_payments WHERE syncStatus='PENDING' ORDER BY createdAt,id LIMIT :n",mapOf("n" to firstArg<Int>())).map(::decode) }
            coEvery { dao.findForVenue(any(),any()) } answers { query("SELECT * FROM pending_payments WHERE venueId=:v AND id=:id",mapOf("v" to firstArg<String>(),"id" to secondArg<String>())).firstOrNull()?.let(::decode) }
            coEvery { dao.recuperarOrdenQ16(any(),any(),any(),any()) } answers {
                sql(Q16_RECOVER_ORDER_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"originalOrderId" to arg<String?>(2),"recoveredOrderId" to arg<String>(3)))
            }
            coEvery { dao.updateStatus(any(),any()) } answers { sql("UPDATE pending_payments SET syncStatus=:s WHERE id=:id",mapOf("id" to firstArg<String>(),"s" to secondArg<String>())); Unit }
            coEvery { dao.incrementRetry(any(),any()) } answers { sql("UPDATE pending_payments SET syncStatus='PENDING',retryCount=retryCount+1,lastRetryAt=:t WHERE id=:id",mapOf("id" to firstArg<String>(),"t" to secondArg<Long>())); Unit }
            coEvery { dao.updateStatusWithError(any(),any(),any()) } answers { sql("UPDATE pending_payments SET syncStatus=:s,lastError=:e WHERE id=:id",mapOf("id" to firstArg<String>(),"s" to secondArg<String>(),"e" to arg<String?>(2))); Unit }
            coEvery { dao.retenerPagoQ15(any(),any(),any(),any()) } answers { sql(Q15_PAYMENT_HOLD_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"motivo" to arg<String>(2),"ahora" to arg<Long>(3))) }
            coEvery { dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } answers { sql(Q15_PAYMENT_RESULT_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"aplicado" to if(arg<Boolean>(2)) 1 else 0,"motivo" to arg<String?>(3),"resultadoJson" to arg<String?>(4),"ahora" to arg<Long>(5))) }
            coEvery { dao.deleteSynced(any()) } answers { sql(Q15_PAYMENT_TTL_SQL,mapOf("olderThan" to firstArg<Long>())); Unit }
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code).setBody(response) }
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val sent = chain.request()
                chain.proceed(sent.newBuilder().url(server.url(sent.url.encodedPath)).build())
            }.build()
            service = PaymentSyncService(dao,storage,client,network,drawer,outbox)
        }
        @Synchronized fun reopen() { db.close(); db = connect() }
        @Synchronized fun sql(text: String, args: Map<String,Any?> = emptyMap()): Int = statement(text,args).use { it.executeUpdate() }
        @Synchronized fun query(text: String,args:Map<String,Any?> = emptyMap()):List<Map<String,Any?>> = statement(text,args).use { s -> s.executeQuery().use { r -> buildList {
            while(r.next()) add((1..r.metaData.columnCount).associate { r.metaData.getColumnLabel(it) to r.getObject(it) })
        } } }
        private fun statement(text:String,args:Map<String,Any?>):java.sql.PreparedStatement {
            val values=mutableListOf<Any?>()
            val bound=Regex(":([a-zA-Z][a-zA-Z0-9]*)").replace(text){ values.add(args[it.groupValues[1]]); "?" }
            return db.prepareStatement(bound).also { s -> values.forEachIndexed { i,v -> s.setObject(i+1,v) } }
        }
        fun seed(id:String="p1",order:String?=null,kind:String="ORDER",items:String?=null) {
            val capture=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(VENUE,id,order,10005,500,splitType="CUSTOM_AMOUNT",method="CASH",customerId="customer",orderRequestJson=ORIGINAL))
            val payload=JSONObject().put("order",order ?: JSONObject.NULL).put("kind",kind).put("amount",10005).put("tip",500).put("items",items ?: JSONObject.NULL).put("orderRequest",if(kind=="ORDER") ORIGINAL else JSONObject.NULL).toString()
            sql("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt,original,q15CaptureJson,capturedOrderRef,orderId) VALUES(:id,:v,'PENDING',1,:o,:c,:r,:r)",mapOf("id" to id,"v" to VENUE,"o" to payload,"c" to capture,"r" to order))
        }
        // Adaptador de prueba para el writer real CashPaymentRepository. SQL en disco, no Room/JVM.
        fun insertCaptured(row: PendingPaymentEntity) {
            val original = JSONObject().put("order", row.orderId ?: JSONObject.NULL)
                .put("kind", row.paymentType).put("amount", row.amountCents).put("tip", row.tipCents)
                .put("items", row.itemsJson ?: JSONObject.NULL).put("orderRequest", row.orderRequestJson ?: JSONObject.NULL)
                .put("staff", row.staffId).put("customer", row.customerId ?: JSONObject.NULL)
                .put("method", row.method).put("split", row.splitType)
                .put("tender", row.tenderTypeId ?: JSONObject.NULL).put("revision", row.tenderRevision ?: JSONObject.NULL)
                .put("cashTendered", row.cashTenderedCents ?: JSONObject.NULL).put("change", row.changeCents ?: JSONObject.NULL).toString()
            sql("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt,original,q15CaptureJson,capturedOrderRef,orderId) VALUES(:id,:v,:s,:at,:o,:c,:ref,:orderId)",
                mapOf("id" to row.id,"v" to row.venueId,"s" to row.syncStatus,"at" to row.createdAt,"o" to original,
                    "c" to row.q15CaptureJson,"ref" to row.capturedOrderRef,"orderId" to row.orderId))
        }
        private fun decode(row:Map<String,Any?>):PendingPaymentEntity {
            val p=JSONObject(row["original"] as String)
            fun nullable(k:String)=if(p.isNull(k)) null else p.getString(k)
            return PendingPaymentEntity(row["id"] as String,row["venueId"] as String,p.optString("staff","staff"),p.getInt("amount"),p.getInt("tip"),p.optString("method","CASH"),p.getString("kind"),
                orderId=row["orderId"] as String?,orderRequestJson=nullable("orderRequest"),itemsJson=nullable("items"),customerId=if(p.has("customer")) nullable("customer") else "customer",splitType=p.optString("split","CUSTOM_AMOUNT"),
                tenderTypeId=if(p.has("tender")) nullable("tender") else null,tenderRevision=if(p.has("revision")&&!p.isNull("revision")) p.getInt("revision") else null,
                cashTenderedCents=if(p.has("cashTendered")&&!p.isNull("cashTendered")) p.getInt("cashTendered") else null,
                changeCents=if(p.has("change")&&!p.isNull("change")) p.getInt("change") else null,
                syncStatus=row["syncStatus"] as String,retryCount=(row["retryCount"] as Number).toInt(),lastRetryAt=(row["lastRetryAt"] as? Number)?.toLong(),
                q15CaptureJson=row["q15CaptureJson"] as String?,q15Reason=row["q15Reason"] as String?,capturedOrderRef=row["capturedOrderRef"] as String?)
        }
        fun row(id:String="p1")=query("SELECT * FROM pending_payments WHERE id=:id",mapOf("id" to id)).single()
        suspend fun awaitFinished(id:String="p1") = withContext(Dispatchers.Default) { withTimeout(5000) {
            while(true) { val r=row(id); if(r["q15Reason"]!=null || r["syncStatus"]=="SYNCED" || (r["retryCount"] as Number).toInt()>0 || r["syncStatus"]=="FAILED") break; delay(10) }
        } }
        override fun close() { service.stop(); server.shutdown(); db.close(); directory.deleteRecursively() }
    }
    @Test fun `CREATE capturado conserva dinero sin HTTP retries ni liberacion por review tras reopen`() = runTest {
        Fixture().use { f ->
            f.seed(); val before=f.row()
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.awaitFinished()
            f.reopen(); repeat(12) { f.service.syncNow(); f.service.esperarSincronizacionActualQ16() }
            val after=f.row()
            repeat(f.server.requestCount) { assertTrue(f.server.takeRequest().path!!.endsWith("/sync/recovery")) }
            assertEquals("PENDING",after["syncStatus"])
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,after["q15Reason"])
            assertEquals(before["retryCount"],after["retryCount"]); assertEquals(before["lastRetryAt"],after["lastRetryAt"])
            assertEquals(before["original"],after["original"]); assertEquals(before["q15CaptureJson"],after["q15CaptureJson"])
            assertEquals(0,f.sql(Q15_PAYMENT_REVIEW_SQL,mapOf("venueId" to VENUE,"id" to "p1","actor" to "manager","rol" to "MANAGER","ahora" to 9)))
        }
    }
    @Test fun `CREATE retenido no adelanta pago de orden materializada`() = runTest {
        Fixture().use { f -> f.seed(); f.seed("p2","server-order")
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.awaitFinished()
            assertEquals(0,f.server.requestCount); assertEquals("PENDING",f.row("p2")["syncStatus"])
        }
    }
    @Test fun `orden materializada reproduce body original y misma llave sin CREATE`() = runTest {
        Fixture().use { f -> f.seed(order="server-order"); f.code=200
            f.response="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"server-order","amount":10005,"tipAmount":500,"status":"COMPLETED"}}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.awaitFinished()
            assertEquals("SYNCED",f.row()["syncStatus"]); assertEquals(1,f.server.requestCount)
            val req=f.server.takeRequest(); assertTrue(req.path!!.endsWith("/orders/server-order/pay"))
            val body=JSONObject(req.body.readUtf8()); assertEquals(10005,body.getInt("amount")); assertEquals(500,body.getInt("tip")); assertEquals("p1",body.getString("idempotencyKey"))
        }
    }
    @Test fun `FAST sin articulos sigue por su ruta`() = runTest {
        Fixture().use { f -> f.seed(kind="FAST"); f.code=200
            f.response="""{"success":true,"data":{"id":"server-payment","orderId":"server-order","amount":"100.05","tipAmount":"5.00","status":"COMPLETED"}}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.awaitFinished()
            assertEquals("SYNCED",f.row()["syncStatus"]); assertEquals(1,f.server.requestCount)
            assertTrue(f.server.takeRequest().path!!.endsWith("/fast"))
        }
    }
    @Test fun `PRICE estructurado no consume retry ni adelanta pago posterior`() = runTest {
        Fixture().use { f -> f.seed(order="server-order"); f.seed("p2","other-order");f.code=409
            f.response="""{"code":"PRICE_PROOF_PENDING","message":"Falta comprobar el precio"}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.awaitFinished()
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row()["q15Reason"])
            assertEquals(0,(f.row()["retryCount"] as Number).toInt());assertEquals("PENDING",f.row()["syncStatus"])
            assertEquals("PENDING",f.row("p2")["syncStatus"]);assertEquals(1,f.server.requestCount)
        }
    }
    @Test fun `texto PRICE en 409 comun no se confunde con codigo estructurado`() = runTest {
        Fixture().use { f -> f.seed(order="server-order");f.code=409;f.response="""{"message":"PRICE_PROOF_PENDING"}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.awaitFinished()
            assertNull(f.row()["q15Reason"]);assertEquals(1,(f.row()["retryCount"] as Number).toInt())
            assertEquals("PENDING",f.row()["syncStatus"])
        }
    }
    @Test fun `FAST con articulos no degrada captura a importe suelto`() = runTest {
        Fixture().use { f -> f.seed(kind="FAST",items="""[{"productId":"original-product","quantity":1}]""")
            val before=f.row()["original"]
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.awaitFinished()
            assertEquals(0,f.server.requestCount);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row()["q15Reason"])
            assertEquals(before,f.row()["original"]);assertEquals(0,(f.row()["retryCount"] as Number).toInt())
        }
    }
    @Test fun `recovery ORDER usa external original luego reproduce misma captura sin CREATE`() = runTest {
        Fixture().use { f ->
            f.seed(); val before=f.row()
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.service.esperarSincronizacionActualQ16()
            f.code=200;f.response="""{"data":{"intents":[],"orders":[{"externalId":"original-key","orderId":"server-order"}]}}"""
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            assertEquals("server-order",f.row()["orderId"]);assertNull(f.row()["q15Reason"])
            assertEquals(before["original"],f.row()["original"]);assertEquals(before["q15CaptureJson"],f.row()["q15CaptureJson"])
            val lookup=f.server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(lookup);assertTrue(lookup!!.path!!.endsWith("/sync/recovery"))
            val request=JSONObject(lookup.body.readUtf8());assertEquals("original-key",request.getJSONArray("externalOrderIds").getString(0));assertFalse(request.has("deviceId"))
            f.response="""{"success":true,"payment":{"paymentId":"server-payment","orderId":"server-order","amount":10005,"tipAmount":500,"status":"COMPLETED"}}"""
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            assertEquals("SYNCED",f.row()["syncStatus"])
            val sent=f.server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(sent)
            assertTrue(sent!!.path!!.endsWith("/orders/server-order/pay"))
            val body=JSONObject(sent.body.readUtf8());assertEquals("p1",body.getString("idempotencyKey"));assertEquals(10005,body.getInt("amount"));assertEquals(500,body.getInt("tip"))
            assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun `recovery ORDER ausente ambiguo ajeno o backend viejo conserva captura y retries`() = runTest {
        for((status,body) in listOf(404 to "{}",401 to "{}",503 to "{}",200 to """{"data":{"intents":[],"orders":[]}}""",
            200 to """{"data":{"intents":[],"orders":[{"externalId":"OTHER","orderId":"R"}]}}""",
            200 to """{"data":{"intents":[],"orders":[{"externalId":"original-key","orderId":"R"},{"externalId":"original-key","orderId":"S"}]}}""")) {
            Fixture().use { f ->f.seed();f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.service.esperarSincronizacionActualQ16()
                val before=f.row();f.code=status;f.response=body
                f.service.syncNow();f.service.esperarSincronizacionActualQ16()
                assertEquals(1,f.server.requestCount);assertEquals(before,f.row())
                assertTrue(f.server.takeRequest().path!!.endsWith("/sync/recovery"))
            }
        }
    }
    @Test fun `CAS de recovery no libera otra sucursal ni captura legacy ni fila reemplazada`() = runTest {
        Fixture().use { f ->f.seed();f.sql("UPDATE pending_payments SET q15Reason='PRICE_PROOF_PENDING'")
            val base=mapOf("venueId" to VENUE,"id" to "p1","originalOrderId" to null,"recoveredOrderId" to "R")
            assertEquals(0,f.sql(Q16_RECOVER_ORDER_SQL,base+mapOf("venueId" to "B")))
            assertEquals(0,f.sql(Q16_RECOVER_ORDER_SQL,base+mapOf("originalOrderId" to "other")))
            f.sql("UPDATE pending_payments SET q15CaptureJson=NULL")
            assertEquals(0,f.sql(Q16_RECOVER_ORDER_SQL,base));assertNull(f.row()["orderId"])
        }
    }
    @Test fun `H2 captura real conserva cliente tender y UUID mientras espera y recupera identidad sin CREATE`() = runTest {
        Fixture().use { f ->
            coEvery { f.dao.insert(any()) } answers { f.insertCaptured(firstArg()); Unit }
            val repository = CashPaymentRepository(f.storage, f.dao)
            val request = com.avoqado.pos.payment.data.model.CreateOrderRequest(
                items=listOf(com.avoqado.pos.payment.data.model.OrderItemRequest(productId="product-A",name="Artículo A",quantity=1,unitPrice=10000)),
                subtotal=10000,total=10000,paymentMethod="CASH")
            val tender = com.avoqado.pos.payment.domain.TenderTypeOption("tender-A",7,"Medio A",false,"OTHER",true,"MORE",1,false)
            val key = repository.queueCashPayment(request,"actor-A",4500,0,null,orderExternalId="original-h2-order",
                customerId="customer-A",idempotencyKey="original-h2-payment",tenderType=tender,
                amountCents=4000,tipCents=500,splitType="CUSTOMAMOUNT",venueId=VENUE)
            val before=f.row(key);val captured=JSONObject(before["original"] as String)
            assertEquals("tender-A",captured.getString("tender"));assertEquals(7,captured.getInt("revision"))
            assertEquals("customer-A",captured.getString("customer"));assertEquals(4000,captured.getInt("amount"));assertEquals(500,captured.getInt("tip"))
            val order=JSONObject(captured.getString("orderRequest"))
            assertEquals("customer-A",order.getString("customerId"));assertEquals("original-h2-order",order.getString("externalId"))
            every { f.storage.venueId } returns "venue-B"
            f.code=200;f.response="""{"data":{"intents":[],"orders":[]}}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            f.service.start();f.service.esperarSincronizacionActualQ16()
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            val held=f.row(key)
            assertEquals("PENDING",held["syncStatus"]);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,held["q15Reason"])
            assertEquals(before["retryCount"],held["retryCount"]);assertEquals(before["lastRetryAt"],held["lastRetryAt"])
            assertEquals(before["original"],held["original"]);assertEquals(before["q15CaptureJson"],held["q15CaptureJson"])
            assertEquals(1,f.server.requestCount)
            val missing=requireNotNull(f.server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(missing.path!!.endsWith("/venues/$VENUE/sync/recovery"))
            assertEquals("original-h2-order",JSONObject(missing.body.readUtf8()).getJSONArray("externalOrderIds").getString(0))
            f.reopen()
            f.response="""{"data":{"intents":[],"orders":[{"externalId":"original-h2-order","orderId":"order-A"}]}}"""
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            assertEquals("order-A",f.row(key)["orderId"]);assertEquals(before["q15CaptureJson"],f.row(key)["q15CaptureJson"])
            val found=requireNotNull(f.server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(found.path!!.endsWith("/sync/recovery"))
            f.response="""{"success":true,"payment":{"paymentId":"payment-A","orderId":"order-A","amount":4000,"tipAmount":500,"changeCents":0,"status":"COMPLETED"}}"""
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            assertEquals("SYNCED",f.row(key)["syncStatus"]);assertEquals(3,f.server.requestCount)
            val sent=requireNotNull(f.server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(sent.path!!.endsWith("/venues/$VENUE/orders/order-A/pay"))
            val pay=JSONObject(sent.body.readUtf8())
            assertEquals(key,pay.getString("idempotencyKey"));assertEquals(4000,pay.getInt("amount"));assertEquals(500,pay.getInt("tip"))
            assertEquals("tender-A",pay.getString("tenderTypeId"));assertEquals(7,pay.getInt("tenderRevision"))
            assertFalse(pay.has("method"));assertFalse(pay.has("externalSource"));assertFalse(pay.has("customerId"))
            assertEquals(before["original"],f.row(key)["original"]);assertEquals(before["q15CaptureJson"],f.row(key)["q15CaptureJson"])
            assertEquals("venue-B",f.storage.venueId)
        }
    }
    companion object {
        const val VENUE="FULLTEST-Q16-A"
        const val ORIGINAL="""{"externalId":"original-key","items":[{"productId":"original-product","quantity":1}]}"""
    }
}
