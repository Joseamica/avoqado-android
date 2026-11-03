package com.avoqado.pos.sync

import androidx.sqlite.db.SupportSQLiteDatabase
import com.avoqado.pos.core.data.local.database.AvoqadoDatabaseMigrations
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.sync.data.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SalesNoticeLifecycleTest {
    @Test fun `production migration 16 to 17 preserves eight complete source rows and creates zero recognitions`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.sql("DROP TABLE sales_notice_acknowledgements")
            repeat(8){f.payment("folio-$it",changes=mapOf("syncStatus" to listOf("PENDING","FAILED","EN_VUELO","SYNCING")[it%4],"lastError" to "original $it"))}
            val before=f.economic();val db=mockk<SupportSQLiteDatabase>()
            every {db.execSQL(any<String>())} answers {f.sql(firstArg())}
            AvoqadoDatabaseMigrations.MIGRATION_16_17.migrate(db)
            assertEquals(before,f.economic())
            assertEquals(0,f.db.createStatement().use {s->s.executeQuery("SELECT COUNT(*) FROM sales_notice_acknowledgements").use{it.next();it.getInt(1)}})
        }
    }
    @Test fun `producer result repeats identically without clock while new result requires notice`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            val capture=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("venue-A","cash",amountCents=100,tipCents=0,method="CASH"))
            fun produced(amount:Int)=ConciliacionDelCobro.evaluar(capture,"venue-A","cash",CanalDelCobro.ORDER,
                """{"success":true,"payment":{"paymentId":"server-payment","orderId":"order-A","status":"COMPLETED","amount":$amount,"tipAmount":0,"method":"CASH","createdAt":"ignored"}}""")
            val first=produced(99);val retry=produced(99)
            assertEquals(first.resultadoJson,retry.resultadoJson)
            f.payment(changes=mapOf("q15CaptureJson" to capture,"q15Reason" to first.motivo,"q15ResultJson" to first.resultadoJson))
            val before=f.economic();val row=f.dao.page("venue-A","",null,emptySet()).rows.single()
            assertTrue(f.write {f.dao.acknowledge(row,"manager-A",1,emptySet())});assertEquals(before,f.economic())
            f.sql("UPDATE pending_payments SET retryCount=2,lastRetryAt=200")
            assertNotNull(f.dao.page("venue-A","",null,emptySet()).rows.single().acknowledgement)
            f.db.prepareStatement("UPDATE pending_payments SET q15ResultJson=?").use {it.setString(1,produced(98).resultadoJson);it.executeUpdate()}
            assertNull(f.dao.page("venue-A","",null,emptySet()).rows.single().acknowledgement)
            val after=f.economic();assertFalse(f.write {f.dao.acknowledge(row,"manager-B",2,emptySet())});assertEquals(after,f.economic())
        }
    }
    @Test fun `recognitions survive database reopen and actor switch without hiding second folio`()=runBlocking {
        val file=java.nio.file.Files.createTempFile("sales-notice-", ".sqlite").toFile()
        try {
            var before:List<List<Any?>> = emptyList()
            SalesNoticeSqlTest.Fixture(file.absolutePath).use {f->
                f.payment("first");f.payment("second");before=f.economic()
                val first=f.dao.page("venue-A","first",null,emptySet()).rows.single()
                assertTrue(f.write {f.dao.acknowledge(first,"manager-A",1,emptySet())})
                assertEquals(before,f.economic())
            }
            SalesNoticeSqlTest.Fixture(file.absolutePath).use {f->
                assertEquals("manager-A",f.dao.page("venue-A","first",null,emptySet()).rows.single().acknowledgement?.actor)
                assertNull(f.dao.page("venue-A","second",null,emptySet()).rows.single().acknowledgement)
                assertEquals(1,f.dao.summary("venue-A",emptySet()).unseenCount)
                val first=f.dao.page("venue-A","first",null,emptySet()).rows.single()
                assertTrue(f.write {f.dao.acknowledge(first,"manager-B",2,emptySet())})
                assertEquals(before,f.economic())
            }
        } finally { file.delete() }
    }
    @Test fun `fifty five valid cancellation candidates never starve a page or hide invalid proof`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            val proof="""{"cancelledPaymentEvidence":{"version":1,"venueId":"venue-A","orderId":"order-A","externalId":"original","orderStatus":"CANCELLED","hasPayments":false,"hasTerminalRequests":false,"verifiedAt":"2026-10-23T20:00:00.000Z"}}"""
            val original="""{"externalId":"original","total":0,"tip":0}"""
            repeat(55){f.payment("cancelled-$it",changes=mapOf("paymentType" to "ORDER","amountCents" to 0,"orderId" to "order-A","orderRequestJson" to original,
                "q15Reason" to "CANCELLED_ZERO_PAYMENT","q15ReviewedAt" to 10L,"q15ReviewedBy" to "manager","q15ReviewRole" to "MANAGER","q15ResultJson" to proof))}
            f.payment("invalid",changes=mapOf("paymentType" to "ORDER","amountCents" to 0,"orderId" to "order-A","orderRequestJson" to original,
                "q15Reason" to "CANCELLED_ZERO_PAYMENT","q15ReviewedAt" to 10L,"q15ReviewedBy" to "manager","q15ReviewRole" to "MANAGER","q15ResultJson" to "{}"))
            repeat(51){f.payment("visible-$it",changes=mapOf("createdAt" to 500L))}
            val before=f.economic();val first=f.dao.page("venue-A","",null,emptySet());assertEquals(52,first.total);assertEquals(50,first.rows.size)
            val second=f.dao.page("venue-A","",first.nextCursor,emptySet());assertEquals(2,second.rows.size)
            assertEquals(52,(first.rows+second.rows).map{it.key}.toSet().size);assertTrue(first.rows.any{it.id=="invalid"})
            assertEquals(52,f.dao.summary("venue-A",emptySet()).pendingCount);assertEquals(before,f.economic())
        }
    }
    @Test fun `matching recognition discounts only its current group and leaves Q16 and reservation contributions untouched`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment("failed");f.payment("q15",changes=mapOf("q15Reason" to "DIFERENCIA_DE_COBRO"));f.intent("rejected")
            f.intent("q16",changes=mapOf("type" to "ADD_ITEMS","q15Reason" to "PRICE_PROOF_PENDING"))
            f.sql("INSERT INTO pending_reservation_action (reservationId,action,attemptCount,createdAt) VALUES ('reservation','CREATE',5,1000)")
            val before=f.economic();val rows=f.dao.page("venue-A","",null,emptySet()).rows
            assertEquals(3,rows.size)
            assertTrue(f.write {f.dao.acknowledge(rows.single {it.id=="q15"},"manager",1,emptySet())})
            val summary=f.dao.summary("venue-A",emptySet())
            assertEquals(mapOf("FAILED" to 1,"INTERRUPTED" to 0,"Q15" to 1,"REJECTED" to 1),summary.alertContributions)
            assertEquals(mapOf("FAILED" to 0,"INTERRUPTED" to 0,"Q15" to 1,"REJECTED" to 0),summary.acknowledgedContributions)
            assertEquals(before,f.economic())
            assertEquals(1,f.db.createStatement().use {s->s.executeQuery("SELECT COUNT(*) FROM pending_reservation_action WHERE attemptCount>=5").use{it.next();it.getInt(1)}})
        }
    }
    @Test fun `opaque result or nested card evidence cannot be acknowledged as native cash`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment("opaque",changes=mapOf("q15ResultJson" to """{"unknown":"not a producer format"}"""))
            f.payment("nested-card",changes=mapOf("q15ResultJson" to """{"method":"CASH","payment":{"cardBrand":"VISA"}}"""))
            val before=f.economic()
            for(row in f.dao.page("venue-A","",null,emptySet()).rows)assertFalse(f.write {f.dao.acknowledge(row,"manager",1,emptySet())})
            assertEquals(before,f.economic());assertEquals(2,f.dao.summary("venue-A",emptySet()).unseenCount)
        }
    }

}
