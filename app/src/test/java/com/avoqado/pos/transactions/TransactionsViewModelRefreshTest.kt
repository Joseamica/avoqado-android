package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
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
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelRefreshTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var now: Duration = Duration.ZERO
    private val repository: TransactionRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): TransactionsViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { now }, random = { 0.5 })
        coEvery { repository.fetchTransactions(any(), any()) } returns Result.success(Unit)
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        return TransactionsViewModel(
            repository = repository,
            refundRepository = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true),
            terminalPaymentService = mockk(relaxed = true),
            roleManager = mockk(relaxed = true),
            tpvSettingsRepository = mockk(relaxed = true),
            orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    @Test
    fun `autoRefresh dentro del TTL no vuelve a pedir`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        now += 10.seconds
        vm.autoRefresh()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
    }

    @Test
    fun `con la devolucion abierta ni el gesto refresca`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.showRefundSheet()
        vm.manualRefresh()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `buscar invalida el TTL y vuelve a pedir con el termino`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        vm.setSearchText("cafe")
        scheduler.advanceTimeBy(500) // pasa el debounce de 400 ms
        scheduler.runCurrent()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = "cafe") }
    }

    @Test
    fun `invalidateAndRefresh procede aunque la devolucion este abierta`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.showRefundSheet()
        vm.invalidateAndRefresh()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
    }

    @Test
    fun `el refund del detalle tambien bloquea el gesto manual`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.setRefundFlowActive(true)
        vm.manualRefresh()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }
}
