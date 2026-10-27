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
    /** Una prueba de la cola que se cuelga FALLA con la pila del hilo atorado, en vez de colgar la corrida entera. */
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()
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
        val client: OkHttpClient
        var response = """{"error":"owned-no-effect"}"""
        var code = 503
        private fun connect() = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("q16.sqlite").absolutePath)
        init {
            sql("CREATE TABLE pending_payments(id TEXT PRIMARY KEY,venueId TEXT NOT NULL,syncStatus TEXT NOT NULL,createdAt INTEGER NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0,lastRetryAt INTEGER,lastError TEXT,orderId TEXT,original TEXT NOT NULL,paymentType TEXT NOT NULL DEFAULT 'ORDER',amountCents INTEGER NOT NULL DEFAULT 0,tipCents INTEGER NOT NULL DEFAULT 0,orderRequestJson TEXT,method TEXT NOT NULL DEFAULT 'CASH',tenderTypeId TEXT,tenderRevision INTEGER,splitType TEXT NOT NULL DEFAULT 'FULLPAYMENT',customerId TEXT,cashTenderedCents INTEGER,changeCents INTEGER)")
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
            coEvery { dao.revisarCanceladoSinCobroQ15(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()) } answers {
                sql(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),
                    "originalOrderId" to arg<String?>(2),"originalOrderRequestJson" to arg<String?>(3),"originalResultJson" to arg<String?>(4),
                    "originalSyncStatus" to arg<String>(5),"originalCaptureJson" to arg<String?>(6),"originalCreatedAt" to arg<Long>(7),
                    "originalCapturedOrderRef" to arg<String?>(8),"originalSplitType" to arg<String>(9),"originalCustomerId" to arg<String?>(10),
                    "originalCashTenderedCents" to arg<Int?>(11),"originalChangeCents" to arg<Int?>(12),
                    "originalReason" to arg<String?>(13),
                    "resultadoJson" to arg<String>(14),"actor" to arg<String>(15),"rol" to arg<String>(16),"ahora" to arg<Long>(17)))
            }
            coEvery { dao.recuperarOrdenQ16(any(),any(),any(),any()) } answers {
                sql(Q16_RECOVER_ORDER_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"originalOrderId" to arg<String?>(2),"recoveredOrderId" to arg<String>(3)))
            }
            coEvery { dao.soltarVentaSinRedQ16(any(),any()) } answers { sql(Q16_RELEASE_SALE_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>())) }
            coEvery { dao.resetSyncingToPending() } answers { sql("UPDATE pending_payments SET syncStatus='PENDING' WHERE syncStatus='SYNCING'"); Unit }
            coEvery { dao.updateStatus(any(),any()) } answers { sql("UPDATE pending_payments SET syncStatus=:s WHERE id=:id",mapOf("id" to firstArg<String>(),"s" to secondArg<String>())); Unit }
            coEvery { dao.incrementRetry(any(),any()) } answers { sql("UPDATE pending_payments SET syncStatus='PENDING',retryCount=retryCount+1,lastRetryAt=:t WHERE id=:id",mapOf("id" to firstArg<String>(),"t" to secondArg<Long>())); Unit }
            coEvery { dao.updateStatusWithError(any(),any(),any()) } answers { sql("UPDATE pending_payments SET syncStatus=:s,lastError=:e WHERE id=:id",mapOf("id" to firstArg<String>(),"s" to secondArg<String>(),"e" to arg<String?>(2))); Unit }
            coEvery { dao.retenerPagoQ15(any(),any(),any(),any()) } answers { sql(Q15_PAYMENT_HOLD_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"motivo" to arg<String>(2),"ahora" to arg<Long>(3))) }
            coEvery { dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } answers { sql(Q15_PAYMENT_RESULT_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"aplicado" to if(arg<Boolean>(2)) 1 else 0,"motivo" to arg<String?>(3),"resultadoJson" to arg<String?>(4),"ahora" to arg<Long>(5))) }
            coEvery { dao.insert(any()) } answers { insertCaptured(firstArg()); Unit }
            coEvery { dao.deleteSynced(any()) } answers { sql(Q15_PAYMENT_TTL_SQL,mapOf("olderThan" to firstArg<Long>())); Unit }
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code).setBody(response) }
            server.start()
            client = OkHttpClient.Builder().addInterceptor { chain ->
                val sent = chain.request()
                chain.proceed(sent.newBuilder().url(server.url(sent.url.encodedPath)).build())
            }.build()
            service = PaymentSyncService(dao,storage,client,network,drawer,outbox,io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true))
        }
        fun realWriters() {
            coEvery { dao.findByIdQ15(any()) } answers {
                query("SELECT * FROM pending_payments WHERE id=:id", mapOf("id" to firstArg<String>())).firstOrNull()?.let(::decode)
            }
            coEvery { dao.insert(any()) } coAnswers { callOriginal() }
            coEvery { dao.insertTransactionQ16(any()) } coAnswers { callOriginal() }
            coEvery { dao.insertRaw(any()) } coAnswers { callOriginal() }
            coEvery { dao.insertRawSqlQ16(any()) } answers { insertCaptured(firstArg()); Unit }
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
        fun seed(id:String="p1",order:String?=null,kind:String="ORDER",items:String?=null,request:String=ORIGINAL,captured:String?=request) {
            val capture=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(VENUE,id,order,10005,500,splitType="CUSTOM_AMOUNT",method="CASH",customerId="customer",orderRequestJson=captured))
            val payload=JSONObject().put("order",order ?: JSONObject.NULL).put("kind",kind).put("amount",10005).put("tip",500).put("items",items ?: JSONObject.NULL).put("orderRequest",if(kind=="ORDER") request else JSONObject.NULL).toString()
            sql("INSERT INTO pending_payments(id,venueId,syncStatus,createdAt,original,q15CaptureJson,capturedOrderRef,orderId,paymentType,amountCents,tipCents,orderRequestJson) VALUES(:id,:v,'PENDING',1,:o,:c,:r,:r,:kind,10005,500,:request)",mapOf("id" to id,"v" to VENUE,"o" to payload,"c" to capture,"r" to order,"kind" to kind,"request" to if(kind=="ORDER") request else null))
        }
        /** La fila tal como la dejó la 2.24.0: retenida por `requierePruebaDePrecio`, con el MISMO SQL de producción. */
        fun retenerComo2240(id:String="p1")=check(sql(Q15_PAYMENT_HOLD_SQL,mapOf("venueId" to VENUE,"id" to id,"motivo" to ConciliacionDelCobro.PRICE_PROOF_PENDING,"ahora" to 7L))==1)
        /** El escritor REAL de la cola (`CashPaymentRepository.queueCashPayment`), como la caja guarda la venta sin red.
         *  Igual que `PaymentFlowViewModel`: el `externalId` de la orden es la misma llave del cobro. */
        suspend fun venta(key:String,subtotal:Int=10000,tip:Int=500,discount:Int=0,amount:Int=subtotal-discount,split:String="FULLPAYMENT",
                          tender:com.avoqado.pos.payment.domain.TenderTypeOption?=null,customer:String?="customer-A",orden:String=key,
                          caja:String?=null):String {
            val request=com.avoqado.pos.payment.data.model.CreateOrderRequest(
                items=listOf(com.avoqado.pos.payment.data.model.OrderItemRequest(productId="product-A",name="Artículo A",quantity=1,unitPrice=subtotal)),
                subtotal=subtotal,discount=discount,tip=tip,total=subtotal-discount+tip,paymentMethod="CASH")
            return CashPaymentRepository(storage,dao).queueCashPayment(request,"staff-A",null,null,null,orderExternalId=orden,
                customerId=customer,idempotencyKey=key,tenderType=tender,amountCents=amount,tipCents=tip,splitType=split,venueId=VENUE,
                cajaDelCobro=caja)
        }
        /** Venta de importe libre (FAST sin artículos) con el mismo escritor real: no tiene orden ni pedido. */
        suspend fun importeLibre(key:String,amount:Int,caja:String?=null):String {
            val request=com.avoqado.pos.payment.data.model.CreateOrderRequest(
                items=listOf(com.avoqado.pos.payment.data.model.OrderItemRequest(name="Importe libre",quantity=1,unitPrice=amount)),
                subtotal=amount,total=amount,paymentMethod="CASH")
            return CashPaymentRepository(storage,dao).queueCashPayment(request,"staff-A",null,null,null,
                idempotencyKey=key,amountCents=amount,tipCents=0,splitType="FULLPAYMENT",venueId=VENUE,cajaDelCobro=caja)
        }
        // Adaptador de prueba para el writer real CashPaymentRepository. SQL en disco, no Room/JVM.
        fun insertCaptured(row: PendingPaymentEntity) {
            val original = JSONObject().put("order", row.orderId ?: JSONObject.NULL)
                .put("kind", row.paymentType).put("amount", row.amountCents).put("tip", row.tipCents)
                .put("items", row.itemsJson ?: JSONObject.NULL).put("orderRequest", row.orderRequestJson ?: JSONObject.NULL)
                .put("staff", row.staffId).put("customer", row.customerId ?: JSONObject.NULL)
                .put("method", row.method).put("split", row.splitType)
                .put("tender", row.tenderTypeId ?: JSONObject.NULL).put("revision", row.tenderRevision ?: JSONObject.NULL)
                .put("cashTendered", row.cashTenderedCents ?: JSONObject.NULL).put("change", row.changeCents ?: JSONObject.NULL)
                .put("caja", row.cashDrawerSessionId ?: JSONObject.NULL).toString()
            // REPLACE, como `@Insert(onConflict = REPLACE)`: la reserva del efectivo se vuelve la fila de la cola con la misma llave.
            sql("INSERT OR REPLACE INTO pending_payments(id,venueId,syncStatus,createdAt,original,q15CaptureJson,capturedOrderRef,orderId,paymentType,amountCents,tipCents,orderRequestJson,lastError,q15ResultJson,q15Reason,q15ReviewedAt,q15ReviewedBy,q15ReviewRole,method,tenderTypeId,tenderRevision,splitType,customerId,cashTenderedCents,changeCents) VALUES(:id,:v,:s,:at,:o,:c,:ref,:orderId,:kind,:amount,:tip,:request,:error,:result,:reason,:reviewed,:actor,:role,:method,:tender,:revision,:split,:customer,:cash,:change)",
                mapOf("id" to row.id,"v" to row.venueId,"s" to row.syncStatus,"at" to row.createdAt,"o" to original,
                    "c" to row.q15CaptureJson,"ref" to row.capturedOrderRef,"orderId" to row.orderId,"kind" to row.paymentType,
                    "amount" to row.amountCents,"tip" to row.tipCents,"request" to row.orderRequestJson,"error" to row.lastError,
                    "result" to row.q15ResultJson,"reason" to row.q15Reason,"reviewed" to row.q15ReviewedAt,"actor" to row.q15ReviewedBy,"role" to row.q15ReviewRole,
                    "method" to row.method,"tender" to row.tenderTypeId,"revision" to row.tenderRevision,
                    "split" to row.splitType,"customer" to row.customerId,"cash" to row.cashTenderedCents,"change" to row.changeCents))
        }
        fun decode(row:Map<String,Any?>):PendingPaymentEntity {
            val p=JSONObject(row["original"] as String)
            fun nullable(k:String)=if(p.isNull(k)) null else p.getString(k)
            return PendingPaymentEntity(row["id"] as String,row["venueId"] as String,p.optString("staff","staff"),(row["amountCents"] as Number).toInt(),(row["tipCents"] as Number).toInt(),p.optString("method","CASH"),row["paymentType"] as String,
                orderId=row["orderId"] as String?,orderRequestJson=row["orderRequestJson"] as String?,itemsJson=nullable("items"),customerId=if(p.has("customer")) nullable("customer") else "customer",splitType=p.optString("split","CUSTOM_AMOUNT"),
                tenderTypeId=if(p.has("tender")) nullable("tender") else null,tenderRevision=if(p.has("revision")&&!p.isNull("revision")) p.getInt("revision") else null,
                cashTenderedCents=if(p.has("cashTendered")&&!p.isNull("cashTendered")) p.getInt("cashTendered") else null,
                changeCents=if(p.has("change")&&!p.isNull("change")) p.getInt("change") else null,
                cashDrawerSessionId=if(p.has("caja")) nullable("caja") else null,
                syncStatus=row["syncStatus"] as String,retryCount=(row["retryCount"] as Number).toInt(),lastRetryAt=(row["lastRetryAt"] as? Number)?.toLong(),
                createdAt=(row["createdAt"] as Number).toLong(),
                lastError=row["lastError"] as String?,q15InitialResultJson=row["q15InitialResultJson"] as String?,q15ResultJson=row["q15ResultJson"] as String?,
                q15ReviewedAt=(row["q15ReviewedAt"] as? Number)?.toLong(),q15ReviewedBy=row["q15ReviewedBy"] as String?,q15ReviewRole=row["q15ReviewRole"] as String?,
                q15CaptureJson=row["q15CaptureJson"] as String?,q15Reason=row["q15Reason"] as String?,capturedOrderRef=row["capturedOrderRef"] as String?)
        }
        fun row(id:String="p1")=query("SELECT * FROM pending_payments WHERE id=:id",mapOf("id" to id)).single()
        suspend fun awaitFinished(id:String="p1") = withContext(Dispatchers.Default) { withTimeout(5000) {
            while(true) { val r=row(id); if(r["q15Reason"]!=null || r["syncStatus"]=="SYNCED" || (r["retryCount"] as Number).toInt()>0 || r["syncStatus"]=="FAILED") break; delay(10) }
        } }
        override fun close() { service.stop(); server.shutdown(); db.close(); directory.deleteRecursively() }
    }
    // Q16 (2.24.1): antes esta prueba EXIGÍA el defecto (12 vueltas, sólo /sync/recovery, fila retenida para siempre).
    @Test fun `CREATE capturado sin red se manda al volver la red con su externalId y su llave`() = runTest {
        Fixture().use { f ->
            val falso=ServidorFalso(); f.server.dispatcher=falso
            val key=f.venta("venta-A"); val before=f.row(key)
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.service.esperarSincronizacionActualQ16()
            f.reopen(); repeat(3) { f.service.syncNow(); f.service.esperarSincronizacionActualQ16() }
            val after=f.row(key)
            assertEquals("SYNCED",after["syncStatus"]); assertNull(after["q15Reason"])
            assertEquals(2,f.server.requestCount); assertEquals(listOf("orders","pay"),falso.peticiones.map { it.first })
            val create=f.server.takeRequest(); assertTrue(create.path!!.endsWith("/venues/$VENUE/orders"))
            val order=JSONObject(create.body.readUtf8()); assertEquals(key,order.getString("externalId"))
            assertEquals("product-A",order.getJSONArray("items").getJSONObject(0).getString("productId"))
            assertEquals(10500,order.getInt("total")); assertEquals(500,order.getInt("tip")); assertEquals("customer-A",order.getString("customerId"))
            val pay=f.server.takeRequest(); assertTrue(pay.path!!.endsWith("/venues/$VENUE/orders/${falso.ordenes.getValue(key)}/pay"))
            val body=JSONObject(pay.body.readUtf8())
            assertEquals(key,body.getString("idempotencyKey")); assertEquals(10000,body.getInt("amount")); assertEquals(500,body.getInt("tip"))
            assertEquals("CASH",body.getString("method")); assertEquals("FULLPAYMENT",body.getString("splitType")); assertTrue(body.getBoolean("isOfflineReplay"))
            assertEquals(1,falso.ordenes.size); assertEquals(1,falso.pagos.size)
            assertEquals(before["original"],after["original"]); assertEquals(before["q15CaptureJson"],after["q15CaptureJson"])
            assertEquals(before["retryCount"],after["retryCount"]); assertNull(after["orderId"])
            assertEquals(0,f.sql(Q15_PAYMENT_REVIEW_SQL,mapOf("venueId" to VENUE,"id" to key,"actor" to "manager","rol" to "MANAGER","ahora" to 9)))
        }
    }
    @Test fun `CREATE que falla por el servidor no adelanta pago de orden materializada`() = runTest {
        Fixture().use { f -> f.seed(); f.seed("p2","server-order")
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined); f.service.start(); f.service.esperarSincronizacionActualQ16()
            assertEquals(1,f.server.requestCount); assertTrue(f.server.takeRequest().path!!.endsWith("/orders"))
            assertEquals(1,(f.row()["retryCount"] as Number).toInt()); assertNull(f.row()["q15Reason"])
            assertEquals("PENDING",f.row("p2")["syncStatus"]); assertEquals(0,(f.row("p2")["retryCount"] as Number).toInt())
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
    // Adenda Q16 (founder, 16-oct): la venta que el 409 retiene ya no detiene a la de OTRA orden con su propio pedido.
    @Test fun `PRICE estructurado no consume retry y no detiene el pago de otra orden`() = runTest {
        Fixture().use { f -> f.seed(order="server-order")
            f.seed("p2","other-order",request="""{"externalId":"otro-pedido","items":[{"productId":"original-product","quantity":1}]}""");f.code=409
            f.response="""{"code":"PRICE_PROOF_PENDING","message":"Falta comprobar el precio"}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.service.esperarSincronizacionActualQ16()
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row()["q15Reason"])
            assertEquals(0,(f.row()["retryCount"] as Number).toInt());assertEquals("PENDING",f.row()["syncStatus"])
            assertEquals(2,f.server.requestCount)
            assertTrue(f.server.takeRequest().path!!.endsWith("/orders/server-order/pay"))
            val second=f.server.takeRequest();assertTrue(second.path!!.endsWith("/orders/other-order/pay"))
            assertEquals("p2",JSONObject(second.body.readUtf8()).getString("idempotencyKey"))
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
            f.seed(); f.retenerComo2240(); val before=f.row()
            f.code=200;f.response="""{"data":{"intents":[],"orders":[{"externalId":"original-key","orderId":"server-order"}]}}"""
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.service.esperarSincronizacionActualQ16()
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
    // Q16 (2.24.1): `orders: []` con `intents: []` ya NO conserva la marca: suelta la venta (ver Q16VentaSinRedTest).
    @Test fun `recovery ORDER con error ambiguo ajeno intents o backend viejo conserva captura y retries sin CREATE`() = runTest {
        for((status,body) in listOf(404 to "{}",401 to "{}",503 to "{}",200 to "{}",200 to """{"data":{"orders":[]}}""",
            200 to """{"data":{"intents":[{"id":"i1"}],"orders":[]}}""",
            200 to """{"data":{"intents":[],"orders":[{"externalId":"OTHER","orderId":"R"}]}}""",
            200 to """{"data":{"intents":[],"orders":[{"externalId":"original-key","orderId":"R"},{"externalId":"original-key","orderId":"S"}]}}""")) {
            Fixture().use { f ->f.seed();f.retenerComo2240();val before=f.row();f.code=status;f.response=body
                f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.service.start();f.service.esperarSincronizacionActualQ16()
                f.service.syncNow();f.service.esperarSincronizacionActualQ16()
                assertEquals(body,2,f.server.requestCount);assertEquals(body,before,f.row())
                repeat(2) { assertTrue(body,f.server.takeRequest().path!!.endsWith("/sync/recovery")) }
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
    @Test fun `CAS de soltar venta sin red no toca otra sucursal alias sin captura otro estado ni otro motivo`() = runTest {
        Fixture().use { f ->
            fun intenta(prep:String?,venue:String=VENUE):Int { f.sql("DELETE FROM pending_payments");f.seed();f.retenerComo2240();prep?.let { f.sql(it) }
                return f.sql(Q16_RELEASE_SALE_SQL,mapOf("venueId" to venue,"id" to "p1")) }
            assertEquals(0,intenta(null,"B"))
            assertEquals(0,intenta("UPDATE pending_payments SET orderId='local-order'"))
            assertEquals(0,intenta("UPDATE pending_payments SET orderId=''"))
            assertEquals(0,intenta("UPDATE pending_payments SET q15CaptureJson=NULL"))
            assertEquals(0,intenta("UPDATE pending_payments SET syncStatus='FAILED'"))
            assertEquals(0,intenta("UPDATE pending_payments SET q15Reason='CAPTURA_SIN_EVIDENCIA'"))
            assertEquals(0,intenta("UPDATE pending_payments SET q15Reason='DIFERENCIA_DE_COBRO'"))
            f.sql("DELETE FROM pending_payments");f.seed();f.retenerComo2240();val before=f.row()
            assertEquals(1,f.sql(Q16_RELEASE_SALE_SQL,mapOf("venueId" to VENUE,"id" to "p1")))
            val after=f.row()
            assertNull(after["q15Reason"]);assertNull(after["q15DetectedAt"]);assertNull(after["orderId"]);assertEquals("PENDING",after["syncStatus"])
            for(k in listOf("original","q15CaptureJson","capturedOrderRef","retryCount","lastRetryAt","createdAt","venueId")) assertEquals(k,before[k],after[k])
        }
    }
    // Q16 (2.24.1): la fila que la 2.24.0 retuvo se SUELTA con recuperación vacía y se manda en la misma vuelta,
    // con la identidad, el cliente y el tipo de pago del escritor real intactos.
    @Test fun `H2 captura real retenida por la 2240 se suelta y se manda en la misma vuelta con cliente tender y UUID sin tocar la captura`() = runTest {
        Fixture().use { f ->
            val falso=ServidorFalso(); f.server.dispatcher=falso
            val repository = CashPaymentRepository(f.storage, f.dao)
            val request = com.avoqado.pos.payment.data.model.CreateOrderRequest(
                items=listOf(com.avoqado.pos.payment.data.model.OrderItemRequest(productId="product-A",name="Artículo A",quantity=1,unitPrice=10000)),
                subtotal=10000,total=10000,paymentMethod="CASH")
            val tender = com.avoqado.pos.payment.domain.TenderTypeOption("tender-A",7,"Medio A",false,"OTHER",true,"MORE",1,false)
            val key = repository.queueCashPayment(request,"actor-A",4500,0,null,orderExternalId="original-h2-order",
                customerId="customer-A",idempotencyKey="original-h2-payment",tenderType=tender,
                amountCents=4000,tipCents=500,splitType="CUSTOMAMOUNT",venueId=VENUE)
            f.retenerComo2240(key)
            val before=f.row(key);val captured=JSONObject(before["original"] as String)
            assertEquals("tender-A",captured.getString("tender"));assertEquals(7,captured.getInt("revision"))
            assertEquals("customer-A",captured.getString("customer"));assertEquals(4000,captured.getInt("amount"));assertEquals(500,captured.getInt("tip"))
            val order=JSONObject(captured.getString("orderRequest"))
            assertEquals("customer-A",order.getString("customerId"));assertEquals("original-h2-order",order.getString("externalId"))
            every { f.storage.venueId } returns "venue-B"
            f.service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            // Ronda 1: se suelta y se manda en la MISMA vuelta.
            f.service.start();f.service.esperarSincronizacionActualQ16()
            val sent1=f.row(key)
            assertEquals("SYNCED",sent1["syncStatus"]);assertNull(sent1["q15Reason"]);assertNull(sent1["orderId"])
            assertEquals(before["retryCount"],sent1["retryCount"]);assertEquals(before["lastRetryAt"],sent1["lastRetryAt"])
            assertEquals(3,f.server.requestCount)
            val missing=requireNotNull(f.server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(missing.path!!.endsWith("/venues/$VENUE/sync/recovery"))
            assertEquals("original-h2-order",JSONObject(missing.body.readUtf8()).getJSONArray("externalOrderIds").getString(0))
            f.reopen()
            f.service.syncNow();f.service.esperarSincronizacionActualQ16()
            assertEquals("SYNCED",f.row(key)["syncStatus"]);assertEquals(3,f.server.requestCount)
            assertEquals(mapOf("original-h2-order" to "orden-1"),falso.ordenes.toMap());assertEquals(setOf(key),falso.pagos.keys)
            val create=requireNotNull(f.server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(create.path!!.endsWith("/venues/$VENUE/orders"))
            val sentOrder=JSONObject(create.body.readUtf8())
            assertEquals("original-h2-order",sentOrder.getString("externalId"));assertEquals("customer-A",sentOrder.getString("customerId"))
            val sent=requireNotNull(f.server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(sent.path!!.endsWith("/venues/$VENUE/orders/orden-1/pay"))
            val pay=JSONObject(sent.body.readUtf8())
            assertEquals(key,pay.getString("idempotencyKey"));assertEquals(4000,pay.getInt("amount"));assertEquals(500,pay.getInt("tip"))
            assertEquals("tender-A",pay.getString("tenderTypeId"));assertEquals(7,pay.getInt("tenderRevision"))
            assertFalse(pay.has("method"));assertFalse(pay.has("externalSource"));assertFalse(pay.has("customerId"))
            assertEquals(before["original"],f.row(key)["original"]);assertEquals(before["q15CaptureJson"],f.row(key)["q15CaptureJson"])
            assertEquals("venue-B",f.storage.venueId)
        }
    }
    // Precio que vio el cliente (Task 7): la hora de la venta se captura UNA vez y la cola la hereda aunque se escriba
    // 15 s después: del pedido capturado (también si la reserva ya se soltó, p. ej. porque la caja abrió otra venta) o de
    // la reserva en disco. La venta encolada se marca `isOfflineReplay` y lleva su precio mostrado.
    @Test fun `la venta encolada conserva la hora ORIGINAL de la reserva`() = runTest {
        Fixture().use { f ->
            val cat = "2026-10-17T15:00:00.000Z"
            val request = com.avoqado.pos.payment.data.model.CreateOrderRequest(
                items = com.avoqado.pos.pos.data.model.buildOrderItemRequests(listOf(com.avoqado.pos.pos.data.model.CartItem(
                    id = "linea-1", type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("product-A"),
                    name = "Café", unitPrice = 4500, catalogAt = cat))),
                subtotal = 4500, total = 4500, paymentMethod = "CASH")
            val t0 = System.currentTimeMillis() - 15_000
            val caja = CashPaymentRepository(f.storage, f.dao)
            fun revisa(llave: String, key: String) {
                val row = f.row(key)
                assertEquals(llave, "PENDING", row["syncStatus"])
                assertEquals(llave, t0, (row["createdAt"] as Number).toLong())
                val order = JSONObject(JSONObject(row["original"] as String).getString("orderRequest"))
                assertEquals(llave, t0, order.getLong("createdAtLocal"))
                assertTrue(llave, order.getBoolean("isOfflineReplay"))
                val linea = order.getJSONArray("items").getJSONObject(0)
                assertEquals(4500, linea.getInt("displayedUnitPrice")); assertEquals(cat, linea.getString("catalogAt"))
                assertEquals("linea-1", linea.getString("externalId"))
                // La captura (la evidencia que la cola reproduce) dice lo mismo.
                val captura = ConciliacionDelCobro.leerCaptura(row["q15CaptureJson"] as String?, VENUE, key)
                assertEquals(llave, t0, JSONObject(requireNotNull(captura?.orderRequestJson)).getLong("createdAtLocal"))
            }
            // 1) El pedido capturado trae la hora; la reserva se soltó antes de encolar (la caja abrió otra venta).
            val pedido = request.copy(createdAtLocal = t0)
            caja.reservarCobro(pedido, "staff-A", 5000, 500, null, orderExternalId = "venta-pedido", idempotencyKey = "venta-pedido", venueId = VENUE)
            assertEquals(t0, (f.row("venta-pedido")["createdAt"] as Number).toLong())
            f.sql("DELETE FROM pending_payments WHERE id='venta-pedido'")
            revisa("venta-pedido", caja.queueCashPayment(pedido, "staff-A", 5000, 500, null, orderExternalId = "venta-pedido",
                idempotencyKey = "venta-pedido", venueId = VENUE))
            // 2) El pedido no trae la hora: la hereda de la reserva en disco con la misma llave.
            caja.reservarCobro(request, "staff-A", 5000, 500, null, orderExternalId = "venta-disco", idempotencyKey = "venta-disco",
                venueId = VENUE, capturadaEn = t0)
            assertEquals("EN_VUELO", f.row("venta-disco")["syncStatus"])
            revisa("venta-disco", caja.queueCashPayment(request, "staff-A", 5000, 500, null, orderExternalId = "venta-disco",
                idempotencyKey = "venta-disco", venueId = VENUE))
        }
    }
    companion object {
        const val VENUE="FULLTEST-Q16-A"
        const val ORIGINAL="""{"externalId":"original-key","items":[{"productId":"original-product","quantity":1}]}"""
    }
}
