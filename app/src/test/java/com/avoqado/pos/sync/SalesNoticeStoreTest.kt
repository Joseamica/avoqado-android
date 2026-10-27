package com.avoqado.pos.sync

import com.avoqado.pos.sync.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SalesNoticeStoreTest {
    @Test fun `same id in another venue or origin never shares a recognition and live reservation stays out`()=runBlocking {
        SalesNoticeSqlTest.Fixture().use {f->
            f.payment("same");f.intent("same");f.payment("other-venue","venue-B")
            f.payment("live",changes=mapOf("syncStatus" to "EN_VUELO"))
            val before=f.economic();val rows=f.dao.page("venue-A","",null,setOf("live")).rows
            assertEquals(2,rows.size)
            assertTrue(f.write {f.dao.acknowledge(rows.single {it.origin=="PAYMENT"},"manager-A",1,setOf("live"))})
            val reload=f.dao.page("venue-A","",null,setOf("live"))
            assertNotNull(reload.rows.single {it.origin=="PAYMENT"}.acknowledgement)
            assertNull(reload.rows.single {it.origin=="INTENT"}.acknowledgement)
            assertNull(f.dao.page("venue-B","",null,emptySet()).rows.single().acknowledgement)
            val summary=f.dao.summary("venue-A",setOf("live"))
            assertEquals(2,summary.pendingCount);assertEquals(1,summary.unseenCount)
            assertEquals(before,f.economic())
        }
    }
}
