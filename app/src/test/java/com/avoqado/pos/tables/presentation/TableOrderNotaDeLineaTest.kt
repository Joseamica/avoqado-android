package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.Product
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Nota por artículo en Mesas (plan 2026-10-13): la línea pendiente se puede anotar («sin aceite») sin pasar por
 * modificadores. La nota vive en el borrador del aparato desde que se escribe, y una línea anotada no se traga el
 * siguiente toque del mismo producto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderNotaDeLineaTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()
    private val ensalada = Product(id = "prod-ensalada", name = "Ensalada", priceValue = 120.0)

    private fun vm(draftStore: TableRoundDraftStore): TableOrderViewModel {
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
        return TableOrderViewModel(
            repository = repository, tableSession = tableSession, printConfigRepository = printConfig,
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "venue-1" },
            syncOutbox = syncOutbox, productsRepository = mockk(relaxed = true), connectivityMonitor = connectivity,
            timeEntryRepository = mockk(relaxed = true), comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = mockk(relaxed = true), draftStore = draftStore,
        )
    }

    private fun conDirectorio(body: (java.io.File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("nota-de-linea").toFile()
        try { body(root) } finally { root.deleteRecursively() }
    }

    @Test fun `la nota cambia sólo esa línea y sobrevive a reabrir la app`() = runTest {
        conDirectorio { root ->
            val subject = vm(TableRoundDraftStore(root))
            subject.loadCheck(); advanceUntilIdle() // al abrir la mesa la pantalla carga la cuenta y su borrador
            subject.addProduct(ensalada)
            subject.addCustomAmount("Pan", 3000)
            val ensaladaId = subject.pending.value.first().item.id

            subject.updatePendingNote(ensaladaId, "  sin aceite ")

            assertEquals("sin aceite", subject.pending.value.first().item.itemNote)
            assertNull(subject.pending.value[1].item.itemNote)
            subject.viewModelScope.cancel()

            val cold = vm(TableRoundDraftStore(root))
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(IOException("sin red"))
            cold.loadCheck(); advanceUntilIdle()
            assertEquals("sin aceite", cold.pending.value.first { it.item.id == ensaladaId }.item.itemNote)
            cold.viewModelScope.cancel()
        }
    }

    @Test fun `texto vacío borra la nota`() = runTest {
        conDirectorio { root ->
            val subject = vm(TableRoundDraftStore(root))
            subject.addProduct(ensalada)
            val id = subject.pending.value.first().item.id
            subject.updatePendingNote(id, "sin aceite")
            subject.updatePendingNote(id, "   ")
            assertNull(subject.pending.value.first().item.itemNote)
            subject.viewModelScope.cancel()
        }
    }

    @Test fun `una línea con nota no se traga el siguiente toque del mismo producto`() = runTest {
        conDirectorio { root ->
            val subject = vm(TableRoundDraftStore(root))
            subject.addProduct(ensalada)
            subject.addProduct(ensalada)
            assertEquals("Sin nota, el toque suma a la misma línea", listOf(2), subject.pending.value.map { it.item.quantity })

            subject.updatePendingNote(subject.pending.value.first().item.id, "sin aceite")
            subject.addProduct(ensalada)

            assertEquals(listOf(2, 1), subject.pending.value.map { it.item.quantity })
            assertEquals(listOf("sin aceite", null), subject.pending.value.map { it.item.itemNote })
            subject.addProduct(ensalada)
            assertEquals("La línea nueva sin nota sí sigue sumando", listOf(2, 2), subject.pending.value.map { it.item.quantity })
            subject.viewModelScope.cancel()
        }
    }
}
