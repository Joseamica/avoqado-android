package com.avoqado.pos.sync.data

import androidx.room.*
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

enum class SalesNoticeOrigin { PAYMENT, INTENT }
data class SalesNoticeKey(val venueId: String, val origin: SalesNoticeOrigin, val id: String)
data class SalesNoticeCursor(val createdAt: Long, val origin: SalesNoticeOrigin, val id: String)
data class SalesNoticePage(val rows: List<SalesNoticeSnapshot>, val total: Int, val nextCursor: SalesNoticeCursor?)
data class SalesNoticeSummary(val pendingCount: Int, val unseenCount: Int,
    val alertContributions: Map<String, Int>, val acknowledgedContributions: Map<String, Int>,
    val untouchedQ15: Int = 0, val untouchedRejected: Int = 0)
data class SalesNoticeVersion(val capture: String?, val body: String?, val result: String?, val reason: String?,
    val error: String?, val code: String?, val type: String, val method: String?, val amountCents: Int?, val tipCents: Int?,
    val tenderTypeId: String?, val tenderRevision: Int?, val reference: String?, val orderId: String?, val state: String)
data class SalesNoticeAcknowledgement(val actor: String, val acknowledgedAt: Long)

@Entity(tableName = "sales_notice_acknowledgements", primaryKeys = ["venueId", "origin", "id"])
data class SalesNoticeAcknowledgementEntity(val venueId: String, val origin: String, val id: String,
    val actor: String, val acknowledgedAt: Long, val capture: String?, val body: String?, val result: String?,
    val reason: String?, val error: String?, val code: String?, val type: String, val method: String?,
    val amountCents: Int?, val tipCents: Int?, val tenderTypeId: String?, val tenderRevision: Int?,
    val reference: String?, val orderId: String?, val state: String)

// The version is a tuple, never a concatenation/digest; IS gives explicit NULL equality.
val SALES_NOTICE_VERSION_COLUMNS = listOf("capture", "body", "result", "reason", "error", "code", "type", "method",
    "amountCents", "tipCents", "tenderTypeId", "tenderRevision", "reference", "orderId", "state")
const val SALES_NOTICE_CREATE_SQL = """CREATE TABLE IF NOT EXISTS sales_notice_acknowledgements (
 venueId TEXT NOT NULL,origin TEXT NOT NULL,id TEXT NOT NULL,actor TEXT NOT NULL,acknowledgedAt INTEGER NOT NULL,
 capture TEXT,body TEXT,result TEXT,reason TEXT,error TEXT,code TEXT,type TEXT NOT NULL,method TEXT,amountCents INTEGER,
 tipCents INTEGER,tenderTypeId TEXT,tenderRevision INTEGER,reference TEXT,orderId TEXT,state TEXT NOT NULL,
 PRIMARY KEY(venueId,origin,id))"""

