package com.avoqado.pos.payment
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.payment.data.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
class Q15ConfirmacionTest {
 private val dao=mockk<PendingPaymentDao>(relaxed=true)
 private val storage=mockk<SecureStorage>(relaxed=true)
 private fun row(capture:Boolean)=PendingPaymentEntity("p1","FULLTEST-A","staff",100,0,"CASH","ORDER",orderId="o1",syncStatus="EN_VUELO",
  q15CaptureJson=if(capture)ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("FULLTEST-A","p1","o1",100,0))else null)
 private fun repo():CashPaymentRepository {every {storage.venueId} returns "FULLTEST-A";return CashPaymentRepository(storage,dao)}
 @Test fun legacy_no_se_suelta_ni_descarta_y_guarda_motivo_sin_inventar_captura()=runTest {
  coEvery {dao.findForVenue("FULLTEST-A","p1")} returns row(false)
  val subject=repo();assertFalse(subject.confirmarQueSeCobro("p1"));assertFalse(subject.descartarPorqueNoSeCobro("p1"))
  coVerify(exactly=2){dao.retenerPagoQ15("FULLTEST-A","p1",ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA,any())}
  coVerify(exactly=0){dao.enVueloAPendiente(any())};coVerify(exactly=0){dao.borrarSiEnVuelo(any())}
 }
 @Test fun captura_nueva_completa_se_encola_solo_con_misma_llave()=runTest {
  coEvery {dao.findForVenue("FULLTEST-A","p1")} returns row(true);coEvery {dao.enVueloAPendiente("p1")} returns 1
  assertTrue(repo().confirmarQueSeCobro("p1"));coVerify(exactly=1){dao.enVueloAPendiente("p1")}
 }
 @Test fun price_y_tenant_ajeno_no_se_liberan()=runTest {
  coEvery {dao.findForVenue("FULLTEST-A","p1")} returns row(true).copy(q15Reason=ConciliacionDelCobro.PRICE_PROOF_PENDING)
  coEvery {dao.findForVenue("FULLTEST-A","foreign")} returns null
  val subject=repo();assertFalse(subject.confirmarQueSeCobro("p1"));assertFalse(subject.confirmarQueSeCobro("foreign"))
  coVerify(exactly=0){dao.enVueloAPendiente(any())}
 }
}
