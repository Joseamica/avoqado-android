package com.avoqado.pos.sync

import androidx.sqlite.SQLiteStatement
import androidx.room.RoomRawQuery
import com.avoqado.pos.sync.data.*
import com.avoqado.pos.payment.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Executes the production DAO defaults and SQL against SQLite, not Room's generated transaction wrapper. */
class SalesNoticeSqlTest {
    class Fixture(path: String = ":memory:") : AutoCloseable {
        val db:Connection=DriverManager.getConnection("jdbc:sqlite:$path")
        val dao=object:SalesNoticeDao() {
            override suspend fun read(query:RoomRawQuery)=statement(query) { s -> s.executeQuery().use { r -> buildList { while(r.next())add(row(r)) } } }
            override suspend fun count(query:RoomRawQuery)=statement(query) { s -> s.executeQuery().use { it.next();it.getInt(1) } }
            override suspend fun aggregate(query:RoomRawQuery)=statement(query) { s -> s.executeQuery().use { r ->
                check(r.next())
                SalesNoticeAggregate(r.getInt("total"),r.getInt("unseenCount"),r.getInt("failedGroup"),r.getInt("interruptedGroup"),
                    r.getInt("q15Group"),r.getInt("rejectedGroup"),r.getInt("ackfailedGroup"),r.getInt("ackinterruptedGroup"),
                    r.getInt("ackq15Group"),r.getInt("ackrejectedGroup"),r.getInt("untouchedQ15"),r.getInt("untouchedRejected"))
            } }
            override suspend fun save(row:SalesNoticeAcknowledgementEntity) {
                val columns=listOf("venueId","origin","id","actor","acknowledgedAt")+SALES_NOTICE_VERSION_COLUMNS
                val values=listOf(row.venueId,row.origin,row.id,row.actor,row.acknowledgedAt,row.capture,row.body,row.result,row.reason,
                    row.error,row.code,row.type,row.method,row.amountCents,row.tipCents,row.tenderTypeId,row.tenderRevision,row.reference,row.orderId,row.state)
                db.prepareStatement("INSERT OR REPLACE INTO sales_notice_acknowledgements (${columns.joinToString(",")}) VALUES (${columns.joinToString(","){"?"}})").use { s ->
                    values.forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeUpdate()
                }
            }
            override fun changes():Flow<Int> = flowOf(0)
        }
        init {
            val schema=JSONObject(File("schemas/com.avoqado.pos.core.data.local.database.AvoqadoDatabase/16.json").readText()).getJSONObject("database").getJSONArray("entities")
            db.createStatement().use { s -> for(i in 0 until schema.length()) { val e=schema.getJSONObject(i);s.execute(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName"))) } }
            sql(SALES_NOTICE_CREATE_SQL)
        }
        fun sql(s:String)=db.createStatement().use { it.execute(s);Unit }
        fun insert(table:String,values:Map<String,Any?>) {
            db.prepareStatement("INSERT INTO $table (${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(","){"?"}})").use { s -> values.values.forEachIndexed { i,v->s.setObject(i+1,v) };s.executeUpdate() }
        }
        fun payment(id:String="cash",venue:String="venue-A",changes:Map<String,Any?> = emptyMap()) {
            val capture=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(venue,id,amountCents=100,tipCents=0,method="CASH"))
            insert("pending_payments",mapOf("id" to id,"venueId" to venue,"staffId" to "cashier","amountCents" to 100,"tipCents" to 0,
                "method" to "CASH","paymentType" to "FAST","syncStatus" to "FAILED","retryCount" to 0,"createdAt" to 1000L,"q15CaptureJson" to capture)+changes)
        }
        fun intent(id:String="intent",venue:String="venue-A",changes:Map<String,Any?> = emptyMap()) {
            val payload="""{"orderId":"order-A","amountCents":100,"tipCents":0}"""
            insert("pos_sync_intents",mapOf("id" to id,"venue_id" to venue,"seq" to 1,"type" to "PAY_CASH","payload_json" to payload,
                "status" to "REJECTED","created_at" to 1000L,"q15CaptureJson" to ConciliacionDelCobro.capturarIntent(venue,id,payload))+changes)
        }
        fun economic():List<List<Any?>> = listOf("pending_payments","pos_sync_intents").flatMap { t ->
            db.createStatement().use { s -> s.executeQuery("SELECT * FROM $t ORDER BY id").use { r -> buildList { while(r.next())add((1..r.metaData.columnCount).map { r.getObject(it) }) } } }
        }
        suspend fun <T> write(block:suspend ()->T):T {
            db.autoCommit=false
            try { val value=block();db.commit();return value }catch(e:Throwable){db.rollback();throw e}finally{db.autoCommit=true}
        }
        private fun <T> statement(q:RoomRawQuery,block:(java.sql.PreparedStatement)->T):T {
            val args=sortedMapOf<Int,Any?>()
            val binder=java.lang.reflect.Proxy.newProxyInstance(SQLiteStatement::class.java.classLoader,arrayOf(SQLiteStatement::class.java)) { _,method,values ->
                when(method.name) {
                    "bindNull" -> args[values!![0] as Int]=null
                    "bindLong","bindDouble","bindText","bindBlob" -> args[values!![0] as Int]=values[1]
                    else -> error("Unexpected SQLite binder operation: ${method.name}")
                };null
            } as SQLiteStatement
            q.getBindingFunction().invoke(binder)
            return db.prepareStatement(q.sql).use { s->args.forEach { (i,v)->s.setObject(i,v) };block(s) }
        }
        private fun row(r:ResultSet):SalesNoticeSnapshot {
            fun n(k:String)=r.getObject(k)?.let { (it as Number).toLong() }
            return SalesNoticeSnapshot(r.getString("venueId"),r.getString("origin"),r.getString("id"),r.getLong("createdAt"),
                r.getString("type"),r.getString("method"),n("amountCents")?.toInt(),n("tipCents")?.toInt(),r.getString("tenderTypeId"),
                n("tenderRevision")?.toInt(),r.getString("reference"),r.getString("orderId"),r.getString("state"),r.getString("capture"),
                r.getString("body"),r.getString("result"),r.getString("reason"),r.getString("error"),r.getString("code"),n("reviewedAt"),
                r.getString("reviewedBy"),r.getString("reviewRole"),n("cashTenderedCents")?.toInt(),n("changeCents")?.toInt(),
                r.getString("splitType"),r.getString("customerId"),r.getInt("failedGroup"),r.getInt("interruptedGroup"),r.getInt("q15Group"),
                r.getInt("rejectedGroup"),r.getString("acknowledgedActor"),n("acknowledgedAt"))
        }
        override fun close(){db.close()}
    }
    @Test fun `Entendido persists separately and preserves every economic field and existing barriers`()=runBlocking {
        Fixture().use { f ->
            f.payment();f.intent();f.payment("card",changes=mapOf("method" to "CREDIT_CARD"));f.payment("manual",changes=mapOf("tenderTypeId" to "bbva"))
            f.intent("Q16",changes=mapOf("type" to "OPEN_TABLE","q15Reason" to "PRICE_PROOF_PENDING"))
            val before=f.economic();val rows=f.dao.page("venue-A","",null,emptySet()).rows
            assertEquals(setOf("cash","intent","card","manual"),rows.map { it.id }.toSet())
            assertTrue(f.write { f.dao.acknowledge(rows.single { it.id=="cash" },"manager",12,emptySet()) })
            assertNotNull(f.dao.page("venue-A","",null,emptySet()).rows.single { it.id=="cash" }.acknowledgement)
            for(id in listOf("card","manual"))assertFalse(f.write { f.dao.acknowledge(rows.single { it.id==id },"manager",13,emptySet()) })
            assertEquals(before,f.economic())
        }
    }
    @Test fun `old snapshot cannot acknowledge new cause and retry does not reannounce`()=runBlocking {
        Fixture().use { f ->
            f.payment();val old=f.dao.page("venue-A","",null,emptySet()).rows.single();val before=f.economic()
            assertTrue(f.write { f.dao.acknowledge(old,"manager",12,emptySet()) });assertEquals(before,f.economic())
            f.sql("UPDATE pending_payments SET retryCount=5,lastRetryAt=999 WHERE id='cash'")
            assertNotNull(f.dao.page("venue-A","",null,emptySet()).rows.single().acknowledgement)
            f.sql("UPDATE pending_payments SET lastError='new cause' WHERE id='cash'")
            assertNull(f.dao.page("venue-A","",null,emptySet()).rows.single().acknowledgement)
            val changed=f.economic();assertFalse(f.write { f.dao.acknowledge(old,"manager",13,emptySet()) });assertEquals(changed,f.economic())
        }
    }
    @Test fun `125 source rows search before pagination and preserve tied timestamps across three pages`()=runBlocking {
        Fixture().use { f ->
            repeat(125){if(it%2==0)f.payment("p%03d".format(it))else f.intent("i%03d".format(it))}
            val before=f.economic();val ids=mutableListOf<SalesNoticeKey>();var cursor:SalesNoticeCursor?=null
            do { val page=f.dao.page("venue-A","",cursor,emptySet());assertEquals(125,page.total);assertTrue(page.rows.size<=50)
                ids.addAll(page.rows.map {it.key});cursor=page.nextCursor
            }while(cursor!=null)
            assertEquals(125,ids.size);assertEquals(125,ids.toSet().size)
            assertEquals(1,f.dao.page("venue-A","p124",null,emptySet()).total);assertEquals(before,f.economic())
        }
    }
    @Test fun `write failure rolls back after successful source read and keeps all money`()=runBlocking {
        Fixture().use { f ->f.payment();val row=f.dao.page("venue-A","",null,emptySet()).rows.single();val before=f.economic()
            f.sql("CREATE TRIGGER fail_notice BEFORE INSERT ON sales_notice_acknowledgements BEGIN SELECT RAISE(ABORT,'disk full'); END")
            var failed=false
            try{f.write { f.dao.acknowledge(row,"manager",12,emptySet()) }}catch(e:java.sql.SQLException){failed=true}
            assertTrue("a failed DB write must not report success",failed);assertEquals(before,f.economic())
            assertNull(f.dao.page("venue-A","",null,emptySet()).rows.single().acknowledgement)
        }
    }
}
