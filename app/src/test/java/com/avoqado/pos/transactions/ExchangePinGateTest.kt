package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.ActionVisibility
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.*
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangePinGateTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))
    private val repository = mockk<TransactionRepository>(relaxed = true)
    private val roles = mockk<RoleManager>(relaxed = true)
    private val pin = mockk<ManagerOverrideCoordinator>()
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val settings = mockk<TpvSettingsRepository>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val sale = Transaction("payment", exchangeable = true, payoutOptions = emptyList(), exchange = ExchangeLinks(0, emptyList()))

    private fun vm(canRefund: Boolean = false, coordinator: ManagerOverrideCoordinator = pin): TransactionsViewModel {
        every { pin.discardPreauthorized(any()) } just Runs
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        coEvery { repository.fetchTransactions(any(), any()) } returns Result.success(Unit)
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns sale
        every { settings.exchangesEnabled } returns MutableStateFlow(true)
        every { settings.managerPinOverrideEnabled } returns MutableStateFlow(true)
        every { roles.canIssueRefund } returns canRefund
        every { roles.visibilityOf(any(), any()) } answers {
            if (firstArg<Boolean>()) ActionVisibility.ALLOWED else ActionVisibility.LOCKED
        }
        every { monitor.isOnline() } returns true
        every { storage.userId } returns "actor"
        every { storage.venueId } returns "venue"
        val factory = mockk<RefreshGateFactory> {
            every { create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        }
        return TransactionsViewModel(repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            roles, settings, coordinator, mockk(relaxed = true), mockk(relaxed = true), storage, factory, monitor)
            .also { it.selectTransaction(sale.id) }
    }

    @Test fun `permission opens without PIN and cancelled PIN never opens`() = runTest(scheduler) {
        val allowed = vm(canRefund = true)
        allowed.onExchangeTapped()
        assertEquals(sale, allowed.exchangeTransaction.value)
        coVerify(exactly = 0) { pin.preauthorize(any()) }
        val locked = vm()
        coEvery { pin.preauthorize("payments:refund") } returns false
        locked.onExchangeTapped()
        assertNull(locked.exchangeTransaction.value)
    }

    @Test fun `PIN is requested before opening and stale authorization cannot open another sale`() = runTest(scheduler) {
        val decision = CompletableDeferred<Boolean>()
        coEvery { pin.preauthorize("payments:refund") } coAnswers { decision.await() }
        val vm = vm()
        vm.onExchangeTapped()
        assertNull(vm.exchangeTransaction.value)
        vm.clearSelection()
        decision.complete(true)
        assertNull(vm.exchangeTransaction.value)
        coVerify(exactly = 1) { pin.preauthorize("payments:refund") }
    }

    @Test fun `offline and disabled entries never ask PIN or open`() = runTest(scheduler) {
        val vm = vm()
        every { monitor.isOnline() } returns false
        vm.onExchangeTapped()
        assertNull(vm.exchangeTransaction.value)
        every { monitor.isOnline() } returns true
        every { settings.exchangesEnabled } returns MutableStateFlow(false)
        vm.onExchangeTapped()
        assertNull(vm.exchangeTransaction.value)
        coVerify(exactly = 0) { pin.preauthorize(any()) }
    }

    @Test fun `successful PIN opens the same sale`() = runTest(scheduler) {
        val vm = vm()
        coEvery { pin.preauthorize("payments:refund") } returns true
        vm.onExchangeTapped()
        assertEquals(sale, vm.exchangeTransaction.value)
    }

    @Test fun `linked sale permission denial shows the permission message and keeps the current sale`() = runTest(scheduler) {
        val vm = vm(canRefund = true)
        coEvery { repository.paymentIdForExchangeOrder("linked", "actor", "venue") } throws SecurityException()
        vm.openExchangeOrder("linked")
        assertEquals("No tienes permiso para abrir la venta vinculada", vm.exchangeMessage.value)
        assertEquals(sale, vm.selectedTransaction.value)
        vm.clearExchangeMessage()
        coEvery { repository.paymentIdForExchangeOrder("linked", "actor", "venue") } returns null
        vm.openExchangeOrder("linked")
        assertEquals(com.avoqado.pos.transactions.presentation.exchange.TextosCambio.VINCULO_NO_DISPONIBLE,
            vm.exchangeMessage.value)
    }
    @Test fun `closing exchange discards real PIN token on every exit`() = runTest(scheduler) {
        for (exit in listOf("dismiss", "select", "clear")) {
            val overrideRepository = mockk<PermissionOverrideRepository> {
                coEvery { requestToken(any(), any(), any()) } returns OverrideResult.Granted("exchange-only", "Manager")
            }
            val coordinator = ManagerOverrideCoordinator(overrideRepository)
            val model = vm(coordinator = coordinator)
            model.onExchangeTapped()
            assertNotNull(coordinator.prompt.value)
            coordinator.submitPin("venue", "1234")
            runCurrent()
            assertEquals(sale, model.exchangeTransaction.value)
            when (exit) {
                "select" -> model.selectTransaction("other")
                "clear" -> model.clearSelection()
                else -> model.dismissExchange()
            }
            assertNull(model.exchangeTransaction.value)
            val next = backgroundScope.async(UnconfinedTestDispatcher(scheduler)) {
                coordinator.preauthorize("payments:refund")
            }
            assertFalse("A different refund must request its own PIN: $exit", next.isCompleted)
            assertEquals("payments:refund", coordinator.prompt.value?.permission)
            coordinator.cancel()
            assertFalse(next.await())
        }
    }
    @Test fun `PIN granted after selection closes does not leave a token`() = runTest(scheduler) {
        val overrideRepository = mockk<PermissionOverrideRepository> {
            coEvery { requestToken(any(), any(), any()) } returns OverrideResult.Granted("stale", "Manager")
        }
        val coordinator = ManagerOverrideCoordinator(overrideRepository)
        val model = vm(coordinator = coordinator)
        model.onExchangeTapped()
        assertNotNull(coordinator.prompt.value)
        model.clearSelection()
        coordinator.submitPin("venue", "1234")
        runCurrent()
        assertNull(model.exchangeTransaction.value)
        val next = backgroundScope.async(UnconfinedTestDispatcher(scheduler)) {
            coordinator.preauthorize("payments:refund")
        }
        assertFalse(next.isCompleted)
        assertEquals("payments:refund", coordinator.prompt.value?.permission)
        coordinator.cancel()
        assertFalse(next.await())
    }

}
