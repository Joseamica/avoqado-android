package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import com.avoqado.pos.sync.presentation.textoDeCobroDeOtraSucursal
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * D52 (12-nov): con un cobro pendiente de la sucursal A y la sesión en B, «Operaciones por revisar» no lo enseñaba (sólo B) y el freno
 * no dejaba cambiar a A: nadie lo veía y no había salida. Ahora la hoja lo enseña aparte, con el nombre de su sucursal y cómo llegar;
 * sin botones: se resuelve sólo con su sucursal abierta (los permisos son por sucursal).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuarantineOtrasSucursalesTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `P1 D52 la hoja ensena lo de otras sucursales con su nombre`() = runTest {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v-b"
        every { storage.userRole } returns "MANAGER"
        every { storage.venuesList } returns listOf(StoredVenue(id = "v-a", name = "Sucursal A"), StoredVenue(id = "v-b", name = "Sucursal B"))
        val outbox = mockk<SyncOutbox>(relaxed = true)
        every { outbox.rejectedCount } returns MutableStateFlow(0)
        val cash = mockk<CashPaymentRepository>(relaxed = true)
        every { cash.observarSinConfirmar() } returns flowOf(emptyList())
        val sync = mockk<PaymentSyncService>(relaxed = true)
        val deA = PendingPaymentEntity("cobro-a", "v-a", "mesero", 2_500, 0, "CASH", "FAST", syncStatus = "FAILED")
        coEvery { sync.pendientesDeOtrasSucursales("v-b") } returns listOf(deA)
        val vm = QuarantineViewModel(outbox, storage, sync, mockk<ReservationRepository>(relaxed = true), RoleManager(storage), cash)

        vm.load(); advanceUntilIdle()

        assertEquals(listOf("cobro-a" to "Sucursal A"), vm.deOtrasSucursales.value.map { it.cobro.id to it.sucursal })
    }

    @Test fun `P1 D52 el texto dice de que sucursal es y como llegar`() {
        assertEquals(
            "Es de Sucursal A. Cambia a Sucursal A para resolverla (Más › Cambiar de establecimiento).",
            textoDeCobroDeOtraSucursal("Sucursal A"),
        )
        assertEquals(
            "Es de otra sucursal. Que entre alguien con acceso a esa sucursal para resolverla (Más › Cambiar usuario).",
            textoDeCobroDeOtraSucursal(null),
        )
    }
}
