package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.coEvery
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
import kotlin.time.Duration

/**
 * 🔴 EL CANDADO NO PUEDE MENTIR, Y EL PIN NO PUEDE LLEGAR TARDE.
 *
 * Medido en la D3 el 2026-08-17 contra el backend real: a un CAJERO se le
 * pintaba el candado en "Emitir reembolso", llenaba importe y motivo, tocaba
 * Reembolsar… **y el reembolso se ejecutaba sin pedir ningún PIN** (quedó un
 * `Payment` de CASH -50.00 y la lista mostró $-50.00). El server SÍ le da
 * `payments:refund` al CASHIER; la app decidía por una lista de roles.
 *
 * Dos defectos, dos mitades de este archivo:
 *
 * 1. **El candado mentía.** Se decide por PERMISO espejado por nombre exacto.
 *    Con el permiso, ni candado ni PIN.
 * 2. **El PIN llegaba tarde.** Salía cuando el server rechazaba, o sea después
 *    de llenar todo el formulario. Cuando la app YA SABE que está bloqueado, se
 *    pide AL TOCAR — y si cancelan, el formulario no se abre.
 *
 * 🔴 Lo que NO cambia: sin candado, la red de seguridad sigue siendo el 403 del
 * server (`ForbiddenInterceptor`), que cubre todo lo que el cliente no puede
 * anticipar. Adelantar el PIN no la reemplaza.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefundPinGateTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val repository: TransactionRepository = mockk(relaxed = true)
    private val roleManager: RoleManager = mockk()
    private val overrideCoordinator: ManagerOverrideCoordinator = mockk()
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): TransactionsViewModel {
        every { factory.create(any(), any()) } returns
            RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        coEvery { repository.fetchTransactions(any(), any()) } returns Result.success(Unit)
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        return TransactionsViewModel(
            repository = repository,
            refundRepository = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true),
            terminalPaymentService = mockk(relaxed = true),
            roleManager = roleManager,
            tpvSettingsRepository = mockk(relaxed = true),
            managerOverrideCoordinator = overrideCoordinator,
            orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    /**
     * El CAJERO tiene `payments:refund` en el server: el botón va SIN candado y
     * tocarlo abre el formulario de una vez. Pedirle un PIN sería inventarle un
     * permiso que sí tiene.
     */
    @Test
    fun `con el permiso, tocar abre el formulario y NO se pide PIN`() = runTest(scheduler) {
        every { roleManager.canIssueRefund } returns true

        val vm = buildViewModel()
        vm.onIssueRefundTapped()

        assertTrue("el formulario debió abrirse", vm.issueRefundSheetVisible.value)
        coVerify(exactly = 0) { overrideCoordinator.preauthorize(any()) }
    }

    /**
     * Un rol SIN el permiso (WAITER, HOST, VIEWER) ve el candado: el PIN se pide
     * ANTES de abrir nada, para no perder el trabajo de llenar el formulario si
     * el encargado no está cerca.
     */
    @Test
    fun `con candado, el PIN se pide ANTES de abrir el formulario`() = runTest(scheduler) {
        every { roleManager.canIssueRefund } returns false
        coEvery { overrideCoordinator.preauthorize("payments:refund") } returns true

        val vm = buildViewModel()
        vm.onIssueRefundTapped()

        coVerify(exactly = 1) { overrideCoordinator.preauthorize("payments:refund") }
        assertTrue("autorizado: el formulario debió abrirse", vm.issueRefundSheetVisible.value)
    }

    /** Si cancela el PIN, no se abre nada: ni formulario a medio llenar. */
    @Test
    fun `si cancela el PIN el formulario no se abre`() = runTest(scheduler) {
        every { roleManager.canIssueRefund } returns false
        coEvery { overrideCoordinator.preauthorize("payments:refund") } returns false

        val vm = buildViewModel()
        vm.onIssueRefundTapped()

        coVerify(exactly = 1) { overrideCoordinator.preauthorize("payments:refund") }
        assertFalse("cancelado: el formulario NO debe abrirse", vm.issueRefundSheetVisible.value)
    }

    /**
     * 🔴 El permiso viaja por nombre EXACTO. Un typo aquí y el server nunca lo
     * reconoce: el PIN de un gerente legítimo se rechaza sin que nadie entienda
     * por qué. Espejo de `payments:refund` en
     * `avoqado-server/src/lib/permissions.ts`.
     */
    @Test
    fun `el permiso que se pide es exactamente el del server`() = runTest(scheduler) {
        every { roleManager.canIssueRefund } returns false
        coEvery { overrideCoordinator.preauthorize(any()) } returns false

        buildViewModel().onIssueRefundTapped()

        coVerify { overrideCoordinator.preauthorize("payments:refund") }
    }

    /** Cerrar el formulario lo cierra de verdad (y deja de bloquear el refresh). */
    @Test
    fun `cerrar el formulario lo esconde`() = runTest(scheduler) {
        every { roleManager.canIssueRefund } returns true

        val vm = buildViewModel()
        vm.onIssueRefundTapped()
        vm.dismissIssueRefundSheet()

        assertFalse(vm.issueRefundSheetVisible.value)
    }
}
