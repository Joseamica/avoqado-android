package com.avoqado.pos.core.data.local.database
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avoqado.pos.payment.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class Q15LegacyCaptureRoomTest {
 private lateinit var db:AvoqadoDatabase
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AvoqadoDatabase::class.java).build()}
 @After fun cleanup(){db.close()}
 @Test fun reserva_repetida_no_construye_prueba_nueva_sobre_null_legacy()=runBlocking {
  val dao=db.pendingPaymentDao()
  val old=PendingPaymentEntity("old","FULLTEST-A","staff-old",50,0,"CASH","ORDER",orderId="o-original",orderRequestJson="original-payload",syncStatus="PENDING")
  dao.insert(old)
  val new=old.copy(amountCents=100,tipCents=5,staffId="new-staff",syncStatus="EN_VUELO",q15CaptureJson=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("FULLTEST-A","old","o-original",100,5)))
  assertTrue(runCatching {dao.insert(new)}.isFailure)
  val preserved=requireNotNull(dao.findForVenue("FULLTEST-A","old"))
  assertEquals(old,preserved);assertNull(preserved.q15CaptureJson);assertEquals("PENDING",preserved.syncStatus)
 }
 @Test fun reserva_no_libera_hold_de_precio_aunque_tenga_captura_nueva()=runBlocking {
  val dao=db.pendingPaymentDao()
  val row=PendingPaymentEntity("p1","FULLTEST-A","staff",100,0,"CASH","ORDER",orderId="o1",q15CaptureJson=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("FULLTEST-A","p1","o1",100,0)),q15Reason=ConciliacionDelCobro.PRICE_PROOF_PENDING)
  dao.insert(row);assertTrue(runCatching {dao.insert(row.copy(syncStatus="EN_VUELO",q15Reason=null))}.isFailure)
  assertEquals(row,dao.findForVenue("FULLTEST-A","p1"))
 }
}
