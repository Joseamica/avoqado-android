package com.avoqado.pos.payment

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.*
import com.avoqado.pos.tables.data.TableRoundDraftStore
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class Q16OutboxPrecioTest {
    class Fixture : AutoCloseable {
        val disk=Q16PrecioCapturadoTest.Fixture()
        val dao=mockk<SyncIntentDao>(relaxed=true)
        val drafts=TableRoundDraftStore(File(disk.directory,"table-round-drafts"))
        val outbox:SyncOutbox
        val prefs=mockk<SharedPreferences>(relaxed=true)
        val booted=CompletableDeferred<Unit>()
        init {
            every { prefs.getString(any(),any()) } returns "original-device"
            val context=mockk<Context>(relaxed=true)
            every { context.filesDir } returns disk.directory
            every { context.getSharedPreferences(any(),any()) } returns prefs
            val cache=mockk<CachedPayloadDao>(relaxed=true)
            coEvery { dao.soltarRetenidos() } answers { booted.complete(Unit); 0 }
            coEvery { dao.pendingFifo(any(),any()) } answers { pending(firstArg(),secondArg()) }
            coEvery { dao.hayCapturaSinResolverQ16(any()) } coAnswers { callOriginal() }
            coEvery { dao.referenciasSinResolverQ16(any(),any()) } coAnswers { callOriginal() }
            coEvery { dao.hayCapturaSinResolverSqlQ16(any()) } answers {
                (disk.query(Q16_VENUE_CAPTURE_UNRESOLVED_SQL,mapOf("venueId" to firstArg<String>())).single().values.single() as Number).toInt() != 0
            }
            coEvery { dao.referenciasSinResolverSqlQ16(any(),any()) } answers {
                val refs=secondArg<List<String>>()
                disk.query(Q16_CAPTURED_UNRESOLVED_REFS_SQL.replace(":refs",refs.indices.joinToString(",") { ":r$it" }),
                    mapOf("venueId" to firstArg<String>()) + refs.mapIndexed { i,s -> "r$i" to s }).map { it["capturedOrderRef"] as String }
            }
            coEvery { dao.canceladosCandidatosQ16(any(),any()) } answers {
                disk.query(Q15_CANCELLED_PAGE_SQL,mapOf("venueId" to firstArg<String>(),"cursor" to secondArg<String>())).map(disk::decode)
            }
            coEvery { dao.canceladosPorReferenciaQ16(any(),any(),any()) } answers {
                val refs=secondArg<List<String>>()
                disk.query("SELECT * FROM pending_payments WHERE venueId=:venueId AND capturedOrderRef IN (${refs.indices.joinToString(",") { ":r$it" }}) AND $Q15_CANCELLED_CANDIDATE_SQL AND id>:cursor ORDER BY id LIMIT 50",
                    mapOf("venueId" to firstArg<String>(),"cursor" to arg<String>(2)) + refs.mapIndexed { i,s -> "r$i" to s }).map(disk::decode)
            }
            coEvery { dao.resolve(any(),any(),any(),any(),any()) } answers {
                disk.sql("UPDATE pos_sync_intents SET status=:s WHERE id=:id",mapOf("s" to secondArg<String>(),"id" to firstArg<String>()));Unit
            }
            coEvery { dao.resolverCobroQ15(any(),any(),any(),any(),any(),any(),any(),any(),any(),any()) } coAnswers {
                dao.resolve(firstArg(),arg(3),arg(4),arg(5),arg(6))
                disk.sql(Q15_INTENT_RESULT_SQL,mapOf("venueId" to secondArg<String>(),"id" to firstArg<String>(),"motivo" to arg<String?>(7),"resultadoJson" to arg<String?>(8),"ahora" to arg<Long>(9)));Unit
            }
            coEvery { dao.insertWithNextSeq(any()) } coAnswers {
                val row=firstArg<SyncIntentEntity>()
                com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(row.venueId) {
                val seq=(disk.query("SELECT COALESCE(MAX(seq),0)+1 AS n FROM pos_sync_intents").single()["n"] as Number).toLong()
                disk.sql("INSERT INTO pos_sync_intents(id,venue_id,status,created_at,type,seq,payload_json,q15CaptureJson,capturedOrderRef) VALUES(:id,:v,:s,:at,:t,:seq,:p,:c,:r)",
                    mapOf("id" to row.id,"v" to row.venueId,"s" to row.status,"at" to row.createdAt,"t" to row.type,"seq" to seq,"p" to row.payloadJson,"c" to row.q15CaptureJson,"r" to row.capturedOrderRef));seq
                }
            }
            coEvery { dao.retenerIntentQ15(any(),any(),any(),any()) } answers {
                disk.sql(Q15_INTENT_HOLD_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),"motivo" to arg<String>(2),"ahora" to arg<Long>(3)))
            }
            coEvery { dao.soltarRetenidoQ16(any(),any()) } answers {
                disk.sql(Q16_INTENT_RELEASE_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>()))
            }
            val api=Retrofit.Builder().baseUrl(disk.server.url("/"))
                .client(okhttp3.OkHttpClient.Builder().addNetworkInterceptor(InterceptorDePrecioQ16).build())
                .addConverterFactory(Json { ignoreUnknownKeys=true }.asConverterFactory("application/json".toMediaType())).build().create(ApiService::class.java)
            outbox=SyncOutbox(dao,api,disk.network,disk.storage,disk.drawer,context,cache)
            // Arranca sin red; la colección observa esta instancia false. El test activa sólo
            // la lectura posterior para drenar explícitamente y esperar el método real.
            every { disk.network.isConnected } returns MutableStateFlow(false)
            outbox.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            outbox.start(VENUE)
        }
        fun add(id:String,seq:Int,ref:String="L",type:String=SyncIntentTypes.ADD_ITEMS) {
            val payload=JSONObject().put("localOrderId",ref).put("items",org.json.JSONArray("""[{"productId":"original-product","quantity":1}]""")).toString()
            disk.sql("INSERT INTO pos_sync_intents(id,venue_id,status,created_at,type,seq,payload_json) VALUES(:id,:v,'PENDING',1,:t,:s,:p)",mapOf("id" to id,"v" to VENUE,"t" to type,"s" to seq,"p" to payload))
        }
        fun pending(venue:String,limit:Int)=disk.query("SELECT * FROM pos_sync_intents WHERE venue_id=:v AND status IN ('PENDING','HELD') ORDER BY seq LIMIT :n",mapOf("v" to venue,"n" to limit)).map {
            SyncIntentEntity(it["id"] as String,it["venue_id"] as String,seq=(it["seq"] as Number).toLong(),type=it["type"] as String,payloadJson=it["payload_json"] as String,status=it["status"] as String,q15CaptureJson=it["q15CaptureJson"] as String?,q15Reason=it["q15Reason"] as String?,capturedOrderRef=it["capturedOrderRef"] as String?)
        }
        suspend fun drain() {
            check(booted.isCompleted)
            every { disk.network.isConnected } returns MutableStateFlow(true)
            outbox.replayNow(VENUE)
        }
        fun row(id:String)=disk.query("SELECT * FROM pos_sync_intents WHERE id=:id",mapOf("id" to id)).single()
        override fun close() { every { disk.network.isConnected } returns MutableStateFlow(false);outbox.stop();disk.close() }
    }
    @Test fun `OPEN y ADD anteriores a captura durable no salen ni adelantan FIFO`() = runTest {
        Fixture().use { f -> f.disk.seed(order="L"); f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.add("add",2);f.add("later",3,ref="other")
            val before=f.row("add"); f.drain()
            assertEquals(0,f.disk.server.requestCount)
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("open")["q15Reason"])
            assertEquals(before["payload_json"],f.row("add")["payload_json"]);assertEquals("PENDING",f.row("later")["status"])
        }
    }
    @Test fun `alias durable L a R retiene ADD despues de reinicio sin memoria`() = runTest {
        Fixture().use { f -> f.disk.seed(order="R");f.drafts.promote(VENUE,"L","R");f.add("add",1)
            f.disk.reopen();f.drain()
            assertEquals(0,f.disk.server.requestCount);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("add")["q15Reason"])
        }
    }
    @Test fun `historia SYNCED no bloquea ronda impaga`() = runTest {
        Fixture().use { f -> f.disk.seed(order="L");f.disk.sql("UPDATE pending_payments SET syncStatus='SYNCED'");f.add("unpaid",1);f.drain()
            assertTrue(f.disk.server.requestCount>0);assertNull(f.row("unpaid")["q15Reason"])
        }
    }
    @Test fun `misma ref en otra sucursal no bloquea ronda impaga`() = runTest {
        Fixture().use { f -> f.disk.seed(order="L");f.disk.sql("UPDATE pending_payments SET venueId='OTHER'");f.add("unpaid",1);f.drain()
            assertTrue(f.disk.server.requestCount>0);assertNull(f.row("unpaid")["q15Reason"])
        }
    }
    @Test fun `prefijo impago conserva body y no manda linked ni posterior`() = runTest {
        Fixture().use { f -> f.disk.seed(order="L");f.add("unpaid",1,ref="another");f.add("linked",2);f.add("after",3,ref="third");f.drain()
            assertTrue(f.disk.server.requestCount>0)
            val wire=JSONObject(f.disk.server.takeRequest().body.readUtf8()).getJSONArray("intents")
            assertEquals(1,wire.length());assertEquals("unpaid",wire.getJSONObject(0).getString("id"))
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("linked")["q15Reason"]);assertEquals("PENDING",f.row("after")["status"])
        }
    }
    @Test fun `redirect corrupto no se interpreta como ronda impaga`() = runTest {
        Fixture().use { f -> f.disk.seed(order="R");f.add("add",1)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest("$VENUE\u0000L".toByteArray()).joinToString("") { "%02x".format(it) }
            File(f.disk.directory,"table-round-drafts").apply { mkdirs();resolve("$hash.json").writeText("broken-json") }
            f.drain();assertEquals(0,f.disk.server.requestCount);assertEquals("PENDING",f.row("add")["status"])
        }
    }
    private fun recovered(id:String="held",type:String=SyncIntentTypes.ADD_ITEMS,status:String="ACKED",error:Any=JSONObject.NULL):JSONObject = JSONObject()
        .put("id",id).put("type",type).put("deviceId","original-device").put("seq",1).put("localRef","L")
        .put("status",status).put("errorCode",error).put("result",JSONObject().put("orderId","R").put("localOrderId","L").put("paymentId","paid-original").put("amount",10005).put("tipAmount",500).put("status","COMPLETED"))
    private fun Fixture.hold(id:String="held") { disk.sql("UPDATE pos_sync_intents SET q15Reason='PRICE_PROOF_PENDING' WHERE id=:id",mapOf("id" to id)) }
    private fun Fixture.response(raw:JSONObject) { disk.code=200;disk.response=JSONObject().put("data",JSONObject().put("intents",org.json.JSONArray().put(raw)).put("orders",org.json.JSONArray())).toString() }
    @Test fun `ACK recuperado promueve OPEN sin ejecutar reducer ni siguiente ADD`()=runTest {
        Fixture().use { f ->f.add("held",1,type=SyncIntentTypes.OPEN_TABLE);f.add("next",2);f.hold();f.response(recovered(type=SyncIntentTypes.OPEN_TABLE));f.drain()
            assertEquals("ACKED",f.row("held")["status"]);assertEquals("PENDING",f.row("next")["status"])
            assertEquals("R",f.drafts.loadResolved(VENUE,"L").first)
            assertEquals(1,f.disk.server.requestCount)
            val sent=f.disk.server.takeRequest();assertTrue(sent.path!!.endsWith("/sync/recovery"))
            val body=JSONObject(sent.body.readUtf8());assertEquals("original-device",body.getString("deviceId"));assertEquals("held",body.getJSONArray("intentIds").getString(0))
        }
    }
    @Test fun `metadata completa original es obligatoria antes ACK local`()=runTest {
        for(field in listOf("id","type","deviceId","seq","localRef")) {
            Fixture().use { f ->f.add("held",1);f.hold();val raw=recovered();raw.put(field,if(field=="seq") 2 else "OTHER");f.response(raw);f.drain()
                assertEquals("PENDING",f.row("held")["status"]);assertEquals(1,f.disk.server.requestCount)
            }
        }
    }
    @Test fun `PROCESSING REJECTED OUTCOME_UNKNOWN y resultado incompleto permanecen en FIFO`()=runTest {
        val cases=listOf(recovered(status="PROCESSING"),recovered(status="REJECTED",error="OUTCOME_UNKNOWN"),recovered(status="REJECTED",error="BUSINESS_RULE"),recovered().put("result",JSONObject.NULL),recovered().removeAndReturn("localRef"))
        for(raw in cases) Fixture().use { f ->f.add("held",1);f.add("next",2);f.hold();f.response(raw);f.drain()
            assertEquals("PENDING",f.row("held")["status"]);assertEquals("PENDING",f.row("next")["status"]);assertEquals(1,f.disk.server.requestCount)
        }
    }
    private fun JSONObject.removeAndReturn(key:String):JSONObject { remove(key);return this }
    @Test fun `sin identidad de dispositivo lookup no genera UUID ni escribe preferencias`()=runTest {
        Fixture().use { f ->f.add("held",1);f.hold();every { f.prefs.getString(any(),any()) } returns null;clearMocks(f.prefs,answers=false,recordedCalls=true);f.drain()
            assertEquals(0,f.disk.server.requestCount);verify(exactly=0) { f.prefs.edit() };assertEquals("PENDING",f.row("held")["status"])
        }
    }
    @Test fun `PAY recuperado conserva ACK aplicado con aviso Q15 sin repetir el dinero`()=runTest {
        Fixture().use { f ->f.add("held",1,type=SyncIntentTypes.PAY_CASH);f.hold();val before=f.row("held")["payload_json"]
            f.response(recovered(type=SyncIntentTypes.PAY_CASH));f.drain()
            assertEquals("ACKED",f.row("held")["status"]);assertNotNull(f.row("held")["q15Reason"])
            assertEquals(before,f.row("held")["payload_json"]);assertEquals(1,f.disk.server.requestCount)
            coVerify(exactly=1) { f.dao.resolverCobroQ15("held",VENUE,1L,"ACKED",any(),any(),any(),any(),any(),any()) }
        }
    }
    @Test fun `captura PAY real durante lectura SQL vacia corta antes del POST`()=runTest {
        Fixture().use { f ->
            f.add("add",1)
            val read=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            coEvery { f.dao.referenciasSinResolverQ16(any(),any()) } coAnswers {
                val refs=secondArg<List<String>>()
                val original=f.disk.query(Q16_CAPTURED_UNRESOLVED_REFS_SQL.replace(":refs",refs.indices.joinToString(",") { ":r$it" }),
                    mapOf("venueId" to firstArg<String>())+refs.mapIndexed { i,v -> "r$i" to v }).map { it["capturedOrderRef"] as String }
                read.complete(Unit);release.await();original
            }
            val drain=async(Dispatchers.Unconfined) { f.drain() }
            try {
                read.await()
                f.outbox.enqueue(VENUE,SyncIntentTypes.PAY_CASH,Json.parseToJsonElement("""{"localOrderId":"L","amountCents":10005,"tipCents":500}""").jsonObject,id="pay",retenido=true)
                release.complete(Unit);drain.await()
                assertEquals(0,f.disk.server.requestCount);assertEquals("PENDING",f.row("add")["status"])
                assertEquals("HELD",f.row("pay")["status"]);assertNotNull(f.row("pay")["q15CaptureJson"])
            } finally { release.complete(Unit);drain.cancelAndJoin() }
        }
    }
    @Test fun `captura de otra sucursal durante consulta no bloquea admision propia`()=runTest {
        Fixture().use { f ->
            f.add("add",1);val read=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            coEvery { f.dao.referenciasSinResolverQ16(any(),any()) } coAnswers {
                val refs=secondArg<List<String>>()
                val original=f.disk.query(Q16_CAPTURED_UNRESOLVED_REFS_SQL.replace(":refs",refs.indices.joinToString(",") { ":r$it" }),
                    mapOf("venueId" to firstArg<String>())+refs.mapIndexed { i,v -> "r$i" to v }).map { it["capturedOrderRef"] as String }
                read.complete(Unit);release.await();original
            }
            val drain=async(Dispatchers.Unconfined) { f.drain() }
            try {
                read.await();f.outbox.enqueue("OTHER",SyncIntentTypes.PAY_CASH,Json.parseToJsonElement("""{"localOrderId":"L","amountCents":10005,"tipCents":500}""").jsonObject,id="pay",retenido=true)
                release.complete(Unit);drain.await()
                assertEquals(1,f.disk.server.requestCount);assertTrue(f.disk.server.takeRequest().path!!.endsWith("/sync/intents"))
                assertEquals("OTHER",f.row("pay")["venue_id"])
            } finally { release.complete(Unit);drain.cancelAndJoin() }
        }
    }
    @Test fun `ORDER con alias durable L a R espera lookup original y nunca paga L`()=runTest {
        Fixture().use { f ->
            f.disk.seed(order="L");f.drafts.promote(VENUE,"L","R")
            coEvery { f.disk.outbox.aliasDurableQ16(any(),any()) } coAnswers { f.outbox.aliasDurableQ16(firstArg(),secondArg()) }
            f.disk.service.usarContextoDeSincronizacion(Dispatchers.Unconfined);f.disk.service.start();f.disk.service.esperarSincronizacionActualQ16()
            assertEquals(0,f.disk.server.requestCount);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.disk.row()["q15Reason"])
            val before=f.disk.row()["q15CaptureJson"]
            f.disk.code=200;f.disk.response="""{"data":{"intents":[],"orders":[{"externalId":"original-key","orderId":"R"}]}}"""
            f.disk.service.syncNow();f.disk.service.esperarSincronizacionActualQ16()
            assertEquals("R",f.disk.row()["orderId"])
            f.disk.response="""{"success":true,"payment":{"paymentId":"paid-original","orderId":"R","amount":10005,"tipAmount":500,"status":"COMPLETED"}}"""
            f.disk.service.syncNow();f.disk.service.esperarSincronizacionActualQ16()
            assertEquals("SYNCED",f.disk.row()["syncStatus"]);assertEquals(before,f.disk.row()["q15CaptureJson"])
            assertEquals(2,f.disk.server.requestCount)
            assertTrue(f.disk.server.takeRequest().path!!.endsWith("/sync/recovery"));assertTrue(f.disk.server.takeRequest().path!!.endsWith("/orders/R/pay"))
        }
    }
    @Test fun `promocion durable durante lectura SQL vacia invalida intento antes POST`()=runTest {
        Fixture().use { f ->
            f.disk.seed(order="R");f.add("add",1)
            val read=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            coEvery { f.dao.referenciasSinResolverQ16(any(),any()) } coAnswers {
                val refs=secondArg<List<String>>()
                val original=f.disk.query(Q16_CAPTURED_UNRESOLVED_REFS_SQL.replace(":refs",refs.indices.joinToString(",") { ":r$it" }),
                    mapOf("venueId" to firstArg<String>())+refs.mapIndexed { i,v -> "r$i" to v }).map { it["capturedOrderRef"] as String }
                assertTrue(original.isEmpty());read.complete(Unit);release.await();original
            }
            val drain=async(Dispatchers.Unconfined) { f.drain() }
            try {
                read.await();f.drafts.promote(VENUE,"L","R");release.complete(Unit);drain.await()
                assertEquals(0,f.disk.server.requestCount);assertEquals("PENDING",f.row("add")["status"])
                assertEquals("R",f.drafts.loadResolved(VENUE,"L").first)
            } finally { release.complete(Unit);drain.cancelAndJoin() }
        }
    }
    @Test fun `crash entre destino y redirect reabre detenido y nunca pierde captura L`()=runTest {
        Fixture().use { f ->
            f.disk.seed(order="L");f.drafts.promote(VENUE,"L","R")
            val files=File(f.disk.directory,"table-round-drafts").listFiles()!!.toList()
            val redirect=files.single { JSONObject(it.readText()).optString("redirectOrderId") == "R" }
            val originalRedirect=redirect.readBytes();check(redirect.delete())
            val reopened=TableRoundDraftStore(File(f.disk.directory,"table-round-drafts"))
            assertTrue(runCatching { reopened.priceReferencesQ16(VENUE,"R") }.isFailure)
            assertTrue(reopened.priceReferencesQ16(VENUE,"L").contains("L"))
            f.add("add-r",1,ref="R");f.disk.reopen();f.drain()
            assertEquals(0,f.disk.server.requestCount);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("add-r")["q15Reason"])
            redirect.writeBytes(originalRedirect)
            assertEquals(setOf("L","R"),reopened.priceReferencesQ16(VENUE,"R"))
            assertEquals("L",f.disk.row()["capturedOrderRef"])
        }
    }
    @Test fun `captura tardia L despues de promote retiene ADD R sin reindexar`()=runTest {
        Fixture().use { f ->
            f.drafts.promote(VENUE,"L","R");f.drafts.save(VENUE,"R",emptyList(),emptyMap())
            f.add("add-r",1,ref="R")
            f.outbox.enqueue(VENUE,SyncIntentTypes.PAY_CASH,Json.parseToJsonElement("""{"localOrderId":"L","amountCents":10005,"tipCents":500}""").jsonObject,id="late",retenido=true)
            f.disk.reopen();f.drain()
            assertEquals(0,f.disk.server.requestCount);assertEquals("L",f.row("late")["capturedOrderRef"])
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("add-r")["q15Reason"])
        }
    }
    @Test fun `snapshot historico sin inversa no se convierte en vacio al guardar`()=runTest {
        Fixture().use { f ->
            f.drafts.save(VENUE,"R",emptyList(),emptyMap())
            val file=File(f.disk.directory,"table-round-drafts").listFiles()!!.single()
            val raw=JSONObject(file.readText());raw.remove("originalOrderIdsQ16");file.writeText(raw.toString())
            f.drafts.save(VENUE,"R",emptyList(),emptyMap())
            assertTrue(runCatching { f.drafts.priceReferencesQ16(VENUE,"R") }.isFailure)
            f.add("legacy",1,ref="R");f.drain();assertEquals(1,f.disk.server.requestCount)
            assertNull(f.row("legacy")["q15Reason"])
            assertTrue(runCatching { f.drafts.priceReferencesQ16(VENUE,"R") }.isFailure)
        }
    }
    @Test fun `inversas no truncan limite y no cruzan sucursal`()=runTest {
        Fixture().use { f ->
            f.drafts.promote(VENUE,"L","R")
            assertEquals(setOf("R"),f.drafts.priceReferencesQ16("OTHER","R"))
            val target=File(f.disk.directory,"table-round-drafts").listFiles()!!.single { !JSONObject(it.readText()).has("redirectOrderId") || JSONObject(it.readText()).isNull("redirectOrderId") }
            val raw=JSONObject(target.readText());raw.put("originalOrderIdsQ16",org.json.JSONArray((1..101).map { "alias-$it" }));target.writeText(raw.toString())
            assertTrue(runCatching { f.drafts.priceReferencesQ16(VENUE,"R") }.isFailure)
        }
    }
    private fun historical(f:Fixture,ref:String="R") {
        f.drafts.save(VENUE,ref,emptyList(),emptyMap())
        val file=java.io.File(f.disk.directory,"table-round-drafts").listFiles()!!.single()
        val raw=org.json.JSONObject(file.readText());raw.remove("originalOrderIdsQ16");file.writeText(raw.toString())
    }
    @Test fun `historico impago conserva flujo con historia terminal pero sin captura unresolved`()=runTest {
        Fixture().use { f ->
            historical(f);f.disk.seed(order="old-order");f.disk.sql("UPDATE pending_payments SET syncStatus='SYNCED'")
            f.add("old-pay",1,type=SyncIntentTypes.PAY_CASH);f.disk.sql("UPDATE pos_sync_intents SET status='ACKED' WHERE id='old-pay'")
            f.add("unpaid",2,ref="R");f.drain()
            assertEquals(1,f.disk.server.requestCount);assertNull(f.row("unpaid")["q15Reason"])
            assertTrue(runCatching { f.drafts.priceReferencesQ16(VENUE,"R") }.isFailure)
        }
    }
    @Test fun `historico alias R no ignora captura L aunque indice no enumere alias`()=runTest {
        Fixture().use { f ->
            historical(f);f.disk.seed(order="L");f.add("unpaid",1,ref="R");f.drain()
            assertEquals(0,f.disk.server.requestCount)
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("unpaid")["q15Reason"])
            assertEquals("L",f.disk.row()["capturedOrderRef"])
        }
    }
    @Test fun `writer real PAY durante ausencia SQL historica invalida token antes POST`()=runTest {
        Fixture().use { f ->
            historical(f);f.add("unpaid",1,ref="R")
            val read=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            coEvery { f.dao.hayCapturaSinResolverQ16(any()) } coAnswers {
                val answer=(f.disk.query(Q16_VENUE_CAPTURE_UNRESOLVED_SQL,mapOf("venueId" to firstArg<String>())).single().values.single() as Number).toInt()!=0
                assertFalse(answer);read.complete(Unit);release.await();answer
            }
            val drain=async(Dispatchers.Unconfined) { f.drain() }
            try {
                read.await();f.outbox.enqueue(VENUE,SyncIntentTypes.PAY_CASH,Json.parseToJsonElement("""{"localOrderId":"L","amountCents":10005,"tipCents":500}""").jsonObject,id="late-capture",retenido=true)
                release.complete(Unit);drain.await()
                assertEquals(0,f.disk.server.requestCount);assertEquals("PENDING",f.row("unpaid")["status"])
                assertEquals("HELD",f.row("late-capture")["status"]);assertNotNull(f.row("late-capture")["q15CaptureJson"])
            } finally { release.complete(Unit);drain.cancelAndJoin() }
        }
    }
    @Test fun `error de prueba de ausencia historica conserva hold y otra sucursal no contamina`()=runTest {
        Fixture().use { f ->
            historical(f);f.disk.seed(order="L");f.disk.sql("UPDATE pending_payments SET venueId='OTHER'")
            f.add("unpaid",1,ref="R");f.drain();assertEquals(1,f.disk.server.requestCount)
            coEvery { f.dao.hayCapturaSinResolverQ16(any()) } throws java.io.IOException("owned read failure")
            f.drain();assertEquals(1,f.disk.server.requestCount)
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("unpaid")["q15Reason"])
        }
    }

    // 19-oct (mesa sin red retenida, D3 2/2): la mesa abierta y cobrada sin red esperaba a su PROPIO cobro, que va detrás en la
    // cola: OPEN_TABLE retenido por PRICE_PROOF_PENDING para siempre. Un PAY_CASH de la misma cola, en espera normal y con su
    // captura, ya no retiene: sale después de OPEN/ADD por FIFO, y la diferencia de precio se ve al confirmar el cobro.
    private suspend fun Fixture.pagarSinRed(id:String="pay",ref:String="L") {
        outbox.enqueue(VENUE,SyncIntentTypes.PAY_CASH,Json.parseToJsonElement("""{"localOrderId":"$ref","amountCents":3000,"tipCents":0}""").jsonObject,id=id)
    }
    private fun Fixture.mandado():List<String> {
        val sent=disk.server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS) ?: return emptyList()
        if (sent.path!!.endsWith("/sync/recovery")) return mandado()
        val wire=JSONObject(sent.body.readUtf8()).getJSONArray("intents")
        return (0 until wire.length()).map { wire.getJSONObject(it).getString("id") }
    }
    @Test fun `mesa sin red con su cobro en la misma cola sale en orden y no se espera a si misma`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.add("add",2);f.pagarSinRed();f.add("clear",4,type="CLEAR_TABLE")
            assertEquals("PENDING",f.row("pay")["status"]);assertNotNull(f.row("pay")["q15CaptureJson"])
            f.drain()
            assertNull(f.row("open")["q15Reason"])
            assertEquals(listOf("open","add"),f.mandado().take(2))
        }
    }
    @Test fun `cobro viejo sin captura en la cola sigue reteniendo su mesa`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.add("pay",2,type=SyncIntentTypes.PAY_CASH)
            f.disk.sql("UPDATE pos_sync_intents SET capturedOrderRef='L' WHERE id='pay'");f.drain()
            assertEquals(0,f.disk.server.requestCount);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("open")["q15Reason"])
        }
    }
    @Test fun `cobro rechazado de la misma mesa sigue reteniendo`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.pagarSinRed()
            f.disk.sql("UPDATE pos_sync_intents SET status='REJECTED' WHERE id='pay'");f.drain()
            assertEquals(0,f.disk.server.requestCount);assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("open")["q15Reason"])
        }
    }
    // Los aparatos que YA tienen la mesa atorada (2.25.3/2.25.4): sin esto, A no suelta nada (la marca ya está puesta). Como
    // mostrador en 2.25.0: si el servidor dice que no tiene ese intent (vacío estricto), se quita la marca y la admisión de
    // precio vuelve a decidir con la regla nueva.
    private fun Fixture.vacio() { disk.code=200;disk.response="""{"data":{"intents":[],"orders":[]}}""" }
    @Test fun `mesa ya atorada se suelta sola si el servidor no tiene la ronda y sale en su turno`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.add("add",2);f.pagarSinRed();f.hold("open");f.vacio();f.drain()
            assertNull(f.row("open")["q15Reason"])
            assertEquals(listOf("open","add"),f.mandado().take(2))
        }
    }
    @Test fun `atorada con historial dudoso vuelve a quedar retenida aunque la recuperacion venga vacia`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.add("pay",2,type=SyncIntentTypes.PAY_CASH)
            f.disk.sql("UPDATE pos_sync_intents SET capturedOrderRef='L' WHERE id='pay'");f.hold("open");f.vacio();f.drain()
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("open")["q15Reason"])
            assertTrue(f.mandado().isEmpty())
        }
    }
    @Test fun `recuperacion con ordenes o intents no vacios no suelta nada`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.pagarSinRed();f.hold("open")
            f.disk.code=200;f.disk.response="""{"data":{"intents":[],"orders":[{"id":"R"}]}}""";f.drain()
            assertEquals(ConciliacionDelCobro.PRICE_PROOF_PENDING,f.row("open")["q15Reason"]);assertEquals(1,f.disk.server.requestCount)
        }
    }
    // Codex r3 (19-oct): la tolerancia de redondeo sale SÓLO de los combos de esta mesa (1¢ por componente). Las piezas de precio
    // normal no dan margen: en una mesa grande, un cambio real chico sí avisa.
    @Test fun `la tolerancia del cobro sin red sale solo de los combos de la mesa`() = runTest {
        Fixture().use { f -> f.add("open",1,type=SyncIntentTypes.OPEN_TABLE);f.add("add",2);f.add("otra",3,ref="otra-mesa")
            f.disk.sql("INSERT INTO pos_sync_intents(id,venue_id,status,created_at,type,seq,payload_json) VALUES('combo',:v,'PENDING',1,'ADD_ITEMS',4,:p)",
                mapOf("v" to VENUE,"p" to """{"localOrderId":"L","items":[{"quantity":1,"promotionRef":{"selections":[{},{},{}]}}]}"""))
            f.outbox.enqueue(VENUE,SyncIntentTypes.PAY_CASH,Json.parseToJsonElement("""{"localOrderId":"L","amountCents":3000,"tipCents":0}""").jsonObject,
                id="pay",retenido=true,restanteEsperadoCents=0)
            assertEquals(3,ConciliacionDelCobro.leerCaptura(f.row("pay")["q15CaptureJson"] as String,VENUE,"pay")!!.toleranciaCents)
        }
    }
    companion object { const val VENUE=Q16PrecioCapturadoTest.VENUE }
}