/** Read-only projection; source JSON/identity is retained for the existing actions, never regenerated. */
data class SalesNoticeSnapshot(val venueId: String, val origin: String, val id: String, val createdAt: Long,
    val type: String, val method: String?, val amountCents: Int?, val tipCents: Int?, val tenderTypeId: String?,
    val tenderRevision: Int?, val reference: String?, val orderId: String?, val state: String,
    val capture: String?, val body: String?, val result: String?, val reason: String?, val error: String?, val code: String?,
    val reviewedAt: Long?, val reviewedBy: String?, val reviewRole: String?, val cashTenderedCents: Int?,
    val changeCents: Int?, val splitType: String?, val customerId: String?, val failedGroup: Int,
    val interruptedGroup: Int, val q15Group: Int, val rejectedGroup: Int,
    val acknowledgedActor: String?, val acknowledgedAt: Long?) {
    @get:Ignore val key get() = SalesNoticeKey(venueId, SalesNoticeOrigin.valueOf(origin), id)
    @get:Ignore val cursor get() = SalesNoticeCursor(createdAt, key.origin, id)
    @get:Ignore val version get() = SalesNoticeVersion(capture,body,result,reason,error,code,type,method,amountCents,tipCents,
        tenderTypeId,tenderRevision,reference,orderId,if(state in setOf("SYNCED","ACKED")) "APPLIED" else "UNRESOLVED")
    @get:Ignore val acknowledgement get() = acknowledgedActor?.let { actor -> acknowledgedAt?.let { SalesNoticeAcknowledgement(actor,it) } }
    @get:Ignore val originalAmountCents get() = ConciliacionDelCobro.leerCaptura(capture,venueId,id)?.amountCents
    @get:Ignore val originalTipCents get() = ConciliacionDelCobro.leerCaptura(capture,venueId,id)?.tipCents
    @get:Ignore val originalMethod get() = ConciliacionDelCobro.leerCaptura(capture,venueId,id)?.method
    @get:Ignore val originalCreatedAt get() = ConciliacionDelCobro.leerCaptura(capture,venueId,id)?.primeraCapturaEn ?: createdAt
    fun eligible(): Boolean {
        if (reason == ConciliacionDelCobro.PRICE_PROOF_PENDING || failedGroup+interruptedGroup+q15Group+rejectedGroup == 0) return false
        if (origin == "PAYMENT" && type !in setOf("FAST","ORDER")) return false
        val c = ConciliacionDelCobro.leerCaptura(capture,venueId,id) ?: return false
        if (ConciliacionDelCobro.guardarCaptura(c) != capture || c.method != "CASH" || c.tenderTypeId != null ||
            c.tenderRevision != null || tenderTypeId != null || tenderRevision != null) return false
        if (origin == "PAYMENT" && (method != "CASH" || amountCents != c.amountCents || tipCents != c.tipCents)) return false
        if (origin == "INTENT" && (type != "PAY_CASH" || c.intentoPayloadJson != body)) return false
        fun native(raw: String?, normalizedResult: Boolean = false): Boolean {
            if (raw == null) return true
            val o = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return false
            if(normalizedResult) {
                val allowed=setOf("paymentId","orderId","amountCents","tipCents","method","splitType","tenderTypeId","tenderRevision",
                    "camposDiferentes","camposNoComprobados","totalAlCobrarCents","totalEnElServidorCents","saldoEsperadoCents","saldoEnElServidorCents","cobradoDeMasCents")
                if(o.keys.any {it !in allowed} || listOf("paymentId","orderId").any { key ->
                    (o[key] as? JsonPrimitive)?.takeIf {it.isString}?.content.isNullOrBlank()
                } || listOf("camposDiferentes","camposNoComprobados").any {key ->
                    val values=o[key] as? JsonArray
                    values == null || values.any {(it as? JsonPrimitive)?.isString != true}
                }) return false
            }
            return o.keys.none { it.contains("card",true) || it.contains("terminal",true) || it.contains("authorization",true) } &&
                (o["method"] == null || o["method"] == JsonNull || o["method"] == JsonPrimitive("CASH")) &&
                listOf("tenderTypeId","tenderRevision").all { o[it] == null || o[it] == JsonNull }
        }
        return native(result,true) && (origin != "INTENT" || native(body))
    }
    internal fun cancelledReviewed(): Boolean {
        if (origin != "PAYMENT") return false
        return RevisionCanceladoCero.revisado(PendingPaymentEntity(id,venueId,"",amountCents ?: -1,tipCents ?: -1,
            method ?: "",type,orderId=orderId,orderRequestJson=body,syncStatus=state,createdAt=createdAt,
            tenderTypeId=tenderTypeId,tenderRevision=tenderRevision,q15CaptureJson=capture,q15ResultJson=result,
            q15Reason=reason,q15ReviewedAt=reviewedAt,q15ReviewedBy=reviewedBy,q15ReviewRole=reviewRole,
            capturedOrderRef=reference,cashTenderedCents=cashTenderedCents,changeCents=changeCents,
            splitType=splitType ?: "FULLPAYMENT",customerId=customerId))
    }
}

