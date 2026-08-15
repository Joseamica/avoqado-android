package com.avoqado.pos.inventory

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.domain.StockRefresher
import com.avoqado.pos.inventory.presentation.InventorySection
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import com.avoqado.pos.scale.ScaleSettingsRepository
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
class InventoryViewModelRefreshTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var now: Duration = Duration.ZERO
    private val repository: InventoryRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()
    private val planManager: PlanManager = mockk(relaxed = true)

    private fun buildViewModel(): InventoryViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { now }, random = { 0.5 })
        coEvery { repository.fetchStockOverview() } returns Result.success(Unit)
        coEvery { repository.fetchRawMaterials() } returns Result.success(Unit)
        coEvery { repository.fetchStockCounts() } returns Result.success(Unit)
        coEvery { repository.fetchPurchaseOrders() } returns Result.success(Unit)
        coEvery { repository.fetchSuppliers() } returns Result.success(Unit)
        coEvery { repository.fetchTransfers() } returns Result.success(Unit)
        return InventoryViewModel(
            repository = repository,
            planManager = planManager,
            scaleSettingsRepository = mockk<ScaleSettingsRepository>(relaxed = true),
            stockRefresher = mockk<StockRefresher>(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    @Test
    fun `autoRefresh en OVERVIEW pide overview y materias primas y respeta el TTL`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        now += 10.seconds
        vm.autoRefresh() // dentro del TTL: no re-pide
        coVerify(exactly = 1) { repository.fetchStockOverview() }
        coVerify(exactly = 1) { repository.fetchRawMaterials() }
        coVerify(exactly = 0) { repository.fetchStockCounts() }
    }

    @Test
    fun `con un conteo en curso ni el gesto manual refresca`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.startCycleCount() // showCounting = true → guard de stock (spec §4.5)
        vm.manualRefresh()
        coVerify(exactly = 0) { repository.fetchStockOverview() }
        coVerify(exactly = 0) { repository.fetchRawMaterials() }
    }

    @Test
    fun `el gesto despacha por la seccion activa`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.selectSection(InventorySection.COUNTS) // carga lazy: 1er fetch de counts
        vm.manualRefresh() // gesto en COUNTS: 2o fetch de counts, cero de overview
        coVerify(exactly = 2) { repository.fetchStockCounts() }
        coVerify(exactly = 0) { repository.fetchStockOverview() }
    }
}
