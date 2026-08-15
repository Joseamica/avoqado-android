package com.avoqado.pos.articles

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
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
class ArticlesViewModelRefreshTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var now: Duration = Duration.ZERO
    private val repository: ArticlesRepository = mockk(relaxed = true)
    private val factory: RefreshGateFactory = mockk()

    private fun buildViewModel(): ArticlesViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { now }, random = { 0.5 })
        coEvery { repository.fetchProducts() } returns Result.success(Unit)
        coEvery { repository.fetchCategories() } returns Result.success(Unit)
        coEvery { repository.fetchModifierGroups() } returns Result.success(Unit)
        coEvery { repository.fetchDiscounts() } returns Result.success(Unit)
        coEvery { repository.fetchCoupons() } returns Result.success(Unit)
        coEvery { repository.fetchCreditPacks() } returns Result.success(Unit)
        coEvery { repository.fetchProductOptions() } returns Result.success(Unit)
        return ArticlesViewModel(
            repository = repository,
            planManager = mockk<PlanManager>(relaxed = true),
            refreshGateFactory = factory,
        )
    }

    @Test
    fun `init NO fetchea - la carga inicial la dispara la UI via el gate`() = runTest(scheduler) {
        buildViewModel()
        coVerify(exactly = 0) { repository.fetchProducts() }
        coVerify(exactly = 0) { repository.fetchCategories() }
    }

    @Test
    fun `autoRefresh en PRODUCTS pide productos y categorias y respeta el TTL`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.autoRefresh()
        now += 10.seconds
        vm.autoRefresh() // dentro del TTL: no re-pide
        coVerify(exactly = 1) { repository.fetchProducts() }
        coVerify(exactly = 1) { repository.fetchCategories() }
    }

    @Test
    fun `el gesto despacha por la seccion activa`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.selectSection(ArticleSection.DISCOUNTS) // carga eager: 1er fetch
        vm.manualRefresh() // gesto en DISCOUNTS: 2o fetch, cero de productos
        coVerify(exactly = 2) { repository.fetchDiscounts() }
        coVerify(exactly = 0) { repository.fetchProducts() }
    }
}
