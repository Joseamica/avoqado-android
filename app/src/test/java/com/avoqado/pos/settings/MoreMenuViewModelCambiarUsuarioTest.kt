package com.avoqado.pos.settings

import android.content.Context
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.auth.data.SwitchUserResult
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.payment.Q16PrecioCapturadoTest
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D28 (recorrido B3, 12-nov): el cambio de usuario por PIN decía «Hay 1 operación sin enviar. Espera a que se sincronice…» con
 * una operación que el SERVIDOR ya había rechazado. Lo rechazado no sale solo y sólo un gerente lo resuelve EN LA SESIÓN
 * abierta, así que el mesero quedaba trabado sin poder dejar entrar al gerente.
 *
 * El freno cuenta sólo lo que de verdad falta enviar (sin enviar, en vuelo, retenido). Lo rechazado se queda en «Operaciones
 * por revisar» a nombre de quien lo hizo: no vuelve a salir solo, y el reintento del gerente lleva al autor original.
 * Cerrar sesión y cambiar de sucursal siguen contándolo (sin cambio).
 */
class MoreMenuViewModelCambiarUsuarioTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val venue = Q16PrecioCapturadoTest.VENUE
    private val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns venue }
    private val auth = mockk<AuthRepository>(relaxed = true) {
        coEvery { switchUser(any()) } returns SwitchUserResult.Error("PIN incorrecto")
    }
    private val dao = mockk<SyncIntentDao>(relaxed = true)

    /** La cola de mesas de verdad, sobre un DAO que sólo contesta los conteos. */
    private fun cola(pendientes: Int = 0, retenidas: Int = 0, rechazadas: Int = 0): SyncOutbox {
        coEvery { dao.pendingCount(venue) } returns pendientes
        coEvery { dao.heldCount(venue) } returns retenidas
        coEvery { dao.rejectedCount(venue) } returns rechazadas
        return SyncOutbox(dao, mockk(relaxed = true), mockk(relaxed = true), storage, mockk(relaxed = true),
            mockk<Context>(relaxed = true), mockk(relaxed = true))
    }

    private fun cobrosSinNada(f: Q16PrecioCapturadoTest.Fixture) {
        coEvery { f.dao.getUnsyncedPayments() } returns emptyList()
        coEvery { f.dao.getFailedPayments() } returns emptyList()
        coEvery { f.dao.enVueloCount() } returns 0
    }

    private fun cobroFallido() = PendingPaymentEntity(
        "cobro-rechazado", venue, "mesero-que-cobro", 2_500, 0, "CASH", "FAST", syncStatus = "FAILED", lastError = "Error 400",
    )

    @Test
    fun `P1 con solo una operacion de mesa rechazada si se cambia de usuario`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            val vm = construir(cola(rechazadas = 1), f.service)

            vm.switchUser("1234")

            coVerify(exactly = 1) { auth.switchUser("1234") }
        }
    }

    @Test
    fun `P1 con solo un cobro rechazado por revisar si se cambia de usuario`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            coEvery { f.dao.getFailedPayments() } returns listOf(cobroFallido())
            val vm = construir(cola(), f.service)

            vm.switchUser("1234")

            coVerify(exactly = 1) { auth.switchUser("1234") }
        }
    }

    @Test
    fun `P1 una ronda sin enviar sigue frenando el cambio con el aviso de siempre`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            val vm = construir(cola(pendientes = 1, rechazadas = 1), f.service)

            val resultado = vm.switchUser("1234")

            coVerify(exactly = 0) { auth.switchUser(any()) }
            assertEquals(
                "Hay 1 operación sin enviar. Espera a que se sincronice antes de cambiar de usuario.",
                (resultado as? SwitchUserResult.Error)?.message,
            )
        }
    }

    @Test
    fun `P1 una ronda en vuelo y un cobro en vuelo siguen frenando el cambio`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            coEvery { f.dao.enVueloCount() } returns 1
            coEvery { f.dao.getFailedPayments() } returns listOf(cobroFallido())
            val vm = construir(cola(retenidas = 1), f.service)

            val resultado = vm.switchUser("1234")

            coVerify(exactly = 0) { auth.switchUser(any()) }
            assertEquals(
                "Hay 2 operaciones sin enviar. Espera a que se sincronicen antes de cambiar de usuario.",
                (resultado as? SwitchUserResult.Error)?.message,
            )
        }
    }

    /** Sin cambio: cambiar de sucursal (y cerrar sesión, misma cuenta) sigue esperando a que se resuelva lo rechazado. */
    @Test
    fun `cambiar de sucursal sigue frenando con una operacion rechazada`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            val vm = construir(cola(rechazadas = 1), f.service)

            vm.switchVenue(StoredVenue(id = "otra-sucursal", name = "Sucursal Centro"))

            coVerify(exactly = 0) { auth.switchVenue(any()) }
            // D34: el freno sigue; el texto ya no le pide «resuélvela» a quien no puede (un mesero, por default).
            assertEquals(
                "Hay 1 operación rechazada por revisar. Pídele a un gerente que la resuelva: Más › Cambiar usuario.",
                vm.sessionGuardMessage.value,
            )
        }
    }

    /** D34: lo rechazado se cuenta aparte de lo que sólo espera a la red, con la cola y los cobros de verdad. */
    @Test
    fun `P1 cambiar de sucursal con un cobro rechazado y una ronda sin enviar dice las dos cosas`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            coEvery { f.dao.getFailedPayments() } returns listOf(cobroFallido())
            val vm = construir(cola(pendientes = 1), f.service, puedeResolver = true)

            vm.switchVenue(StoredVenue(id = "otra-sucursal", name = "Sucursal Centro"))

            coVerify(exactly = 0) { auth.switchVenue(any()) }
            assertEquals(
                "Hay 2 operaciones pendientes: 1 operación rechazada por revisar y 1 más. Sincroniza o resuelve lo demás y " +
                    "resuelve lo rechazado en Ventas › Pendientes › «Otras operaciones y revisiones anteriores» antes de cambiar de establecimiento.",
                vm.sessionGuardMessage.value,
            )
        }
    }

    /**
     * Codex r2 #2 y r3: un cobro fallido de OTRA sucursal frena (el freno es de todo el aparato). Al mesero se le dice lo mismo que con
     * uno de aquí (lo resuelve un gerente); al gerente, que se resuelve con esa sucursal abierta (no lo vería en la pantalla de ésta).
     */
    @Test
    fun `P1 un cobro fallido de otra sucursal, solo, al mesero y al gerente`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            coEvery { f.dao.getFailedPayments() } returns listOf(cobroFallido().copy(venueId = "otra-sucursal"))

            val mesero = construir(cola(), f.service)
            mesero.switchVenue(StoredVenue(id = "sucursal-centro", name = "Sucursal Centro"))
            assertEquals(
                "Hay 1 operación rechazada por revisar. Pídele a un gerente que la resuelva: Más › Cambiar usuario.",
                mesero.sessionGuardMessage.value,
            )

            val gerente = construir(cola(), f.service, puedeResolver = true)
            gerente.switchVenue(StoredVenue(id = "sucursal-centro", name = "Sucursal Centro"))
            assertEquals(
                "Hay 1 operación rechazada por revisar. Resuélvela con su sucursal abierta, en Ventas › Pendientes › " +
                    "«Otras operaciones y revisiones anteriores».",
                gerente.sessionGuardMessage.value,
            )
        }
    }

    /** Sin nada rechazado, el texto de siempre. */
    @Test
    fun `cambiar de sucursal con solo una ronda sin enviar conserva su aviso`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            cobrosSinNada(f)
            val vm = construir(cola(pendientes = 1), f.service)

            vm.switchVenue(StoredVenue(id = "otra-sucursal", name = "Sucursal Centro"))

            assertEquals(
                "Hay 1 operación offline pendiente o en conciliación. Resuélvela antes de cambiar de establecimiento.",
                vm.sessionGuardMessage.value,
            )
        }
    }

    private fun construir(syncOutbox: SyncOutbox, pagos: PaymentSyncService, puedeResolver: Boolean = false): MoreMenuViewModel {
        val tpv = mockk<TpvSettingsRepository>(relaxed = true) {
            every { terminalNavigation } returns MutableStateFlow(TerminalNavigationSettings.DEFAULT)
        }
        return MoreMenuViewModel(
            secureStorage = storage,
            authRepository = auth,
            timeEntryRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            printConfigRepository = mockk(relaxed = true),
            roleManager = mockk(relaxed = true) { every { canResolveQuarantine } returns puedeResolver },
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
            paymentSyncService = pagos,
            syncOutbox = syncOutbox,
            wasteSyncCoordinator = mockk(relaxed = true),
            bloqueoDeMerma = mockk(relaxed = true),
            pendingWasteDao = mockk(relaxed = true),
            tpvSettingsRepository = tpv,
            vigilanteDePedidos = mockk(relaxed = true),
            pedidosEnLineaRepository = mockk(relaxed = true) { coEvery { porCobrarGuardado() } returns null },
            pendingReservationDao = mockk { coEvery { all() } returns emptyList() },
            cashDrawerRepository = null,
        )
    }
}
