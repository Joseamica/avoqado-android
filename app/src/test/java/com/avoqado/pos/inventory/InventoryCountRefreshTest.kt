package com.avoqado.pos.inventory

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.domain.StockRefresher
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.scale.ScaleSettingsRepository
import io.mockk.coEvery
import org.junit.Assert.assertEquals
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Al confirmar un conteo, "Descripción general" tiene que quedar con el stock
 * NUEVO. El server ya aplicaba el ajuste — medido en la DB local el 2026-08-12:
 * Cerveza Corona 89 → 10 → 7 con sus `InventoryMovement` de tipo COUNT — pero la
 * app sólo volvía a pedir la LISTA DE CONTEOS, así que la pantalla se quedaba
 * con el stock que bajó al abrir (89) hasta reiniciar la app. No hay
 * pull-to-refresh que lo salve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryCountRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<InventoryRepository>(relaxed = true)
    private val scaleSettingsRepository = mockk<ScaleSettingsRepository>(relaxed = true)

    /** Caché del POS: es la que decide si un producto sale "Agotado". */
    private val productsRepository = mockk<ProductsRepository>(relaxed = true)

    /** El refresher REAL sobre repos falsos: así el test prueba el cableado
     *  completo y no que "se llamó a un mock". */
    private val stockRefresher = StockRefresher(repository, productsRepository)

    private val serverCount = StockCount(
        id = "count-1",
        type = StockCountType.FULL,
        items = listOf(
            StockCountItem(
                id = "item-1",
                productId = "prod-corona",
                productName = "Cerveza Corona",
                expected = 89.0,
            ),
        ),
    )

    @Before
    fun setup() {
        every { repository.stockItems } returns MutableStateFlow(emptyList<StockItem>())
        every { repository.countableRawMaterials } returns MutableStateFlow(emptyList<StockItem>())
        every { repository.stockCounts } returns MutableStateFlow(emptyList<StockCount>())
        every { repository.purchaseOrders } returns MutableStateFlow(emptyList())
        every { repository.transfers } returns MutableStateFlow(emptyList())
        every { repository.suppliers } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        coEvery { repository.createStockCount(any(), any(), any()) } returns Result.success(serverCount)
        coEvery { repository.updateStockCount(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.confirmStockCount(any()) } returns Result.success(Unit)
    }

    private fun createViewModel(): InventoryViewModel {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.planTier } returns null
        every { storage.planExempt } returns false
        val refreshGateFactory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns
            com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return InventoryViewModel(repository, PlanManager(storage), scaleSettingsRepository, stockRefresher, refreshGateFactory)
    }

    /**
     * El caso que impedía COBRAR: repones existencia contándola y el POS sigue
     * marcando el producto "Agotado" porque su catálogo nunca se vuelve a bajar.
     */
    @Test
    fun `confirmar un conteo refresca el catalogo del POS`() = runTest {
        val viewModel = createViewModel()
        viewModel.startFullCount()
        viewModel.updateCountedText("20")
        viewModel.finishCounting()

        viewModel.confirmCount()

        coVerify(exactly = 1) { productsRepository.fetchProducts() }
    }

    /** Recibir mercancía también repone: mismo riesgo de "Agotado" falso. */
    @Test
    fun `recibir mercancia refresca el catalogo del POS`() = runTest {
        coEvery { repository.receivePurchaseOrder(any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.receivePurchaseOrder("po-1", emptyList())

        coVerify(exactly = 1) { productsRepository.fetchProducts() }
    }

    /** El conteo cambió el stock ⇒ la descripción general se vuelve a pedir. */
    @Test
    fun `confirmar un conteo completo refresca la descripcion general`() = runTest {
        val viewModel = createViewModel()
        viewModel.startFullCount()
        viewModel.updateCountedText("7")
        viewModel.finishCounting()

        viewModel.confirmCount()

        // El init ya NO fetchea (la carga inicial la dispara la UI vía el
        // RefreshGate): la única llamada esperada es la de después de confirmar.
        coVerify(exactly = 1) { repository.fetchStockOverview() }
    }

    /** Los insumos viven en su propio catálogo y también se ajustan al confirmar. */
    @Test
    fun `confirmar un conteo completo refresca los insumos`() = runTest {
        val viewModel = createViewModel()
        viewModel.startFullCount()
        viewModel.updateCountedText("4")
        viewModel.finishCounting()

        viewModel.confirmCount()

        coVerify(exactly = 1) { repository.fetchRawMaterials() }
    }

    /** Un conteo cíclico aplica el mismo ajuste ⇒ mismo refresco. */
    @Test
    fun `confirmar un conteo ciclico refresca la descripcion general`() = runTest {
        val viewModel = createViewModel()
        viewModel.startCycleCount()
        viewModel.addItemsToCycleCount(
            listOf(StockItem(id = "prod-corona", name = "Cerveza Corona", onHand = 89.0)),
        )
        viewModel.updateCountedText("10")
        viewModel.finishCounting()

        viewModel.confirmCount()

        coVerify(exactly = 1) { repository.fetchStockOverview() }
    }

    /** Recibir mercancía SUBE el stock: misma pantalla, mismo refresco obligatorio. */
    @Test
    fun `recibir mercancia refresca la descripcion general`() = runTest {
        coEvery { repository.receivePurchaseOrder(any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.receivePurchaseOrder("po-1", emptyList())

        coVerify(exactly = 1) { repository.fetchStockOverview() }
    }

    /** Un conteo físico no puede ser negativo: se avisa y NO se guarda la línea. */
    @Test
    fun `una cantidad contada negativa no se guarda y avisa`() = runTest {
        val viewModel = createViewModel()
        viewModel.startFullCount()
        viewModel.updateCountedText("-5")
        viewModel.finishCounting()

        assertEquals("La cantidad contada no puede ser negativa", viewModel.errorMessage.value)
        // La línea nunca se marcó como contada → confirmar no manda nada negativo
        viewModel.confirmCount()
        coVerify(exactly = 0) { repository.updateStockCount(any(), match { items -> items.any { it.counted < 0 } }, any()) }
    }

    /** Si el conteo NO se pudo confirmar, no se finge un refresco. */
    @Test
    fun `un conteo que falla al confirmar no refresca`() = runTest {
        coEvery { repository.confirmStockCount(any()) } returns Result.failure(Exception("boom"))
        val viewModel = createViewModel()
        viewModel.startFullCount()
        viewModel.updateCountedText("7")
        viewModel.finishCounting()

        viewModel.confirmCount()

        // Sin fetch de init y con la confirmación fallando: CERO refetches.
        coVerify(exactly = 0) { repository.fetchStockOverview() }
    }
}
