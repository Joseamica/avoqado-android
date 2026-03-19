package com.avoqado.pos.articles

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ArticlesRepository>(relaxed = true)

    @Before
    fun setup() {
        every { repository.products } returns MutableStateFlow(emptyList())
        every { repository.categories } returns MutableStateFlow(emptyList())
        every { repository.modifierGroups } returns MutableStateFlow(emptyList())
        every { repository.discounts } returns MutableStateFlow(emptyList())
        every { repository.coupons } returns MutableStateFlow(emptyList())
        every { repository.creditPacks } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.errorMessage } returns MutableStateFlow(null)
    }

    private fun createViewModel() = ArticlesViewModel(repository)

    // MARK: - Initial State

    @Test
    fun `initial section is PRODUCTS`() {
        val viewModel = createViewModel()
        assertEquals(ArticleSection.PRODUCTS, viewModel.selectedSection.value)
    }

    @Test
    fun `initial searchQuery is empty`() {
        val viewModel = createViewModel()
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `initial isSaving is false`() {
        val viewModel = createViewModel()
        assertEquals(false, viewModel.isSaving.value)
    }

    // MARK: - Section Selection

    @Test
    fun `selectSection changes selectedSection`() {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.DISCOUNTS)
        assertEquals(ArticleSection.DISCOUNTS, viewModel.selectedSection.value)
    }

    @Test
    fun `selectSection PRODUCTS fetches products and categories`() = runTest {
        val viewModel = createViewModel()
        // Reset interactions from init
        viewModel.selectSection(ArticleSection.CATEGORIES) // switch away first
        viewModel.selectSection(ArticleSection.PRODUCTS)

        coVerify(atLeast = 1) { repository.fetchProducts() }
        coVerify(atLeast = 1) { repository.fetchCategories() }
    }

    @Test
    fun `selectSection CATEGORIES fetches only categories`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.CATEGORIES)

        coVerify(atLeast = 1) { repository.fetchCategories() }
    }

    @Test
    fun `selectSection MODIFIERS fetches modifier groups`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.MODIFIERS)

        coVerify(atLeast = 1) { repository.fetchModifierGroups() }
    }

    @Test
    fun `selectSection DISCOUNTS fetches discounts`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.DISCOUNTS)

        coVerify(atLeast = 1) { repository.fetchDiscounts() }
    }

    @Test
    fun `selectSection COUPONS fetches coupons and discounts`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.COUPONS)

        coVerify(atLeast = 1) { repository.fetchCoupons() }
        coVerify(atLeast = 1) { repository.fetchDiscounts() }
    }

    @Test
    fun `selectSection CREDIT_PACKS fetches credit packs and products`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.CREDIT_PACKS)

        coVerify(atLeast = 1) { repository.fetchCreditPacks() }
        coVerify(atLeast = 1) { repository.fetchProducts() }
    }

    // MARK: - Search

    @Test
    fun `updateSearch changes searchQuery`() {
        val viewModel = createViewModel()
        viewModel.updateSearch("pizza")
        assertEquals("pizza", viewModel.searchQuery.value)
    }

    @Test
    fun `updateSearch with empty string clears searchQuery`() {
        val viewModel = createViewModel()
        viewModel.updateSearch("pizza")
        viewModel.updateSearch("")
        assertEquals("", viewModel.searchQuery.value)
    }

    // MARK: - Products CRUD

    @Test
    fun `deleteProduct calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteProduct("prod-123")

        coVerify { repository.deleteProduct("prod-123") }
    }

    // MARK: - Categories CRUD

    @Test
    fun `createCategory calls repository with correct payload`() = runTest {
        val viewModel = createViewModel()
        viewModel.createCategory(name = "Bebidas", description = "Cold drinks", color = "#FF0000")

        coVerify { repository.createCategory(any()) }
    }

    @Test
    fun `deleteCategory calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteCategory("cat-456")

        coVerify { repository.deleteCategory("cat-456") }
    }

    // MARK: - Modifier Groups CRUD

    @Test
    fun `deleteModifierGroup calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteModifierGroup("group-789")

        coVerify { repository.deleteModifierGroup("group-789") }
    }

    @Test
    fun `createModifierGroup calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.createModifierGroup(
            name = "Salsas",
            required = false,
            allowMultiple = true,
            modifiers = emptyList(),
        )

        coVerify { repository.createModifierGroup(any()) }
    }

    // MARK: - Discounts CRUD

    @Test
    fun `deleteDiscount calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteDiscount("disc-abc")

        coVerify { repository.deleteDiscount("disc-abc") }
    }

    // MARK: - Coupons CRUD

    @Test
    fun `deleteCoupon calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteCoupon("coup-xyz")

        coVerify { repository.deleteCoupon("coup-xyz") }
    }

    // MARK: - Credit Packs CRUD

    @Test
    fun `deleteCreditPack calls repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteCreditPack("pack-001")

        coVerify { repository.deleteCreditPack("pack-001") }
    }

    // MARK: - Raw Materials

    @Test
    fun `searchRawMaterials does nothing for query shorter than 2 chars`() = runTest {
        val viewModel = createViewModel()
        viewModel.searchRawMaterials("a")

        coVerify(exactly = 0) { repository.searchRawMaterials(any()) }
    }

    @Test
    fun `searchRawMaterials calls repository for 2+ char query`() = runTest {
        val viewModel = createViewModel()
        viewModel.searchRawMaterials("to")

        coVerify { repository.searchRawMaterials("to") }
    }

    @Test
    fun `clearRawMaterialResults clears rawMaterialResults`() {
        val viewModel = createViewModel()
        viewModel.clearRawMaterialResults()
        assertEquals(emptyList<Any>(), viewModel.rawMaterialResults.value)
    }

    // MARK: - Refresh

    @Test
    fun `refresh calls loadSectionData for current section`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSection(ArticleSection.MODIFIERS)
        viewModel.refresh()

        coVerify(atLeast = 2) { repository.fetchModifierGroups() }
    }
}
