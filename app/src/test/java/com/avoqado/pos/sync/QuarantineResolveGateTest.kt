package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PermisosRealesDelServer
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 LA CUARENTENA NO ES UN REEMBOLSO, Y NO PUEDE COLGAR DE SU PERMISO.
 *
 * La pantalla de cuarentena tiene CUATRO acciones —descartar una operación
 * rechazada, reintentar un cobro fallido, descartar un cobro fallido y descartar
 * una acción de reserva— y las cuatro terminan en un `DELETE` de la base LOCAL
 * (`dao.dismiss`, `dao.deleteFailed`, `pendingDao.delete`). **No hay endpoint, y
 * por lo tanto no hay permiso del server que las juzgue: el gate del cliente es
 * el ÚNICO gate que existe.**
 *
 * Colgarlas de `canIssueRefund` funcionó mientras ese gate era una lista de
 * roles (MANAGER+). Al espejarlo de `payments:refund` —arreglo correcto y medido
 * en hardware para el reembolso— el CAJERO se llevó de regalo la cuarentena, que
 * es donde se BORRA PARA SIEMPRE del aparato el registro de un cobro que no
 * cuadró. Es la misma enfermedad que ese arreglo vino a curar (el cliente
 * inventando una regla) pero en la dirección que ABRE en vez de cerrar.
 *
 * Estos tests fijan que los dos gates son INDEPENDIENTES.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuarantineResolveGateTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val syncOutbox: SyncOutbox = mockk(relaxed = true)
    private val secureStorage: SecureStorage = mockk(relaxed = true)
    private val paymentSyncService: PaymentSyncService = mockk(relaxed = true)
    private val reservationRepository: ReservationRepository = mockk(relaxed = true)

    private fun buildViewModel(rol: String, permisos: List<String>): QuarantineViewModel {
        every { syncOutbox.rejectedCount } returns MutableStateFlow(0)
        every { secureStorage.userRole } returns rol
        every { secureStorage.venuePermissions } returns permisos
        every { secureStorage.venueId } returns "venue-1"
        return QuarantineViewModel(
            syncOutbox = syncOutbox,
            secureStorage = secureStorage,
            paymentSyncService = paymentSyncService,
            reservationRepository = reservationRepository,
            roleManager = RoleManager(secureStorage),
        )
    }

    /**
     * 🔴 El caso medido: el server SÍ le da `payments:refund` al CASHIER. Eso lo
     * habilita para REEMBOLSAR, no para borrar del aparato el rastro de un cobro
     * que no cuadró.
     */
    @Test
    fun `el CAJERO no resuelve la cuarentena aunque el server le de payments refund`() = runTest(scheduler) {
        val vm = buildViewModel("CASHIER", PermisosRealesDelServer.CASHIER)

        assertTrue(
            "premisa del test: el server sí le da payments:refund al cajero",
            PermisosRealesDelServer.CASHIER.contains("payments:refund"),
        )
        assertFalse("la cuarentena no puede abrirse por el permiso de reembolso", vm.canResolve)
    }

    /**
     * Y no basta con esconder el botón: `dismissPayment` borra la fila con
     * `dao.deleteFailed(id)`. Si el guard no está en el ViewModel, cualquier
     * camino que llegue a llamarlo (un tap en una recomposición vieja, un test de
     * UI, un deep link futuro) borra el registro igual.
     */
    @Test
    fun `y descartar un cobro fallido no borra nada`() = runTest(scheduler) {
        val vm = buildViewModel("CASHIER", PermisosRealesDelServer.CASHIER)

        vm.dismissPayment("pago-1")

        coVerify(exactly = 0) { paymentSyncService.dismissFailedPayment(any()) }
    }

    /** Las otras tres acciones locales, por el mismo gate. */
    @Test
    fun `ni descartar un rechazo ni una accion de reserva ni reintentar`() = runTest(scheduler) {
        val vm = buildViewModel("CASHIER", PermisosRealesDelServer.CASHIER)

        vm.dismiss("intent-1")
        vm.dismissReservationAction(7L)
        vm.retryPayment("pago-1")

        coVerify(exactly = 0) { syncOutbox.dismissRejected(any(), any()) }
        coVerify(exactly = 0) { reservationRepository.dismissQuarantined(any()) }
        coVerify(exactly = 0) { paymentSyncService.retryFailedPayment(any()) }
    }

    /**
     * 🔴 El test que separa de verdad los dos gates: un Permission Set puede
     * darle `payments:refund` a CUALQUIER rol. Eso mueve el reembolso — y NADA
     * más. Si este test se pone verde por accidente, es que alguien volvió a
     * colgar la cuarentena del permiso equivocado.
     */
    @Test
    fun `un MESERO con payments refund concedido a mano reembolsa pero no resuelve`() = runTest(scheduler) {
        val permisos = PermisosRealesDelServer.WAITER + "payments:refund"
        val vm = buildViewModel("WAITER", permisos)

        assertTrue("el permiso concedido sí debe habilitar el reembolso", RoleManager(secureStorage).canIssueRefund)
        assertFalse("pero no la cuarentena", vm.canResolve)
    }

    /** El gerente sigue resolviendo, con la lista poblada… */
    @Test
    fun `el GERENTE resuelve con la lista de permisos poblada`() = runTest(scheduler) {
        val vm = buildViewModel("MANAGER", PermisosRealesDelServer.CASHIER)

        assertTrue(vm.canResolve)
    }

    /** …y también con la cache vacía, que es una sesión vieja o un server que no la manda. */
    @Test
    fun `con la cache de permisos vacia sigue siendo de gerente para arriba`() = runTest(scheduler) {
        assertTrue(buildViewModel("OWNER", emptyList()).canResolve)
        assertFalse(buildViewModel("CASHIER", emptyList()).canResolve)
        assertFalse(buildViewModel("WAITER", emptyList()).canResolve)
    }
}
