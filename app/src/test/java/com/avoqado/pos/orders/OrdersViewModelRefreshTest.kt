package com.avoqado.pos.orders

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.presentation.OrdersViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelRefreshTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var now: Duration = Duration.ZERO
    private val repository: OrdersRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): OrdersViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { now }, random = { 0.5 })
        coEvery { repository.loadOrders(any(), any(), any(), any()) } returns Result.success(Unit)
        return OrdersViewModel(repository = repository, refreshGateFactory = factory)
    }

    @Test
    fun `autoRefresh dentro del TTL no vuelve a pedir`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        now += 10.seconds
        vm.autoRefresh()
        coVerify(exactly = 1) {
            repository.loadOrders(page = 1, search = null, status = null, append = false)
        }
    }

    @Test
    fun `buscar rapido tras abrir invalida y pide con el termino`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        vm.updateSearch("mesa 4") // inmediato: ejercita la ventana del drop(1)
        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()
        coVerify(exactly = 1) {
            repository.loadOrders(page = 1, search = "mesa 4", status = null, append = false)
        }
    }

    @Test
    fun `cambiar el filtro de estado invalida el TTL y re-pide`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        vm.setStatusFilter("OPEN") // dentro del TTL, pero es identidad nueva
        coVerify(exactly = 1) {
            repository.loadOrders(page = 1, search = null, status = "OPEN", append = false)
        }
    }
}
