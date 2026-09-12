package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Una anulación de cuenta que se encoló SIN RED y, al reproducirse, chocó con un cobro de tarjeta
 * vivo: el servidor la rechaza con `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE` y cae en cuarentena.
 * Sin un texto propio, el gerente leía la guía genérica («vuelve a hacerla manualmente») sobre una
 * cuenta que NO se puede anular hasta que el cobro termine.
 */
class CuarentenaCobroVivoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(): QuarantineViewModel {
        val syncOutbox: SyncOutbox = mockk(relaxed = true)
        val secureStorage: SecureStorage = mockk(relaxed = true)
        every { syncOutbox.rejectedCount } returns MutableStateFlow(0)
        every { secureStorage.userRole } returns "ADMIN"
        every { secureStorage.venuePermissions } returns emptyList()
        return QuarantineViewModel(
            syncOutbox = syncOutbox,
            secureStorage = secureStorage,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            reservationRepository = mockk<ReservationRepository>(relaxed = true),
            roleManager = RoleManager(secureStorage),
        )
    }

    @Test
    fun `P1 una anulacion bloqueada por un cobro vivo dice que hay que resolver el cobro`() {
        assertEquals(
            CancelacionDeCobro.COBRO_EN_CURSO_BLOQUEA_CUENTA,
            viewModel().resolutionHint("ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE", "CANCEL_ORDER"),
        )
    }

    @Test
    fun `una fusion bloqueada por el mismo motivo dice lo mismo`() {
        assertEquals(
            CancelacionDeCobro.COBRO_EN_CURSO_BLOQUEA_CUENTA,
            viewModel().resolutionHint("ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE", "MERGE_ORDERS"),
        )
    }
}
