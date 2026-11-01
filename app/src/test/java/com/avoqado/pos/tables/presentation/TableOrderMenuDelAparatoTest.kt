package com.avoqado.pos.tables.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import com.avoqado.pos.tables.data.MenusPayload
import com.avoqado.pos.tables.data.VenueMenu
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** «Menú del aparato» en Mesas: preselección, «Todo ▸» y recarga con el filtro del aparato. */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderMenuDelAparatoTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()
    
    private fun vm(): TableOrderViewModel {
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
            comandasPendientesStore = mockk(relaxed = true), draftStore = TableRoundDraftStore(java.nio.file.Files.createTempDirectory("menu-aparato").toFile()),
        )
    }

    private fun menu(id: String, cats: List<String>, horario: Boolean = true, ahora: Boolean = true) = VenueMenu(
        id = id, name = id, categoryIds = cats, appliesNow = ahora,
        availableFrom = if (horario) "07:00" else null, availableUntil = if (horario) "12:00" else null,
    )

    private fun servidorDa(vararg menus: VenueMenu, activo: String? = null) {
        coEvery { repository.getMenus(any()) } returns Result.success(MenusPayload(menus.toList(), activo))
    }

    @Test fun `Todos los productos es ver todo`() = runTest {
        val s = vm()
        servidorDa(menu("A", listOf("a")), activo = "A")
        s.loadMenus(null); advanceUntilIdle()
        assertEquals("A", s.selectedMenuId.value)
        s.selectMenu(null)
        assertNull(s.selectedMenuId.value)
        assertTrue(s.verTodo.value)
        assertEquals("Catálogo completo", s.actionMessage.value)
    }

    @Test fun `preselecciona solo menus del aparato`() = runTest {
        val s = vm()
        servidorDa(menu("A", listOf("a")), menu("B", listOf("b")), activo = "A")
        s.loadMenus(listOf("B")); advanceUntilIdle()
        assertEquals("B", s.selectedMenuId.value)
    }

    @Test fun `sin filtro del aparato preselecciona como hoy`() = runTest {
        val s = vm()
        servidorDa(menu("A", listOf("a")), menu("B", listOf("b")), activo = "A")
        s.loadMenus(null); advanceUntilIdle()
        assertEquals("A", s.selectedMenuId.value)
    }

    @Test fun `ver todo el catalogo prende verTodo y suelta el menu elegido`() = runTest {
        val s = vm()
        servidorDa(menu("A", listOf("a")), activo = "A")
        s.loadMenus(listOf("A")); advanceUntilIdle()
        assertEquals("A", s.selectedMenuId.value)
        s.verTodoElCatalogo()
        assertTrue(s.verTodo.value)
        assertNull(s.selectedMenuId.value)
        // Una recarga no vuelve a preseleccionar mientras verTodo siga prendido.
        s.loadMenus(listOf("A")); advanceUntilIdle()
        assertNull(s.selectedMenuId.value)
        // Elegir un menú apaga verTodo.
        s.selectMenu("A")
        assertFalse(s.verTodo.value)
    }

    @Test fun `recargar menus suelta el menu que ya no viene`() = runTest {
        val s = vm()
        servidorDa(menu("A", listOf("a")), menu("B", listOf("b")), activo = "A")
        s.loadMenus(null); advanceUntilIdle()
        s.selectMenu("B")
        servidorDa(menu("A", listOf("a")), activo = "A")
        s.loadMenus(null); advanceUntilIdle()
        assertEquals("A", s.selectedMenuId.value) // B se soltó; se preselecciona A
    }

    @Test fun `un menu elegido que sale del filtro del aparato se suelta`() = runTest {
        val s = vm()
        servidorDa(menu("A", listOf("a")), menu("B", listOf("b"), horario = false), activo = null)
        s.loadMenus(null); advanceUntilIdle()
        s.selectMenu("B")
        s.loadMenus(listOf("A")); advanceUntilIdle()
        assertEquals("A", s.selectedMenuId.value) // B se soltó; se preselecciona A
    }

    // «Todo ▸» en Mesas decide por el filtro EFECTIVO (como iOS), no por si hay menú elegido.
    private fun todo(elegido: String?, menus: List<VenueMenu>, ids: List<String>? = null, visibles: List<String>? = listOf("a"),
                     conProductos: Set<String> = setOf("a", "c")) =
        com.avoqado.pos.pos.presentation.product.MenuDelAparato
            .muestraVerTodoEnMesas(elegido, menus, ids, visibles, false, conProductos)

    @Test fun `todo se ofrece con un menu elegido de las mismas categorias del aparato`() {
        assertTrue(todo("M", listOf(menu("M", listOf("a")))))
    }

    @Test fun `todo se ofrece si el menu elegido no tiene productos`() {
        assertTrue(todo("M", listOf(menu("M", listOf("p")))))
    }

    @Test fun `todo se ofrece si el menu elegido no trae categorias`() {
        assertTrue(todo("M", listOf(menu("M", emptyList()))))
    }

    @Test fun `todo se ofrece si el menu elegido ya no es del aparato`() {
        assertTrue(todo("M", listOf(menu("M", listOf("c")), menu("N", listOf("a"))), ids = listOf("N")))
    }

    @Test fun `todo no se ofrece con un menu elegido con otras categorias`() {
        assertFalse(todo("M", listOf(menu("M", listOf("c")))))
    }
}
