package com.avoqado.pos.settings

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.auth.data.SwitchUserResult
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.kiosk.data.KioskTap
import com.avoqado.pos.reservations.data.PendingReservationActionDao
import com.avoqado.pos.reservations.data.PendingReservationActionEntity
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Las llegadas del kiosco guardadas sin red bloquean el cambio de sucursal (Modo Autoservicio 0.e).
 *
 * 🔴 Cada llegada guarda su sucursal y el reintentador sólo manda las de la sucursal activa: si se cambiara con
 * llegadas pendientes, se quedarían esperando a que alguien vuelva a esa sucursal. Igual que con los cobros sin red.
 */
class MoreMenuViewModelLlegadasTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `cambiar de sucursal se bloquea con llegadas del kiosco pendientes`() = runTest {
        val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns "v1" }
        val auth = mockk<AuthRepository>(relaxed = true)
        val llegadas = mockk<PendingReservationActionDao> {
            coEvery { all() } returns listOf(
                PendingReservationActionEntity(
                    reservationId = "r1",
                    action = KioskTap.ACTION,
                    payloadJson = KioskTap.payload("v1", "2026-10-28T19:20:00.000Z", 900_000),
                ),
            )
        }
        val vm = construir(storage, auth, llegadas)

        vm.switchVenue(StoredVenue(id = "v2", name = "Sucursal Centro"))

        coVerify(exactly = 0) { auth.switchVenue(any()) }
        val aviso = vm.sessionGuardMessage.value.orEmpty()
        assertTrue("esperaba el aviso de pendientes, llegó «$aviso»", aviso.contains("1 operación offline"))
    }

    /** Retiro con gerente (Task 9, Codex r3 #12): el replay usa la sesión vigente; relevar con un retiro sin subir lo dejaría a nombre de otro. */
    @Test
    fun `con un retiro sin subir no se cambia de usuario`() = runTest {
        val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns "v1" }
        val auth = mockk<AuthRepository>(relaxed = true)
        val cajon = mockk<com.avoqado.pos.cashdrawer.data.CashDrawerRepository> { every { movimientosSinSubir("v1") } returns 1 }

        val resultado = construir(storage, auth, cajon = cajon).switchUser("1234")

        coVerify(exactly = 0) { auth.switchUser(any()) }
        assertTrue("esperaba el aviso de pendientes, llegó $resultado", (resultado as? SwitchUserResult.Error)?.message.orEmpty().contains("Hay 1 operación sin enviar"))
    }

    private fun construir(
        storage: SecureStorage,
        auth: AuthRepository,
        llegadas: PendingReservationActionDao = mockk { coEvery { all() } returns emptyList() },
        cajon: com.avoqado.pos.cashdrawer.data.CashDrawerRepository? = null,
    ): MoreMenuViewModel {
        val tpv = mockk<TpvSettingsRepository>(relaxed = true) {
            every { terminalNavigation } returns MutableStateFlow(TerminalNavigationSettings.DEFAULT)
        }
        return MoreMenuViewModel(
            secureStorage = storage,
            authRepository = auth,
            timeEntryRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            printConfigRepository = mockk(relaxed = true),
            roleManager = mockk(relaxed = true),
            planManager = mockk(relaxed = true),
            posModeManager = mockk(relaxed = true),
            addonsManager = mockk(relaxed = true),
            activeCartState = mockk(relaxed = true),
            screenPinningManager = mockk(relaxed = true),
            customerDisplayPrefs = mockk(relaxed = true),
            customerDisplayState = mockk(relaxed = true),
            displayModePrefs = mockk(relaxed = true),
            kioskPrefs = mockk(relaxed = true),
            cashierDisplayGuard = mockk(relaxed = true),
            venueSwitchState = mockk(relaxed = true),
            paymentSyncService = mockk(relaxed = true),
            syncOutbox = mockk(relaxed = true),
            wasteSyncCoordinator = mockk(relaxed = true),
            bloqueoDeMerma = mockk(relaxed = true),
            pendingWasteDao = mockk(relaxed = true),
            tpvSettingsRepository = tpv,
            vigilanteDePedidos = mockk(relaxed = true),
            pedidosEnLineaRepository = mockk(relaxed = true) { coEvery { porCobrarGuardado() } returns null },
            pendingReservationDao = llegadas,
            cashDrawerRepository = cajon,
        )
    }
}
