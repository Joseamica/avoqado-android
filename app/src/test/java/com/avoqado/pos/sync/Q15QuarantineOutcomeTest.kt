package com.avoqado.pos.sync
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
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
}
