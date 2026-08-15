package com.avoqado.pos.reports

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.presentation.ReportsViewModel
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
class ReportsViewModelRefreshTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var now: Duration = Duration.ZERO
    private val repository: ReportsRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): ReportsViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { now }, random = { 0.5 })
        coEvery { repository.loadReport(any(), any(), any()) } returns Result.success(Unit)
        return ReportsViewModel(
            repository = repository,
            planManager = mockk<PlanManager>(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    @Test
    fun `init NO fetchea y el primer autoRefresh si`() = runTest(scheduler) {
        val vm = buildViewModel()
        coVerify(exactly = 0) { repository.loadReport(any(), any(), any()) }
        vm.autoRefresh()
        coVerify(exactly = 1) { repository.loadReport(any(), any(), any()) }
    }

    @Test
    fun `autoRefresh dentro del TTL no vuelve a pedir`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        now += 10.seconds
        vm.autoRefresh()
        coVerify(exactly = 1) { repository.loadReport(any(), any(), any()) }
    }

    @Test
    fun `aplicar fechas personalizadas invalida el TTL y re-pide`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        vm.applyCustomDates() // dentro del TTL, pero es identidad nueva (§4.4)
        coVerify(exactly = 2) { repository.loadReport(any(), any(), any()) }
    }
}
