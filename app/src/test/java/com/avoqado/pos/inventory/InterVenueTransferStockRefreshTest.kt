package com.avoqado.pos.inventory

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferApi
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferDetail
import com.avoqado.pos.inventory.data.transfers.InterVenueTransferItem
import com.avoqado.pos.inventory.data.transfers.TransferListPage
import com.avoqado.pos.inventory.data.transfers.TransferRawMaterialRef
import com.avoqado.pos.inventory.data.transfers.TransferVenue
import com.avoqado.pos.inventory.domain.StockRefresher
import com.avoqado.pos.inventory.presentation.traslados.InterVenueTransfersViewModel
import com.avoqado.pos.pos.data.ProductsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Recibir un traslado de otra sucursal MUEVE existencias de verdad: el server
 * escribe `TRANSFER_IN` sobre `RawMaterial.currentStock`. La app sólo volvía a
 * bajar la lista de traslados, así que la mercancía recién recibida no aparecía
 * ni en inventario ni en la pantalla de cobro — el mismo defecto que el conteo
 * que no actualizaba nada.
 *
 * Aprobar/rechazar/cancelar NO mueven stock: ahí refrescar sería tráfico de más.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterVenueTransferStockRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = mockk<InterVenueTransferApi>(relaxed = true)
    private val inventoryRepository = mockk<InventoryRepository>(relaxed = true)
    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val stockRefresher = StockRefresher(inventoryRepository, productsRepository)

    private val venue = TransferVenue(id = "venue-1", name = "BAE Centro")
    private val detail = InterVenueTransferDetail(
        id = "tr-1",
        number = "TR-001",
        mode = "REQUEST",
        status = "COMPLETED",
        sourceVenueId = "venue-2",
        destinationVenueId = "venue-1",
        sourceVenue = TransferVenue(id = "venue-2", name = "CEDIS"),
        destinationVenue = venue,
        items = listOf(
            InterVenueTransferItem(
                id = "item-1",
                unit = "KILOGRAM",
                quantityRequested = "10",
                quantityDispatched = "10",
                sourceRawMaterial = TransferRawMaterialRef(id = "rm-src", name = "Champiñones"),
                destinationRawMaterial = TransferRawMaterialRef(id = "rm-dst", name = "Champiñones"),
            ),
        ),
    )

    @Before
    fun setup() {
        coEvery { api.list() } returns Result.success(TransferListPage())
        coEvery { api.get(any()) } returns Result.success(detail)
        coEvery { api.receive(any(), any()) } returns Result.success(detail)
        coEvery { api.approve(any()) } returns Result.success(detail)
    }

    private fun createViewModel(): InterVenueTransfersViewModel {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "venue-1"
        return InterVenueTransfersViewModel(
            api = api,
            secureStorage = storage,
            roleManager = mockk(relaxed = true),
            stockRefresher = stockRefresher,
        )
    }

    /** El caso de la sucursal: recibes mercancía y tiene que verse ya. */
    @Test
    fun `recibir un traslado refresca existencias y catalogo del POS`() = runTest {
        val viewModel = createViewModel()
        viewModel.openDetail("tr-1")
        viewModel.openReceive("tr-1")

        val enviado = viewModel.submitReceive("tr-1")

        assertTrue("la recepción debió aceptarse", enviado)
        coVerify(exactly = 1) { inventoryRepository.fetchStockOverview() }
        coVerify(exactly = 1) { inventoryRepository.fetchRawMaterials() }
        coVerify(exactly = 1) { productsRepository.fetchProducts() }
    }

    @Test
    fun `aprobar un traslado NO refresca existencias`() = runTest {
        val viewModel = createViewModel()

        viewModel.approve("tr-1")

        coVerify(exactly = 0) { inventoryRepository.fetchStockOverview() }
        coVerify(exactly = 0) { productsRepository.fetchProducts() }
    }
}
