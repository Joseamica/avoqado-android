package com.avoqado.pos.core.data.local.database
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class Q15AvisosRoomTest {
 private lateinit var db:AvoqadoDatabase
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AvoqadoDatabase::class.java).build()}
 @After fun cleanup(){db.close()}
 @Test fun contador_vivo_observa_ambas_colas_review_y_tenant()=runBlocking {
  val changes=Channel<Int>(Channel.UNLIMITED)
  val job=launch { db.syncIntentDao().observarRevisionesQ15("FULLTEST-A",false,"").collect { changes.send(it) } }
  suspend fun espera(expected:Int) = withTimeout(5_000) { while(changes.receive()!=expected){} }
  try {
   espera(0)
   db.pendingPaymentDao().insert(PendingPaymentEntity("p1","FULLTEST-A","staff",100,0,"CASH","ORDER",orderId="o1"))
   db.pendingPaymentDao().registrarResultadoQ15("FULLTEST-A","p1",true,"DIFERENCIA_DE_COBRO","r",10);espera(1)
   val seq=db.syncIntentDao().insertWithNextSeq(SyncIntentEntity("i1","FULLTEST-A",seq=0,type="PAY_CASH",payloadJson="{}",createdAt=1))
   db.syncIntentDao().resolverCobroQ15("i1","FULLTEST-A",seq,"ACKED",null,null,null,"DIFERENCIA_DE_COBRO","r",10);espera(2)
   db.pendingPaymentDao().insert(PendingPaymentEntity("other","FULLTEST-B","staff",100,0,"CASH","ORDER",orderId="o1"))
   db.pendingPaymentDao().registrarResultadoQ15("FULLTEST-B","other",true,"DIFERENCIA_DE_COBRO","r",10)
   assertEquals(2,db.pendingPaymentDao().contarRevisionesQ15("FULLTEST-A",false,""))
   db.pendingPaymentDao().revisarPagoQ15("FULLTEST-A","p1","manager","MANAGER",20);espera(1)
   db.pendingPaymentDao().revisarIntentQ15("FULLTEST-A","i1","manager","MANAGER",20);espera(0)
   assertEquals(2,db.pendingPaymentDao().contarRevisionesQ15("FULLTEST-A",true,""))
  } finally {job.cancelAndJoin();changes.close()}
 }
 @Test fun legacy_en_vuelo_no_se_borra_ni_retry_inventa_evidencia()=runBlocking {
  val dao=db.pendingPaymentDao()
  dao.insert(PendingPaymentEntity("old","FULLTEST-A","staff",100,0,"CASH","ORDER",orderId="o1",syncStatus="EN_VUELO"))
  assertEquals(0,dao.borrarSiEnVuelo("old"))
  assertNotNull(dao.findForVenue("FULLTEST-A","old"));assertNull(dao.findForVenue("FULLTEST-A","old")?.q15CaptureJson)
 }
}