internal object SalesNoticeSql {
    private val payment = """SELECT venueId,'PAYMENT' AS origin,id,createdAt,paymentType AS type,method,amountCents,tipCents,
 tenderTypeId,tenderRevision,capturedOrderRef AS reference,orderId,
 syncStatus AS state,
 q15CaptureJson AS capture,orderRequestJson AS body,q15ResultJson AS result,q15Reason AS reason,lastError AS error,NULL AS code,
 q15ReviewedAt AS reviewedAt,q15ReviewedBy AS reviewedBy,q15ReviewRole AS reviewRole,cashTenderedCents,changeCents,splitType,customerId,
 CASE WHEN syncStatus='FAILED' AND (q15Reason IS NULL OR q15ReviewedAt IS NOT NULL) THEN 1 ELSE 0 END AS failedGroup,
 CASE WHEN syncStatus='EN_VUELO' AND (q15Reason IS NULL OR q15ReviewedAt IS NOT NULL) THEN 1 ELSE 0 END AS interruptedGroup,
 CASE WHEN q15Reason IS NOT NULL AND q15ReviewedAt IS NULL THEN 1 ELSE 0 END AS q15Group,0 AS rejectedGroup
 FROM pending_payments WHERE venueId=?"""
    private val intent = """SELECT venue_id AS venueId,'INTENT' AS origin,id,created_at AS createdAt,type,NULL AS method,
 NULL AS amountCents,NULL AS tipCents,NULL AS tenderTypeId,NULL AS tenderRevision,capturedOrderRef AS reference,NULL AS orderId,
 status AS state,q15CaptureJson AS capture,payload_json AS body,
 q15ResultJson AS result,q15Reason AS reason,message AS error,error_code AS code,q15ReviewedAt AS reviewedAt,
 q15ReviewedBy AS reviewedBy,q15ReviewRole AS reviewRole,NULL AS cashTenderedCents,NULL AS changeCents,NULL AS splitType,NULL AS customerId,
 0 AS failedGroup,0 AS interruptedGroup,CASE WHEN q15Reason IS NOT NULL AND q15ReviewedAt IS NULL THEN 1 ELSE 0 END AS q15Group,
 CASE WHEN status='REJECTED' AND q15Reason IS NULL THEN 1 ELSE 0 END AS rejectedGroup
 FROM pos_sync_intents WHERE venue_id=? AND type='PAY_CASH'"""
    private val join = "LEFT JOIN sales_notice_acknowledgements a ON a.venueId=s.venueId AND a.origin=s.origin AND a.id=s.id AND " +
        SALES_NOTICE_VERSION_COLUMNS.joinToString(" AND ") {
            if(it=="state") "a.state IS CASE WHEN s.state IN ('SYNCED','ACKED') THEN 'APPLIED' ELSE 'UNRESOLVED' END" else "a.$it IS s.$it"
        }
    private fun active(reserved: Set<String>): Pair<String,List<Any?>> {
        val reserve = if (reserved.isEmpty()) "1" else "s.id NOT IN (${reserved.joinToString(",") { "?" }})"
        return "(s.state IN ('PENDING','SYNCING','FAILED','REJECTED','HELD') OR (s.state='EN_VUELO' AND ($reserve OR s.q15Group=1)) OR s.q15Group=1)" to reserved.toList()
    }
    fun query(venue: String, query: String, reserved: Set<String>, cursor: SalesNoticeCursor? = null,
        count: Boolean = false, key: SalesNoticeKey? = null, candidates: Boolean = false, aggregate: Boolean = false): RoomRawQuery {
        val (active, reserves) = active(reserved)
        val args = mutableListOf<Any?>()
        if(aggregate) args.addAll(listOf(venue,venue))
        args.addAll(listOf(venue,venue));args.addAll(reserves)
        var where = "$active AND (?='' OR instr(lower(s.id),lower(?))>0 OR instr(lower(COALESCE(s.reference,'')),lower(?))>0 OR instr(lower(COALESCE(s.capture,'')),lower(?))>0 OR instr(lower(COALESCE(s.body,'')),lower(?))>0 OR instr(lower(COALESCE(s.error,'')),lower(?))>0 OR instr(lower(COALESCE(s.reason,'')),lower(?))>0)"
        repeat(7) { args.add(query) }
        if (candidates) where += " AND s.origin='PAYMENT' AND s.reason='CANCELLED_ZERO_PAYMENT' AND s.reviewedAt IS NOT NULL"
        if (key != null) { where += " AND s.origin=? AND s.id=?";args.add(key.origin.name);args.add(key.id) }
        if (cursor != null) {
            where += " AND (s.createdAt<? OR (s.createdAt=? AND (s.origin>? OR (s.origin=? AND s.id>?))))"
            args.addAll(listOf(cursor.createdAt,cursor.createdAt,cursor.origin.name,cursor.origin.name,cursor.id))
        }
        val select = if(aggregate) {
            "COUNT(*) AS total,COALESCE(SUM(CASE WHEN a.id IS NULL AND s.failedGroup+s.interruptedGroup+s.q15Group+s.rejectedGroup>0 THEN 1 ELSE 0 END),0) AS unseenCount," +
            listOf("failedGroup","interruptedGroup","q15Group","rejectedGroup").joinToString(",") { "COALESCE(SUM(s.$it),0) AS $it,COALESCE(SUM(CASE WHEN a.id IS NOT NULL THEN s.$it ELSE 0 END),0) AS ack$it" } +
            ", (SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id=? AND type!='PAY_CASH' AND q15Reason IS NOT NULL AND q15ReviewedAt IS NULL) AS untouchedQ15" +
            ", (SELECT COUNT(*) FROM pos_sync_intents WHERE venue_id=? AND type!='PAY_CASH' AND status='REJECTED' AND q15Reason IS NULL) AS untouchedRejected"
        } else if(count) "COUNT(*)" else "s.*,a.actor AS acknowledgedActor,a.acknowledgedAt AS acknowledgedAt"
        val sql = "SELECT $select FROM ($payment UNION ALL $intent) s $join WHERE $where" +
            (if(count || aggregate) "" else " ORDER BY s.createdAt DESC,s.origin ASC,s.id ASC LIMIT 50")
        return RoomRawQuery(sql) { statement -> args.forEachIndexed { index, value -> when(value) {
            null -> statement.bindNull(index+1)
            is Number -> statement.bindLong(index+1,value.toLong())
            else -> statement.bindText(index+1,value.toString())
        } } }
    }
}

