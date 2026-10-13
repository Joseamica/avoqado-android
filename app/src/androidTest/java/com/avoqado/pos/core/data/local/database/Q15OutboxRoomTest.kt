package com.avoqado.pos.core.data.local.database
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avoqado.pos.payment.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Q15OutboxRoomTest {
    private lateinit var db:AvoqadoDatabase
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AvoqadoDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private fun original()=SyncIntentEntity("pay1","FULLTEST-A",seq=0,type="PAY_CASH",payloadJson="{\"localOrderId\":\"L1\",\"amountCents\":10005,\"tipCents\":500}",createdAt=1,q15CaptureJson=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("FULLTEST-A","pay1",amountCents=10005,tipCents=500,localOrderId="L1")),capturedOrderRef="L1")
    @Test fun ack_y_resultado_se_conservan_atomicos_no_se_reencolan_ni_ttl_borra()=runBlocking {
        val dao=db.syncIntentDao();val seq=dao.insertWithNextSeq(original())
        dao.resolverCobroQ15("pay1","FULLTEST-A",seq,"ACKED",null,null,"wire",ConciliacionDelCobro.DIFERENCIA,"economic",10)
        val row=requireNotNull(dao.findById("pay1"));assertEquals("ACKED",row.status);assertEquals("economic",row.q15InitialResultJson)
        assertEquals("L1",row.capturedOrderRef);assertNotNull(row.q15CaptureJson)
        dao.resolverCobroQ15("pay1","FULLTEST-A",seq,"REJECTED","OLD",null,null,ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,null,20)
        assertEquals("ACKED",dao.findById("pay1")?.status);dao.deleteOldAcked(Long.MAX_VALUE);dao.dismiss("pay1");assertNotNull(dao.findById("pay1"))
    }
    @Test fun rollback_y_tenant_no_emiten_estado_aplicado_sin_evidencia()=runBlocking {
        val dao=db.syncIntentDao();val seq=dao.insertWithNextSeq(original())
        assertTrue(runCatching { dao.resolverCobroQ15("pay1","FULLTEST-B",seq,"ACKED",null,null,null,null,"result",10) }.isFailure)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER q15_fail BEFORE UPDATE OF q15ResultJson ON pos_sync_intents BEGIN SELECT RAISE(FAIL,'disco de prueba'); END")
        assertTrue(runCatching { dao.resolverCobroQ15("pay1","FULLTEST-A",seq,"ACKED",null,null,null,null,"result",10) }.isFailure)
        assertEquals("PENDING",dao.findById("pay1")?.status);assertNull(dao.findById("pay1")?.q15ResultJson)
    }
    @Test fun stale_de_cobro_conserva_historial_y_piso_en_el_mismo_commit()=runBlocking {
        val dao=db.syncIntentDao();val seq=dao.insertWithNextSeq(original())
        dao.resolveStaleSequence("pay1","FULLTEST-A",seq,100,"STALE_DEVICE_SEQUENCE","old",null)
        val row=requireNotNull(dao.findById("pay1"));assertEquals("REJECTED",row.status)
        assertEquals(ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,row.q15Reason);assertEquals(100L,dao.sequenceState())
        dao.dismiss("pay1");assertNotNull(dao.findById("pay1"))
    }

}
