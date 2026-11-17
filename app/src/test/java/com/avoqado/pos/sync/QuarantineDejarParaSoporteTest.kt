package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CobrosParaSoporte
import com.avoqado.pos.payment.data.CobrosParaSoporte.Declaracion
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import com.avoqado.pos.sync.presentation.textoDeNotaDeSoporte
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D63: «Dejar para soporte» en «Operaciones por revisar»: sólo gerente, con su nota a la vista, y las filas viejas sin sucursal se ven. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuarantineDejarParaSoporteTest {
    @get:Rule val main = MainDispatcherRule()

    private val fallido = PendingPaymentEntity("cobro-1", "venue-atole", "cajera", 12_900, 0, "CASH", "FAST", syncStatus = "FAILED")

    private fun vm(rol: String, sync: PaymentSyncService = mockk(relaxed = true), notas: CobrosParaSoporte? = CobrosParaSoporte(PrefsEnMemoria().context)): QuarantineViewModel {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "venue-atole"
        every { storage.userRole } returns rol
        val outbox = mockk<SyncOutbox>(relaxed = true)
        every { outbox.rejectedCount } returns MutableStateFlow(0)
        val cash = mockk<CashPaymentRepository>(relaxed = true)
        every { cash.observarSinConfirmar() } returns flowOf(emptyList())
        return QuarantineViewModel(outbox, storage, sync, mockk<ReservationRepository>(relaxed = true), RoleManager(storage), cash,
            cobrosParaSoporte = notas)
    }

    @Test fun `P1 el cajero no ve el boton ni puede dejarlo para soporte`() = runTest {
        val sync = mockk<PaymentSyncService>(relaxed = true)
        val cajero = vm("CASHIER", sync)

        assertFalse(cajero.puedeDejarParaSoporte(fallido))
        cajero.dejarParaSoporte("cobro-1", Declaracion.NO_SE); advanceUntilIdle()

        coVerify(exactly = 0) { sync.dejarParaSoporte(any(), any()) }
    }

    @Test fun `P1 el gerente lo ve y al dejarlo para soporte se le confirma`() = runTest {
        val sync = mockk<PaymentSyncService>(relaxed = true)
        coEvery { sync.dejarParaSoporte("cobro-1", Declaracion.SI_PAGO) } returns CobrosParaSoporte.Resultado.GUARDADA
        val gerente = vm("MANAGER", sync)

        assertTrue(gerente.puedeDejarParaSoporte(fallido))
        assertFalse(gerente.puedeDejarParaSoporte(fallido.copy(syncStatus = "EN_VUELO", q15Reason = null)))
        assertTrue(gerente.puedeDejarParaSoporte(fallido.copy(syncStatus = "EN_VUELO", q15Reason = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO)))
        gerente.dejarParaSoporte("cobro-1", Declaracion.SI_PAGO); advanceUntilIdle()

        assertEquals("Cobro dejado para soporte", gerente.successMessage.value)
    }

    @Test fun `P1 si no se pudo guardar se dice y el cobro sigue guardado`() = runTest {
        val sync = mockk<PaymentSyncService>(relaxed = true)
        coEvery { sync.dejarParaSoporte(any(), any()) } returns CobrosParaSoporte.Resultado.NO_SE_PUDO
        val gerente = vm("MANAGER", sync)

        gerente.dejarParaSoporte("cobro-1", Declaracion.NO_SE); advanceUntilIdle()

        assertEquals("No se pudo guardar la nota. Libera espacio en el aparato e intenta de nuevo. El cobro sigue guardado.", gerente.errorMessage.value)
    }

    @Test fun `P1 Codex r1 1 la hoja ensena la fila vieja sin sucursal`() = runTest {
        val sync = mockk<PaymentSyncService>(relaxed = true)
        val vieja = fallido.copy(id = "sin-sucursal", venueId = "", q15Reason = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA)
        coEvery { sync.failedPayments() } returns listOf(fallido)
        coEvery { sync.retenidasSinOtraVista() } returns listOf(vieja)
        val gerente = vm("MANAGER", sync)

        gerente.load(); advanceUntilIdle()

        assertEquals(listOf("cobro-1", "sin-sucursal"), gerente.failedPayments.value.map { it.id })
        assertTrue(gerente.puedeDejarParaSoporte(vieja))
    }

    @Test fun `P2 la nota dice quien, cuando y que respondio`() {
        val nota = CobrosParaSoporte.Nota(paymentId = "cobro-1", venueId = "venue-atole", declaracion = Declaracion.NO_PAGO, actorId = "u-1",
            actorNombre = "Luis Pérez", actorRol = "MANAGER", marcadoEn = 1L, estado = "FAILED", importeCents = 12_900, propinaCents = 0)
        assertEquals("Dejado para soporte por Luis Pérez · 16 nov 14:02 · «No pagó»", textoDeNotaDeSoporte(nota, "16 nov 14:02"))
        assertEquals("Dejado para soporte por un gerente · 16 nov 14:02 · «No sé»",
            textoDeNotaDeSoporte(nota.copy(actorNombre = null, declaracion = Declaracion.NO_SE), "16 nov 14:02"))
    }
}
