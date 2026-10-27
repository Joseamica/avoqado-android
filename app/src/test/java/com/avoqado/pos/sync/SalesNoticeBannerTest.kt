package com.avoqado.pos.sync

import com.avoqado.pos.sync.data.*
import com.avoqado.pos.sync.presentation.salesNoticeBannerCount
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class SalesNoticeBannerTest {
    @Test fun understanding_two_incidents_only_reduces_their_notice_and_keeps_sources_and_pending_badge()=runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            f.payment("one");f.payment("two");val economic=f.economic()
            val rows=f.dao.page("venue-A","",null,emptySet()).rows
            suspend fun count()=salesNoticeBannerCount(f.dao.summary("venue-A",emptySet()),0,99)
            assertEquals(2,count())
            f.write { f.dao.acknowledge(rows[0],"manager",1,emptySet()) };assertEquals(1,count())
            f.write { f.dao.acknowledge(rows[1],"manager",2,emptySet()) };assertEquals(0,count())
            assertEquals(2,f.dao.summary("venue-A",emptySet()).pendingCount);assertEquals(economic,f.economic())
            f.sql("UPDATE pending_payments SET lastError='new cause' WHERE id='one'")
            assertEquals(1,count())
        }
    }
    @Test fun acknowledged_cash_never_subtracts_card_reservations_or_Q16_even_with_matching_id() {
        val summary=SalesNoticeSummary(3,2,mapOf("FAILED" to 2,"Q15" to 1),mapOf("FAILED" to 1),untouchedQ15=1,untouchedRejected=2)
        assertEquals(8,salesNoticeBannerCount(summary,3,99))
        assertEquals(9,salesNoticeBannerCount(null,3,9))
    }
}
