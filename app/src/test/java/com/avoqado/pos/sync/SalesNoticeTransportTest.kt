package com.avoqado.pos.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SalesNoticeTransportTest {
    @Test fun `SYNCING stays reachable in pending page with its actual stored state`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment(changes=mapOf("syncStatus" to "SYNCING"));val before=f.economic()
            val page=f.dao.page("venue-A","",null,emptySet());assertEquals(1,page.total);assertEquals("SYNCING",page.rows.single().state)
            assertEquals(before,f.economic())
        }
    }
    @Test fun `same facts do not reannounce during failed pending syncing failed transport cycle`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment(changes=mapOf("q15Reason" to "DIFERENCIA_DE_COBRO","lastError" to "original cause"))
            val original=f.dao.page("venue-A","",null,emptySet()).rows.single();val before=f.economic()
            assertTrue(f.write {f.dao.acknowledge(original,"manager",1,emptySet())});assertEquals(before,f.economic())
            for(state in listOf("PENDING","SYNCING","FAILED")) {
                f.sql("UPDATE pending_payments SET syncStatus='$state'")
                val phase=f.economic();assertNotNull(state,f.dao.page("venue-A","",null,emptySet()).rows.single().acknowledgement)
                assertEquals(0,f.dao.summary("venue-A",emptySet()).unseenCount);assertEquals(phase,f.economic())
            }
        }
    }
    @Test fun `untouched Q16 belongs to same snapshot and survives recognizing or changing money`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.intent("cash",changes=mapOf("q15Reason" to "DIFERENCIA_DE_COBRO"))
            f.intent("Q16",changes=mapOf("type" to "ADD_ITEMS","q15Reason" to "PRICE_PROOF_PENDING"))
            val before=f.economic();val row=f.dao.page("venue-A","",null,emptySet()).rows.single()
            assertEquals(1,f.dao.summary("venue-A",emptySet()).untouchedQ15)
            assertTrue(f.write {f.dao.acknowledge(row,"manager",1,emptySet())});assertEquals(before,f.economic())
            var summary=f.dao.summary("venue-A",emptySet());assertEquals(0,summary.unseenCount);assertEquals(1,summary.untouchedQ15)
            f.sql("UPDATE pos_sync_intents SET status='ACKED' WHERE id='cash'")
            summary=f.dao.summary("venue-A",emptySet());assertEquals(1,summary.unseenCount);assertEquals(1,summary.untouchedQ15)
            f.sql("UPDATE pos_sync_intents SET q15ReviewedAt=10 WHERE id='cash'")
            val after=f.economic();summary=f.dao.summary("venue-A",emptySet());assertEquals(0,summary.unseenCount);assertEquals(1,summary.untouchedQ15)
            assertEquals(after,f.economic())
        }
    }
    @Test fun `PRICE proof notice remains unrecognizable even with valid CASH capture`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment(changes=mapOf("q15Reason" to "PRICE_PROOF_PENDING"));val before=f.economic()
            val row=f.dao.page("venue-A","",null,emptySet()).rows.single();assertFalse(row.eligible())
            assertFalse(f.write {f.dao.acknowledge(row,"manager",1,emptySet())})
            assertEquals(before,f.economic())
        }
    }
    @Test fun `snapshot with forged venue cannot recognize another venue or change source rows`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment("cash","venue-A");f.payment("other","venue-B");val before=f.economic()
            val original=f.dao.page("venue-A","",null,emptySet()).rows.single()
            assertFalse(f.write {f.dao.acknowledge(original.copy(venueId="venue-B"),"manager",1,emptySet())})
            assertNull(f.dao.page("venue-B","",null,emptySet()).rows.single().acknowledgement)
            assertEquals(before,f.economic())
        }
    }

}
