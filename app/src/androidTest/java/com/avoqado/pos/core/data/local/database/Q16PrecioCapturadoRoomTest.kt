package com.avoqado.pos.core.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import androidx.room.withTransaction
import com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Q16PrecioCapturadoRoomTest {
    private lateinit var db:AvoqadoDatabase
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AvoqadoDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private suspend fun payment(id:String,ref:String,venue:String="A",status:String="PENDING") {
        db.pendingPaymentDao().insert(PendingPaymentEntity(id,venue,"staff",10005,500,"CASH","ORDER",orderId=ref,syncStatus=status,capturedOrderRef=ref))
    }
    @Test fun pendientes_y_reservas_no_pierden_enlace_pero_historia_confirmada_no_bloquea()=runBlocking {
        payment("pending","R");payment("flight","F",status="EN_VUELO");payment("done","D",status="SYNCED")
        assertEquals(setOf("R","F"),db.syncIntentDao().referenciasSinResolverQ16("A",listOf("R","F","D")).toSet())
    }
    @Test fun ref_exacta_y_tenant_sin_truncar_en_silencio()=runBlocking {
        payment("a","order");payment("b","other","B")
        assertEquals(listOf("order"),db.syncIntentDao().referenciasSinResolverQ16("A",listOf("order","other")))
        assertTrue(db.syncIntentDao().referenciasSinResolverQ16("A",listOf("orde")).isEmpty())
    }
    @Test fun paycash_pendiente_se_consulta_por_indice_y_acked_no_impide_ronda_nueva()=runBlocking {
        db.syncIntentDao().insertWithNextSeq(SyncIntentEntity("pay","A",seq=0,type="PAY_CASH",payloadJson="{}",capturedOrderRef="R"))
        db.syncIntentDao().insertWithNextSeq(SyncIntentEntity("done","A",seq=0,type="PAY_CASH",payloadJson="{}",status="ACKED",capturedOrderRef="D"))
        assertEquals(listOf("R"),db.syncIntentDao().referenciasSinResolverQ16("A",listOf("R","D")))
    }
    @Test fun price_hold_conserva_captura_y_no_libera_por_revision()=runBlocking {
        payment("a","R")
        val before=db.pendingPaymentDao().findForVenue("A","a")!!
        assertEquals(1,db.pendingPaymentDao().retenerPagoQ15("A","a","PRICE_PROOF_PENDING",1))
        assertEquals(0,db.pendingPaymentDao().revisarPagoQ15("A","a","manager","MANAGER",2))
        val after=db.pendingPaymentDao().findForVenue("A","a")!!
        assertEquals(before.amountCents,after.amountCents);assertEquals(before.retryCount,after.retryCount)
        assertEquals(before.orderId,after.orderId);assertEquals("PENDING",after.syncStatus)
        assertEquals(listOf("R"),db.syncIntentDao().referenciasSinResolverQ16("A",listOf("R")))
    }
    @Test fun captura_room_real_invalida_lectura_vacia_y_raw_tambien()=runBlocking {
        for(raw in listOf(false,true)) {
            val venue="A-$raw";val token=AdmisionDePrecioQ16.snapshot(venue)
            assertTrue(db.syncIntentDao().referenciasSinResolverQ16(venue,listOf("L")).isEmpty())
            val row=PendingPaymentEntity("p-$raw",venue,"staff",10005,500,"CASH","ORDER",orderId="L",capturedOrderRef="L")
            if(raw) db.pendingPaymentDao().insertRaw(row) else db.pendingPaymentDao().insert(row)
            var sent=false;assertFalse(AdmisionDePrecioQ16.admit(token) { sent=true });assertFalse(sent)
            assertEquals(10005,db.pendingPaymentDao().findForVenue(venue,row.id)!!.amountCents)
        }
    }
    @Test fun pay_cash_raw_y_allocator_room_invalidan_token_sin_perder_uuid()=runBlocking {
        for(raw in listOf(false,true)) {
            val venue="cash-$raw";val token=AdmisionDePrecioQ16.snapshot(venue)
            val row=SyncIntentEntity("cash-$raw",venue,seq=1,type="PAY_CASH",payloadJson="{}",capturedOrderRef="L")
            if(raw) db.syncIntentDao().insert(row) else db.syncIntentDao().insertWithNextSeq(row)
            assertFalse(AdmisionDePrecioQ16.admit(token) { fail("No debe admitir") })
            assertEquals(row.id,db.syncIntentDao().findById(row.id)!!.id)
        }
    }
    @Test fun writer_room_anunciado_antes_de_esperar_tx_y_cancelacion_libera_admision()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=launch(Dispatchers.IO) { db.withTransaction { entered.complete(Unit);release.await() } }
        entered.await()
        val writer=launch(start=CoroutineStart.UNDISPATCHED) {
            db.pendingPaymentDao().insert(PendingPaymentEntity("blocked","A","staff",10005,500,"CASH","ORDER",orderId="L",capturedOrderRef="L"))
        }
        try {
            assertFalse(AdmisionDePrecioQ16.admit(AdmisionDePrecioQ16.snapshot("A")) { fail("Writer sigue activo") })
            writer.cancelAndJoin()
            assertTrue(AdmisionDePrecioQ16.admit(AdmisionDePrecioQ16.snapshot("A")) { })
        } finally { writer.cancel();release.complete(Unit);holder.join() }
    }
    @Test fun captura_room_otra_sucursal_no_invalida_ni_borra()=runBlocking {
        val token=AdmisionDePrecioQ16.snapshot("A")
        payment("other-capture","L",venue="B")
        assertTrue(AdmisionDePrecioQ16.admit(token) { })
        assertNotNull(db.pendingPaymentDao().findForVenue("B","other-capture"))
    }
    @Test fun recovery_CAS_room_preserva_captura_todas_las_columnas_y_tenant()=runBlocking {
        val row=PendingPaymentEntity("capture","A","staff",10005,500,"CASH","ORDER",orderRequestJson="original",q15CaptureJson="original-proof",q15Reason="PRICE_PROOF_PENDING",customerId="customer",splitType="CUSTOM_AMOUNT")
        db.pendingPaymentDao().insert(row)
        assertEquals(0,db.pendingPaymentDao().recuperarOrdenQ16("B",row.id,null,"R"))
        assertEquals(1,db.pendingPaymentDao().recuperarOrdenQ16("A",row.id,null,"R"))
        val after=db.pendingPaymentDao().findForVenue("A",row.id)!!
        assertEquals(row.copy(orderId="R",q15Reason=null),after)
    }
    @Test fun ausencia_room_real_incluye_null_todos_no_terminales_y_respeta_venue()=runBlocking {
        assertFalse(db.syncIntentDao().hayCapturaSinResolverQ16("A"))
        for(status in listOf("PENDING","SYNCING","FAILED","EN_VUELO","UNKNOWN")) {
            val row=PendingPaymentEntity("null-$status","A-$status","staff",10005,500,"CASH","ORDER",syncStatus=status)
            db.pendingPaymentDao().insertRaw(row)
            assertTrue(db.syncIntentDao().hayCapturaSinResolverQ16("A-$status"))
        }
        db.pendingPaymentDao().insertRaw(PendingPaymentEntity("terminal","terminal","staff",10005,500,"CASH","ORDER",syncStatus="SYNCED"))
        assertFalse(db.syncIntentDao().hayCapturaSinResolverQ16("terminal"))
        for(status in listOf("PENDING","HELD","REJECTED","UNKNOWN")) {
            db.syncIntentDao().insert(SyncIntentEntity("pay-$status","pay-$status",seq=1,type="PAY_CASH",payloadJson="{}",status=status))
            assertTrue(db.syncIntentDao().hayCapturaSinResolverQ16("pay-$status"))
        }
        db.syncIntentDao().insert(SyncIntentEntity("ack","ack",seq=1,type="PAY_CASH",payloadJson="{}",status="ACKED"))
        assertFalse(db.syncIntentDao().hayCapturaSinResolverQ16("ack"));assertFalse(db.syncIntentDao().hayCapturaSinResolverQ16("A"))
    }
}
