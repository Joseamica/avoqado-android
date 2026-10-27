package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.Q16PrecioCapturadoTest
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Fui5QuarantineOutcomeTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `FUI5 consulta sin prueba se explica sin palomita ni promesa de replay`() = runTest {
        val result = Q16PrecioCapturadoTest.Fixture().use { f ->
            every { f.storage.userRole } returns "MANAGER"; every { f.storage.userId } returns "gerente-actual"
            f.insertCaptured(PendingPaymentEntity("folio-original", Q16PrecioCapturadoTest.VENUE, "staff", 0, 0, "CASH", "ORDER",
                orderRequestJson = """{"externalId":"venta-original"}""", syncStatus = "FAILED"))
            f.code = 200; f.response = "{}"
            f.service.retryFailedPayment("folio-original")
        }
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.userRole } returns "MANAGER"; every { storage.venueId } returns "FULLTEST-A"
        val outbox = mockk<SyncOutbox>(relaxed = true)
        every { outbox.rejectedCount } returns MutableStateFlow(0)
        val cash = mockk<CashPaymentRepository>(relaxed = true)
        every { cash.observarSinConfirmar() } returns flowOf(emptyList())
        val sync = mockk<PaymentSyncService>(relaxed = true)
        coEvery { sync.retryFailedPayment("folio-original") } returns result
        val vm = QuarantineViewModel(outbox, storage, sync, mockk<ReservationRepository>(relaxed = true), RoleManager(storage), cash)
        vm.retryPayment("folio-original"); advanceUntilIdle()
        assertEquals("No se pudo comprobar que esta cuenta esté cancelada y sin cobros. El intento sigue por revisar. No vuelvas a cobrar.", vm.errorMessage.value)
        assertNull(vm.successMessage.value)
        verify(exactly = 0) { sync.syncNow() }
    }
}
