package com.avoqado.pos.core.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Q15IdentidadLegacyRoomTest {
    private lateinit var db: AvoqadoDatabase
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AvoqadoDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    @Test fun referencias_legacy_paginadas_no_crean_evidencia_financiera() = runBlocking {
        val sql=db.openHelper.writableDatabase
        for (n in 0..120) {
            val id="p%03d".format(n)
            sql.execSQL("INSERT INTO pending_payments(id,venueId,staffId,amountCents,tipCents,method,paymentType,orderId,syncStatus,retryCount,createdAt,splitType) VALUES(?,?,'staff',10005,500,'CASH','ORDER',?,'PENDING',0,1,'FULLPAYMENT')",arrayOf(id,if(n%2==0) "FULLTEST-A" else "FULLTEST-B","R-$n"))
            sql.execSQL("INSERT INTO pos_sync_intents(id,venue_id,seq,type,payload_json,status,created_at) VALUES(?,'FULLTEST-A',?,'PAY_CASH',?,'PENDING',1)",arrayOf("i%03d".format(n),n+1,"{\"localOrderId\":\"L-$n\",\"orderId\":\"R-$n\",\"amount\":99}"))
        }
        sql.execSQL("INSERT INTO pos_sync_intents(id,venue_id,seq,type,payload_json,status,created_at) VALUES('z-number','FULLTEST-A',122,'PAY_CASH','{\"localOrderId\":123,\"orderId\":456}','PENDING',1),('z-blank','FULLTEST-A',123,'PAY_CASH','{\"localOrderId\":\" \",\"orderId\":\"R-original\"}','PENDING',1),('z-other','FULLTEST-A',124,'ADD_ITEMS','{\"orderId\":\"R-other\"}','PENDING',1)")
        AvoqadoDatabaseMigrations.restaurarReferenciasQ15(sql)
        sql.query("SELECT COUNT(*) FROM pending_payments WHERE capturedOrderRef=orderId AND q15CaptureJson IS NULL AND q15ResultJson IS NULL").use { it.moveToFirst();assertEquals(121,it.getInt(0)) }
        sql.query("SELECT COUNT(*) FROM pos_sync_intents WHERE capturedOrderRef LIKE 'L-%' AND q15CaptureJson IS NULL AND q15Reason IS NULL").use { it.moveToFirst();assertEquals(121,it.getInt(0)) }
        sql.query("SELECT capturedOrderRef FROM pos_sync_intents WHERE id='z-number'").use { it.moveToFirst();assertTrue(it.isNull(0)) }
        sql.query("SELECT capturedOrderRef FROM pos_sync_intents WHERE id='z-other'").use { it.moveToFirst();assertTrue(it.isNull(0)) }
        sql.query("SELECT capturedOrderRef FROM pos_sync_intents WHERE id='z-blank'").use { it.moveToFirst();assertEquals("R-original",it.getString(0)) }
        assertTrue(db.pendingPaymentDao().hayCapturaDeOrdenQ15("FULLTEST-B","R-119"))
        assertFalse(db.pendingPaymentDao().hayCapturaDeOrdenQ15("FULLTEST-A","R-119"))
        AvoqadoDatabaseMigrations.restaurarReferenciasQ15(sql)
        sql.query("SELECT COUNT(*) FROM pending_payments WHERE q15CaptureJson IS NOT NULL").use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
    }
    @Test fun uuid_ajeno_no_se_reemplaza_al_reservar() = runBlocking {
        val dao=db.pendingPaymentDao()
        val a=PendingPaymentEntity("same-id","FULLTEST-A","staff",100,0,"CASH","ORDER",orderId="A",q15CaptureJson="captura-A")
        dao.insert(a)
        val result=runCatching { dao.insert(a.copy(venueId="FULLTEST-B",orderId="B",q15CaptureJson="captura-B")) }
        assertTrue(result.isFailure);assertEquals("captura-A",dao.findForVenue("FULLTEST-A","same-id")?.q15CaptureJson)
        assertNull(dao.findForVenue("FULLTEST-B","same-id"))
    }
}
