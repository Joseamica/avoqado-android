package com.avoqado.pos.orders

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.presentation.OPEN_ORDERS_STATUS_FILTER
import com.avoqado.pos.orders.presentation.OrdersViewModel
import com.avoqado.pos.orders.presentation.applyInitialStatusFilter
import com.avoqado.pos.orders.presentation.statusFilters
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Task 7b: el pill "Abiertas" y el filtro inicial con el que "Cierre del día
 * → Cuentas abiertas" abre la lista de Pedidos. Lógica pura, sin Compose —
 * el mismo patrón de mocks que [OrdersViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelInitialFilterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<OrdersRepository>(relaxed = true)

    @Before
    fun setup() {
        every { repository.orders } returns MutableStateFlow(emptyList())
        every { repository.selectedOrder } returns MutableStateFlow(null)
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        every { repository.isLoadingDetail } returns MutableStateFlow(false)
        every { repository.errorMessage } returns MutableStateFlow(null)
        every { repository.hasMore } returns MutableStateFlow(false)
        every { repository.currentPage } returns MutableStateFlow(1)
    }

    private fun createViewModel(): OrdersViewModel {
        val refreshGateFactory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns
            com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return OrdersViewModel(repository, refreshGateFactory)
    }

    // MARK: - El pill "Abiertas"

    @Test
    fun `el pill Abiertas manda exactamente la cadena que el servidor interpreta como abierta`() {
        val abiertas = statusFilters.first { it.label == "Abiertas" }
        assertEquals("PENDING,CONFIRMED,PREPARING,READY", abiertas.value)
        // La constante que consume MoreMenuScreen es la MISMA que alimenta el pill —
        // si divergieran, "Cuentas abiertas" abriría con un pill distinto seleccionado.
        assertEquals(OPEN_ORDERS_STATUS_FILTER, abiertas.value)
    }

    @Test
    fun `el orden de los pills es Todos, Abiertas, Pendientes, Completados, Cancelados`() {
        assertEquals(
            listOf("Todos", "Abiertas", "Pendientes", "Completados", "Cancelados"),
            statusFilters.map { it.label },
        )
    }

    @Test
    fun `los pills existentes no cambiaron de semantica`() {
        assertNull(statusFilters.first { it.label == "Todos" }.value)
        assertEquals("PENDING", statusFilters.first { it.label == "Pendientes" }.value)
        assertEquals("COMPLETED", statusFilters.first { it.label == "Completados" }.value)
        assertEquals("CANCELLED", statusFilters.first { it.label == "Cancelados" }.value)
    }

    // MARK: - applyInitialStatusFilter

    @Test
    fun `con initialStatusFilter null el filtro queda en Todos, igual que hoy`() = runTest {
        val viewModel = createViewModel()
        // Estado previo: el VM YA trae "Abiertas" puesto. Sin esto la prueba
        // pasaría trivialmente con un VM recién creado (nace en null por
        // construcción) y no ejercitaría el reset — es justo el estado que
        // sobrevive a cerrar y reabrir "Pedidos" dentro de la misma visita a
        // "Más", porque OrdersViewModel es un @HiltViewModel compartido
        // (Ronda de arreglo 1, ver el hallazgo Critical de la revisión).
        viewModel.setStatusFilter(OPEN_ORDERS_STATUS_FILTER)

        applyInitialStatusFilter(viewModel, null)

        assertNull(viewModel.statusFilter.value)
        // La última llamada al repositorio queda SIN status — sea porque se
        // reseteó (había Abiertas puesto) o porque ya estaba en Todos y sólo
        // se hizo autoRefresh: lo que importa es que la lista termine sin
        // filtro, no por qué camino interno se llegó ahí.
        coVerify(exactly = 1) {
            repository.loadOrders(page = 1, search = null, status = null, append = false)
        }
    }

    @Test
    fun `escenario del Critical — Abiertas, cerrar, Pedidos normal, el filtro vuelve a Todos`() = runTest {
        val viewModel = createViewModel()

        // 1. "Cierre del día" -> "Cuentas abiertas": abre la lista YA filtrada.
        applyInitialStatusFilter(viewModel, OPEN_ORDERS_STATUS_FILTER)
        assertEquals(OPEN_ORDERS_STATUS_FILTER, viewModel.statusFilter.value)

        // 2. El cajero cierra la pantalla. A propósito NO hay nada que
        // simular aquí: el OrdersViewModel es @HiltViewModel y sobrevive al
        // cierre del composable (mismo ViewModelStore del NavBackStackEntry
        // de "Más") — es el MISMO `viewModel` de arriba, no uno nuevo. Ese
        // es exactamente el hallazgo Critical de la revisión.

        // 3. El botón "Pedidos" normal abre con initialStatusFilter = null.
        applyInitialStatusFilter(viewModel, null)

        assertNull(viewModel.statusFilter.value)
        coVerify(exactly = 1) {
            repository.loadOrders(page = 1, search = null, status = null, append = false)
        }
    }

    @Test
    fun `con el filtro de abiertas el pill seleccionado es Abiertas`() = runTest {
        val viewModel = createViewModel()
        applyInitialStatusFilter(viewModel, OPEN_ORDERS_STATUS_FILTER)

        assertEquals(OPEN_ORDERS_STATUS_FILTER, viewModel.statusFilter.value)
        val selected = statusFilters.first { it.value == viewModel.statusFilter.value }
        assertEquals("Abiertas", selected.label)
    }

    @Test
    fun `con el filtro de abiertas se pide al servidor con esa cadena`() = runTest {
        val viewModel = createViewModel()
        applyInitialStatusFilter(viewModel, OPEN_ORDERS_STATUS_FILTER)

        coVerify(atLeast = 1) {
            repository.loadOrders(
                page = 1,
                search = any(),
                status = "PENDING,CONFIRMED,PREPARING,READY",
                append = false,
            )
        }
    }
}
