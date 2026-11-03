package com.avoqado.pos.sync
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Q15QuarantineOutcomeTest {
 @get:Rule val main=MainDispatcherRule()
 private val cash=mockk<CashPaymentRepository>(relaxed=true);private val sync=mockk<PaymentSyncService>(relaxed=true)
 private fun vm():QuarantineViewModel {
  val storage=mockk<SecureStorage>(relaxed=true);every {storage.userRole} returns "MANAGER";every {storage.venueId} returns "FULLTEST-A"
  val outbox=mockk<SyncOutbox>(relaxed=true);every {outbox.rejectedCount} returns kotlinx.coroutines.flow.MutableStateFlow(0)
  every {cash.observarSinConfirmar()} returns flowOf(emptyList())
  return QuarantineViewModel(outbox,storage,sync,mockk<ReservationRepository>(relaxed=true),RoleManager(storage),cash)
 }
 @Test fun confirmacion_retenida_no_dispara_sync_ni_canta_enviado()=runTest {
  coEvery {cash.confirmarQueSeCobro("old")} returns false
  val subject=vm();subject.confirmarCobroInterrumpido("old");advanceUntilIdle()
  verify(exactly=0){sync.syncNow()};assertNull(subject.successMessage.value);assertNotNull(subject.errorMessage.value)
 }
 @Test fun delete_rechazado_no_canta_resuelto_ni_se_pierde_error()=runTest {
  coEvery {sync.dismissFailedPayment("old")} returns false
  val subject=vm();subject.dismissPayment("old");advanceUntilIdle()
  assertNull(subject.successMessage.value);assertTrue(subject.errorMessage.value?.contains("conserva")==true)
 }
 @Test fun resultado_incierto_no_promete_retry_automatico()=runTest {
  coEvery {sync.retryFailedPayment("held")} returns PaymentSyncService.RetryOutcome.StillQueued
  val subject=vm();subject.retryPayment("held");advanceUntilIdle()
  assertNull(subject.successMessage.value);assertTrue(subject.errorMessage.value?.contains("No vuelvas a cobrar")==true)
 }
 @Test fun cuenta_ausente_no_hace_descartable_un_pago_capturado() {
  val hint=vm().resolutionHint("ORDER_NOT_FOUND","PAY_CASH")
  assertTrue(hint.contains("No vuelvas a cobrar"));assertFalse(hint.contains("No requiere acción"))
 }
 @Test fun cortesia_no_confirmada_conserva_motivo_local_y_no_promete_que_vuelva_el_servidor() {
  assertEquals(CortesiaPersistida.AVISO,vm().detalleRechazo(CortesiaPersistida.AVISO))
 }
 @Test fun fila_failed_de_cortesia_muestra_el_motivo_persistido() {
  val payment=PendingPaymentEntity(id="c000000000000000000000001",venueId="FULLTEST-A",staffId="staff",amountCents=0,
   tipCents=0,method="CASH",paymentType="ORDER",syncStatus="FAILED",lastError=CortesiaPersistida.AVISO)
  assertEquals(CortesiaPersistida.AVISO,vm().paymentResolutionHint(payment))
 }
 @Test fun reintento_rechazado_de_cortesia_conserva_motivo_y_no_canta_exito()=runTest {
  coEvery {sync.retryFailedPayment("held")} returns PaymentSyncService.RetryOutcome.Rejected(CortesiaPersistida.AVISO)
  val subject=vm();subject.retryPayment("held");advanceUntilIdle()
  assertEquals(CortesiaPersistida.AVISO,subject.errorMessage.value);assertNull(subject.successMessage.value)
 }
 @Test fun errores_locales_desconocidos_o_avisos_alterados_no_se_imprimen_crudos() {
  val fallback="El cobro no logró confirmarse. Conserva el efectivo registrado y reintenta cuando el servidor esté disponible."
  for(error in listOf("<b>privado</b>\u0000",CortesiaPersistida.AVISO+" privado","prefijo "+CortesiaPersistida.AVISO,
   CortesiaPersistida.AVISO.lowercase()," "+CortesiaPersistida.AVISO)) {
   assertEquals(fallback,vm().detalleRechazo(error))
  }
 }
 @Test fun detalle400_conserva_causa_real_sin_json_html_ni_controles() {
  val hint=vm().detalleRechazo("Error 400: {\"message\":\"<b>No se puede pagar una cuenta cancelada</b>\\u0000\",\"stack\":\"interno\"}")
  assertTrue(hint.contains("No se puede pagar una cuenta cancelada"))
  assertFalse(hint.contains("<b>"));assertFalse(hint.contains("stack"));assertFalse(hint.contains('\u0000'))
 }
 @Test fun importes40100_40300_40400_en_cuerpo400_no_cambian_el_codigo_http() {
  for(amount in listOf(40100,40300,40400)) {
   val cause="Payment amount (0) is less than remaining balance ($amount)"
   for(prefix in listOf("Error 400:","Order recreate failed (400):")) {
    val hint=vm().detalleRechazo("$prefix {\"message\":\"$cause\"}")
    assertTrue(hint,hint.contains(cause))
   }
  }
 }
 @Test fun codigos401_403_404_reales_con400_en_cuerpo_conservan_su_guia() {
  for((code,message) in listOf(401 to "La sesión expiró",403 to "no tenía permiso",404 to "ya no existe")) {
   val hint=vm().detalleRechazo("Error $code: {\"message\":\"Folio400\"}")
   assertTrue(hint,hint.contains(message))
  }
 }
}
