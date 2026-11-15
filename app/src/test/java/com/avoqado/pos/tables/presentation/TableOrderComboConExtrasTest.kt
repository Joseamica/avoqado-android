package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableRoundDraftStore
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Mesas: `addPromotion` guarda los extras de cada pieza y su marca «manda su lista de extras». */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderComboConExtrasTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()
    private val productsRepository = mockk<com.avoqado.pos.pos.data.ProductsRepository>(relaxed = true)
    private val alMomento = ServiceCourseSnapshot("immediate", "Al momento", "IMMEDIATE")
    private val queso = SelectedModifier("ge", "Extras", "queso", "Queso", 1500)

    private val combo = Promotion(
        id = "promo-uno", name = "Hamburguesa completa", pricingMode = "FIXED_TOTAL", priceCents = 18000,
        groups = listOf(
            PromotionGroup(
                id = "g-ham", name = "Hamburguesa",
                options = listOf(PromotionOption(id = "o-ham", productId = "p-ham", productName = "Hamburguesa", productPriceCents = 18000)),
            ),
        ),
    )

    private fun vm(): TableOrderViewModel {
        every { productsRepository.products } returns MutableStateFlow(listOf(Product(id = "p-ham", name = "Hamburguesa", priceValue = 180.0)))
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList()))
        every { syncOutbox.acks } returns MutableSharedFlow()
        every { syncOutbox.pendingCount } returns MutableStateFlow(0)
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        val printConfig = mockk<PrintConfigRepository>(relaxed = true)
        every { printConfig.getCurrentConfig() } returns PrintConfig()
        tableSession.start(
            TableSession.Active(
                tableId = "t1", tableNumber = "5", areaName = null, orderId = "o1", orderNumber = "ORD-1",
                version = 3, totalCents = 0, mode = TableSession.Mode.ORDERING,
            ),
        )
        val root = java.nio.file.Files.createTempDirectory("combo-extras").toFile()
        return TableOrderViewModel(
            repository = repository, tableSession = tableSession, printConfigRepository = printConfig,
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "venue-1" },
            syncOutbox = syncOutbox, productsRepository = productsRepository, connectivityMonitor = connectivity,
            timeEntryRepository = mockk(relaxed = true), comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = mockk(relaxed = true), draftStore = TableRoundDraftStore(root),
        )
    }

    @Test fun `la pieza queda con sus extras y su marca`() = runTest {
        val subject = vm()
        assertTrue(subject.addPromotion(combo, emptyMap(), mapOf("g-ham" to alMomento), mapOf("g-ham" to listOf(queso)), setOf("g-ham")))
        val item = subject.pending.value.single().item
        assertEquals(listOf(queso), item.selectedModifiers)
        assertTrue(item.comboConExtras)
        subject.viewModelScope.cancel()
    }

    @Test fun `sin extras ni marca la pieza queda como antes`() = runTest {
        val subject = vm()
        assertTrue(subject.addPromotion(combo, emptyMap(), mapOf("g-ham" to alMomento)))
        val item = subject.pending.value.single().item
        assertTrue(item.selectedModifiers.isEmpty())
        assertFalse(item.comboConExtras)
        subject.viewModelScope.cancel()
    }

    @Test fun `extras de una pieza sin marca no viajan al renglon`() = runTest {
        val subject = vm()
        assertTrue(subject.addPromotion(combo, emptyMap(), mapOf("g-ham" to alMomento), mapOf("g-ham" to listOf(queso)), emptySet()))
        val item = subject.pending.value.single().item
        assertTrue(item.selectedModifiers.isEmpty())
        assertFalse(item.comboConExtras)
        subject.viewModelScope.cancel()
    }
}