data class SalesNoticeAggregate(val total: Int=0,val unseenCount: Int=0,val failedGroup: Int=0,val interruptedGroup: Int=0,
    val q15Group: Int=0,val rejectedGroup: Int=0,val ackfailedGroup: Int=0,val ackinterruptedGroup: Int=0,
    val ackq15Group: Int=0,val ackrejectedGroup: Int=0,val untouchedQ15: Int=0,val untouchedRejected: Int=0)

@Dao
abstract class SalesNoticeDao {
    @RawQuery abstract suspend fun read(query: RoomRawQuery): List<SalesNoticeSnapshot>
    @RawQuery abstract suspend fun count(query: RoomRawQuery): Int
    @RawQuery abstract suspend fun aggregate(query: RoomRawQuery): SalesNoticeAggregate
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun save(row: SalesNoticeAcknowledgementEntity)
    @Query("SELECT (SELECT COUNT(*) FROM pending_payments)+(SELECT COUNT(*) FROM pos_sync_intents)+(SELECT COUNT(*) FROM sales_notice_acknowledgements)")
    abstract fun changes(): Flow<Int>

    @Transaction
    open suspend fun page(venueId: String, query: String, cursor: SalesNoticeCursor?, reserved: Set<String>): SalesNoticePage {
        val total = count(SalesNoticeSql.query(venueId,query,reserved,count=true)) - cancelled(venueId,query,reserved).total
        val rows = mutableListOf<SalesNoticeSnapshot>(); var after = cursor; var exhausted = false
        while(rows.size < 50 && !exhausted) {
            val batch = read(SalesNoticeSql.query(venueId,query,reserved,after))
            if(batch.isEmpty()) { exhausted=true;break }
            for(row in batch) { after=row.cursor; if(!row.cancelledReviewed()) rows.add(row);if(rows.size==50) break }
            exhausted = batch.size < 50 && after == batch.last().cursor
        }
        return SalesNoticePage(rows,total,if(exhausted || rows.isEmpty()) null else after)
    }
    private suspend fun cancelled(venue: String,query: String,reserved: Set<String>): SalesNoticeAggregate {
        var result=SalesNoticeAggregate();var cursor: SalesNoticeCursor?=null
        do { val batch=read(SalesNoticeSql.query(venue,query,reserved,cursor,candidates=true))
            for(row in batch) if(row.cancelledReviewed()) {
                val ack=row.acknowledgement != null
                result=result.copy(total=result.total+1,
                    unseenCount=result.unseenCount+if(!ack && row.failedGroup+row.interruptedGroup+row.q15Group+row.rejectedGroup>0)1 else 0,
                    failedGroup=result.failedGroup+row.failedGroup,interruptedGroup=result.interruptedGroup+row.interruptedGroup,
                    q15Group=result.q15Group+row.q15Group,rejectedGroup=result.rejectedGroup+row.rejectedGroup,
                    ackfailedGroup=result.ackfailedGroup+if(ack)row.failedGroup else 0,
                    ackinterruptedGroup=result.ackinterruptedGroup+if(ack)row.interruptedGroup else 0,
                    ackq15Group=result.ackq15Group+if(ack)row.q15Group else 0,
                    ackrejectedGroup=result.ackrejectedGroup+if(ack)row.rejectedGroup else 0)
            }
            if(batch.size<50)break;cursor=batch.last().cursor
        } while(true)
        return result
    }
    @Transaction
    open suspend fun summary(venueId: String,reserved: Set<String>): SalesNoticeSummary {
        val raw=aggregate(SalesNoticeSql.query(venueId,"",reserved,aggregate=true))
        val removed=cancelled(venueId,"",reserved)
        return SalesNoticeSummary(raw.total-removed.total,raw.unseenCount-removed.unseenCount,
            mapOf("FAILED" to raw.failedGroup-removed.failedGroup,"INTERRUPTED" to raw.interruptedGroup-removed.interruptedGroup,
                "Q15" to raw.q15Group-removed.q15Group,"REJECTED" to raw.rejectedGroup-removed.rejectedGroup),
            mapOf("FAILED" to raw.ackfailedGroup-removed.ackfailedGroup,"INTERRUPTED" to raw.ackinterruptedGroup-removed.ackinterruptedGroup,
                "Q15" to raw.ackq15Group-removed.ackq15Group,"REJECTED" to raw.ackrejectedGroup-removed.ackrejectedGroup),
            raw.untouchedQ15,raw.untouchedRejected)
    }
    @Transaction
    open suspend fun acknowledge(snapshot: SalesNoticeSnapshot,actor: String,now: Long,reserved: Set<String>): Boolean {
        if(actor.isBlank() || now<=0 || !snapshot.eligible()) return false
        val current=read(SalesNoticeSql.query(snapshot.venueId,"",reserved,key=snapshot.key)).singleOrNull() ?: return false
        if(current.version != snapshot.version || !current.eligible() || current.cancelledReviewed()) return false
        if(current.acknowledgement != null) return true
        val v=current.version
        save(SalesNoticeAcknowledgementEntity(current.venueId,current.origin,current.id,actor,now,v.capture,v.body,v.result,v.reason,
            v.error,v.code,v.type,v.method,v.amountCents,v.tipCents,v.tenderTypeId,v.tenderRevision,v.reference,v.orderId,v.state))
        return true
    }

}

@Singleton
class SalesNoticeStore @Inject constructor(private val dao: SalesNoticeDao,
    private val cash: com.avoqado.pos.payment.data.CashPaymentRepository) {
    suspend fun page(venueId:String,query:String="",cursor:SalesNoticeCursor?=null)=dao.page(venueId,query,cursor,cash.salesNoticeReservedIds())
    suspend fun summary(venueId:String)=dao.summary(venueId,cash.salesNoticeReservedIds())
    suspend fun acknowledge(snapshot:SalesNoticeSnapshot,actor:String,now:Long)=dao.acknowledge(snapshot,actor,now,cash.salesNoticeReservedIds())
    fun changes():Flow<Int> = flow {
        var revision=0
        dao.changes().collect { emit(++revision) }
    }
}
