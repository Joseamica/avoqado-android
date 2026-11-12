package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.*
import com.avoqado.pos.sync.SalesNoticeSqlTest
import com.avoqado.pos.sync.data.SalesNoticeSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import com.avoqado.pos.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.test.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel

/** Existing evaluator -> existing read projection -> existing acknowledgement writer; independent of the new GET. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Q15RecoveryNoticeTest {
    @get:Rule val main=MainDispatcherRule()
    @Test fun recovered_ORDER_D1_result_can_be_acknowledged_without_erasing_payment_history() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            val row=seed(f); val before=f.economic()
            assertTrue("D1 balance and order status are valid normalized payment evidence",row.eligible())
            assertTrue(f.write { f.dao.acknowledge(row,"cashier",123,emptySet()) })
            val reread=f.dao.page(VENUE,"",null,emptySet()).rows.single()
            assertNotNull(reread.acknowledgement); assertEquals(before,f.economic())
            assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,reread.reason)
            assertEquals(row.capture,reread.capture); assertEquals(row.result,reread.result)
        }
    }

    @Test fun remote_tender_metadata_on_a_synced_ORDER_does_not_turn_the_original_into_a_local_tender() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            val row=seed(f,remoteTender=true); val before=f.economic()
            assertNull(row.tenderTypeId); assertNull(row.tenderRevision)
            assertTrue(row.eligible())
            assertTrue(f.write { f.dao.acknowledge(row,"cashier",123,emptySet()) })
            assertEquals(before,f.economic())
            assertEquals("channel-tender",JSONObject(row.result!!).getString("tenderTypeId"))
        }
    }

    @Test fun tender_exception_never_applies_to_local_capture_row_body_or_INTENT() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            val row=seed(f,remoteTender=true)
            val bad=listOf(row.copy(origin="INTENT",type="PAY_CASH"),row.copy(type="FAST"),row.copy(state="PENDING"),
                row.copy(tenderTypeId="local-tender",tenderRevision=1),row.copy(method="CARD"),
                row.copy(capture=ConciliacionDelCobro.guardarCaptura(capture().copy(method="CARD"))),
                row.copy(result=JSONObject(row.result!!).put("cardBrand","VISA").toString()),
                row.copy(result=JSONObject(row.result!!).put("tenderRevision",0).toString()),
                row.copy(result=JSONObject(row.result!!).put("tenderTypeId"," ").toString()))
            for(invalid in bad) assertFalse(invalid.toString(),invalid.eligible())
        }
    }

    @Test fun D1_fields_require_bounded_scalar_values_and_cannot_hide_opaque_financial_payloads() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            val row=seed(f)
            val bad:List<(JSONObject)->Unit> = listOf(
                {it.put("saldoCents",true)}, {it.put("saldoCents",0.5)}, {it.put("saldoCents",-1)},
                {it.put("saldoCents",2147483648L)}, {it.put("estadoDeLaOrden","UNKNOWN")},
                {it.put("processorData",JSONObject().put("opaque",true))},
                {it.put("tenderRevision",1)}, {it.put("method","CARD")})
            for(change in bad) assertFalse(row.copy(result=JSONObject(row.result!!).apply { change(this) }.toString()).eligible())
        }
    }

    @Test fun registered_copy_separates_known_components_and_never_claims_remote_retained_cash() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            val row=seed(f,remoteTender=true)
            assertEquals("Pago registrado",com.avoqado.pos.sync.presentation.salesNoticeStatus(row))
            assertEquals("El pago ya está registrado. Puedes marcar el aviso como entendido; sus datos se conservan.",com.avoqado.pos.sync.presentation.salesNoticeReason(row))
            assertEquals("Aviso entendido · datos conservados",com.avoqado.pos.sync.presentation.salesNoticeAcknowledged(row))
            val money=com.avoqado.pos.sync.presentation.salesNoticeServerMoney(row)
            assertTrue(money.startsWith("Importe registrado:")); assertTrue(money.contains("propina:"))
            assertFalse(money.contains("Rappi")); assertFalse(money.contains("retenido"))
            assertFalse(JSONObject(row.result!!).has("cashRetainedCents")); assertFalse(JSONObject(row.result!!).has("deliveryCashAllocation"))
            assertTrue(com.avoqado.pos.sync.presentation.salesNoticeResultWarnings(row).isEmpty())
            val unresolved=row.copy(state="PENDING",result=null)
            assertEquals("Sin confirmar",com.avoqado.pos.sync.presentation.salesNoticeStatus(unresolved))
            assertEquals("Aviso entendido · sigue pendiente",com.avoqado.pos.sync.presentation.salesNoticeAcknowledged(unresolved))
        }
    }

    @Test fun registered_copy_keeps_real_differences_and_unchecked_fields_visible() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            val row=seed(f)
            val differences=row.copy(result=JSONObject(row.result!!).put("camposDiferentes",org.json.JSONArray().put("amountCents"))
                .put("camposNoComprobados",org.json.JSONArray().put("splitType")).toString())
            assertEquals("Pago registrado · revisa las diferencias",com.avoqado.pos.sync.presentation.salesNoticeStatus(differences))
            assertEquals(listOf("Campos distintos: importe","Sin confirmar: reparto"),com.avoqado.pos.sync.presentation.salesNoticeResultWarnings(differences))
            assertFalse(com.avoqado.pos.sync.presentation.salesNoticeReason(differences).contains("cuadra"))
            assertEquals(row.reason,differences.reason); assertTrue(differences.eligible())
        }
    }

    @Test fun acknowledged_recovered_payment_message_says_data_kept_and_does_not_call_it_pending() = runTest {
        SalesNoticeSqlTest.Fixture().use { f ->
            seed(f)
            val storage=mockk<com.avoqado.pos.core.data.local.SecureStorage>(relaxed=true)
            every {storage.venueId} returns VENUE; every {storage.userId} returns "manager"
            every {storage.userRole} returns "MANAGER"
            val cash=CashPaymentRepository(storage,mockk(relaxed=true))
            val store=com.avoqado.pos.sync.data.SalesNoticeStore(f.dao,cash)
            val session=mockk<com.avoqado.pos.tables.data.TableSession>(relaxed=true)
            val network=mockk<com.avoqado.pos.core.util.ConnectivityMonitor>(relaxed=true)
            every {network.isConnected} returns kotlinx.coroutines.flow.MutableStateFlow(false)
            every {network.isServerReachable} returns kotlinx.coroutines.flow.MutableStateFlow(false)
            val vm=com.avoqado.pos.sync.presentation.SalesNoticesViewModel(store,storage,
                com.avoqado.pos.core.domain.RoleManager(storage),session,mockk(relaxed=true),network)
            try {
                vm.refresh(); runCurrent(); vm.select(vm.rows.value.single())
                val before=f.economic(); vm.acknowledgeSelected(); runCurrent()
                assertEquals("Aviso entendido. Los datos del pago se conservan en Ventas.",vm.message.value)
                assertEquals(before,f.economic()); assertEquals(0,vm.summary.value!!.unseenCount)
                assertNull(vm.rows.value.single().reviewedAt)
            } finally {vm.viewModelScope.cancel()}
        }
    }

    /** D5 · B3 (5b y C3): el efectivo de una MESA cobrada sin red (cola de mesas, ACKED) le dice al gerente lo que trajo el acuse REAL. */
    @Test fun mesa_sin_red_ACKED_shows_the_manager_the_overcharge_and_the_owed_balance() = runBlocking {
        SalesNoticeSqlTest.Fixture().use { f ->
            // 5b (D3, M6): esperaba dejarla en $0 con $45; la N86 ya había cobrado $25: el servidor queda con $70 pagados de $45.
            val sobra=mesaAplicada(f,"cobro-5b",4500,remaining=0,total=4500,paid=7000)
            assertEquals("Pago registrado · revisa las diferencias",com.avoqado.pos.sync.presentation.salesNoticeStatus(sobra))
            assertEquals("Importe registrado: \$45.00 · propina: \$0.00",com.avoqado.pos.sync.presentation.salesNoticeServerMoney(sobra))
            assertEquals(listOf("Campos distintos: saldo de la mesa","Saldo esperado \$0.00 · saldo en el servidor \$0.00","Se cobró de más: \$25.00"),
                com.avoqado.pos.sync.presentation.salesNoticeResultWarnings(sobra))
            // C3 (N86, M6): esperaba dejarla en $0 con $25 y el servidor ya tenía la ronda de $20 de la D3: queda debiendo $20.
            val debe=mesaAplicada(f,"cobro-c3",2500,remaining=2000,total=4500,paid=2500)
            assertEquals(listOf("Campos distintos: saldo de la mesa","Saldo esperado \$0.00 · saldo en el servidor \$20.00"),
                com.avoqado.pos.sync.presentation.salesNoticeResultWarnings(debe))
            // Sin acuse (sigue en la cola), igual que antes: sin confirmar.
            assertEquals("Sin confirmar",com.avoqado.pos.sync.presentation.salesNoticeStatus(debe.copy(state="PENDING")))
        }
    }

    private suspend fun mesaAplicada(f:SalesNoticeSqlTest.Fixture,id:String,amount:Int,remaining:Int,total:Int,paid:Int):SalesNoticeSnapshot {
        val payload="""{"orderId":"order-M6","amountCents":$amount,"tipCents":0,"expectedBalanceBeforeCents":$amount}"""
        val c=ConciliacionDelCobro.capturarIntent(VENUE,id,payload,restanteEsperadoCents=0)
        // El acuse del SERVIDOR (`PAY_CASH`), con sus campos en centavos.
        val ack=JSONObject().put("orderId","order-M6").put("paymentId","pay-$id").put("orderNumber","ORD-1794488493066")
            .put("amount",amount).put("tipAmount",0).put("changeCents",0).put("cashRetainedCents",amount).put("method","CASH")
            .put("remainingBalanceCents",remaining).put("orderPaymentStatus",if(remaining==0)"PAID" else "PARTIAL")
            .put("orderTotalCents",total).put("totalPaidCents",paid).toString()
        val e=ConciliacionDelCobro.evaluar(c,VENUE,id,CanalDelCobro.ACK,ack)
        check(e.aplicado && e.motivo==ConciliacionDelCobro.DIFERENCIA) { e.toString() }
        f.intent(id,VENUE,mapOf("payload_json" to payload,"status" to "ACKED","q15CaptureJson" to c,"result_json" to ack,
            "q15InitialResultJson" to e.resultadoJson,"q15ResultJson" to e.resultadoJson,"q15Reason" to e.motivo,"q15DetectedAt" to 102L))
        return f.dao.page(VENUE,"",null,emptySet()).rows.single { it.id==id }
    }

    private suspend fun seed(f:SalesNoticeSqlTest.Fixture,remoteTender:Boolean=false):SalesNoticeSnapshot {
        val c=ConciliacionDelCobro.guardarCaptura(capture())
        val payment=JSONObject().put("paymentId","server-payment").put("orderId","original-order").put("venueId",VENUE)
            .put("status","COMPLETED").put("method","CASH").put("splitType","FULLPAYMENT")
            .put("amount",1999).put("tipAmount",1).put("remainingBalanceCents",0).put("orderPaymentStatus","PAID")
        if(remoteTender) payment.put("tenderTypeId","channel-tender").put("tenderRevision",7)
        val body=JSONObject().put("success",true).put("payment",payment).toString()
        val evaluation=ConciliacionDelCobro.evaluar(c,VENUE,KEY,CanalDelCobro.ORDER,body)
        check(evaluation.aplicado && evaluation.camposDiferentes.isEmpty())
        f.payment(KEY,VENUE,mapOf("paymentType" to "ORDER","orderId" to "original-order","amountCents" to 1999,"tipCents" to 1,
            "syncStatus" to "SYNCED","q15CaptureJson" to c,"q15InitialResultJson" to evaluation.resultadoJson,
            "q15ResultJson" to evaluation.resultadoJson,"q15Reason" to ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,"q15DetectedAt" to 102))
        return f.daoPageSingle()
    }
    private suspend fun SalesNoticeSqlTest.Fixture.daoPageSingle()=dao.page(VENUE,"",null,emptySet()).rows.single()
    private fun capture()=CapturaDelCobro(VENUE,KEY,"original-order",1999,1,splitType="FULLPAYMENT",method="CASH")
    private companion object {const val VENUE="venue-A";const val KEY="notice-recovery"}
}
