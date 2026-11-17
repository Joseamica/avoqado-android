package com.avoqado.pos.sync

import com.avoqado.pos.sync.data.*
import com.avoqado.pos.sync.presentation.salesNoticeBannerCount
import com.avoqado.pos.sync.presentation.salesNoticeBannerApplied
import com.avoqado.pos.sync.presentation.textoDeLaBandaDeRevision
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
    @Test fun D68_la_banda_dice_aparte_los_avisos_de_pagos_ya_registrados_que_no_frenan()=runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            // Un cobro sin confirmar (frena) y dos «Pago registrado · revisa las diferencias» (SYNCED: ya están en el servidor).
            f.payment("sin-confirmar")
            val resultado="""{"paymentId":"p","orderId":"o","amountCents":100,"tipCents":0,"camposDiferentes":["tipCents"],"camposNoComprobados":[]}"""
            for(id in listOf("registrado-1","registrado-2")) f.payment(id,changes=mapOf("syncStatus" to "SYNCED",
                "q15Reason" to com.avoqado.pos.payment.data.ConciliacionDelCobro.DIFERENCIA,"q15ResultJson" to resultado))
            val summary=f.dao.summary("venue-A",emptySet())
            assertEquals(3,salesNoticeBannerCount(summary,0,99))
            assertEquals(2,salesNoticeBannerApplied(summary,0))
            assertEquals("1 operación necesita revisión · 2 avisos de pagos ya registrados",
                textoDeLaBandaDeRevision(salesNoticeBannerCount(summary,0,99),salesNoticeBannerApplied(summary,0)))
            // «Entendido» en uno de los registrados lo quita de los dos conteos.
            val registrado=f.dao.page("venue-A","",null,emptySet()).rows.first { it.id=="registrado-1" }
            f.write { f.dao.acknowledge(registrado,"manager",1,emptySet()) }
            val despues=f.dao.summary("venue-A",emptySet())
            assertEquals(2,salesNoticeBannerCount(despues,0,99));assertEquals(1,salesNoticeBannerApplied(despues,0))
        }
    }
    @Test fun D68_textos_de_la_banda() {
        assertEquals("1 operación necesita revisión",textoDeLaBandaDeRevision(1,0))
        assertEquals("3 operaciones necesitan revisión",textoDeLaBandaDeRevision(3,0))
        assertEquals("1 aviso de un pago ya registrado",textoDeLaBandaDeRevision(1,1))
        assertEquals("2 avisos de pagos ya registrados",textoDeLaBandaDeRevision(2,2))
        assertEquals("2 operaciones necesitan revisión · 1 aviso de un pago ya registrado",textoDeLaBandaDeRevision(3,1))
        assertEquals("Historial de cobros",textoDeLaBandaDeRevision(0,0))
        assertEquals(0,salesNoticeBannerApplied(null,0))
    }
    @Test fun acknowledged_cash_never_subtracts_card_reservations_or_Q16_even_with_matching_id() {
        val summary=SalesNoticeSummary(3,2,mapOf("FAILED" to 2,"Q15" to 1),mapOf("FAILED" to 1),untouchedQ15=1,untouchedRejected=2)
        assertEquals(8,salesNoticeBannerCount(summary,3,99))
        assertEquals(9,salesNoticeBannerCount(null,3,9))
    }
}
