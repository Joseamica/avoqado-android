package com.avoqado.pos.orders

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.presentation.OrdersViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelTest {

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

    private fun createViewModel() = OrdersViewModel(repository)

    // MARK: - Initial State

    @Test
    fun `initial state has empty search and no filter`() {
        val viewModel = createViewModel()
        assertEquals("", viewModel.searchText.value)
        assertNull(viewModel.statusFilter.value)
    }

    @Test
    fun `initial selectedOrderId is null`() {
        val viewModel = createViewModel()
        assertNull(viewModel.selectedOrderId.value)
    }

    @Test
    fun `init calls refresh on construction`() = runTest {
        val viewModel = createViewModel()
        coVerify(atLeast = 1) {
            repository.loadOrders(page = 1, search = null, status = null, append = false)
        }
    }

    // MARK: - Search

    @Test
    fun `updateSearch changes searchText`() {
        val viewModel = createViewModel()
        viewModel.updateSearch("burger")
        assertEquals("burger", viewModel.searchText.value)
    }

    @Test
    fun `updateSearch clears searchText when empty`() {
        val viewModel = createViewModel()
        viewModel.updateSearch("burger")
        viewModel.updateSearch("")
        assertEquals("", viewModel.searchText.value)
    }

    // MARK: - Status Filter

    @Test
    fun `setStatusFilter changes filter`() {
        val viewModel = createViewModel()
        viewModel.setStatusFilter("COMPLETED")
        assertEquals("COMPLETED", viewModel.statusFilter.value)
    }

    @Test
    fun `setStatusFilter with null clears filter`() {
        val viewModel = createViewModel()
        viewModel.setStatusFilter("PENDING")
        viewModel.setStatusFilter(null)
        assertNull(viewModel.statusFilter.value)
    }

    @Test
    fun `setStatusFilter changes filter and triggers refresh`() = runTest {
        val viewModel = createViewModel()
        viewModel.setStatusFilter("CANCELLED")

        coVerify(atLeast = 1) {
            repository.loadOrders(page = 1, search = any(), status = "CANCELLED", append = false)
        }
    }

    // MARK: - Select Order

    @Test
    fun `selectOrder sets selectedOrderId`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("order-001")
        assertEquals("order-001", viewModel.selectedOrderId.value)
    }

    @Test
    fun `selectOrder calls repository loadOrderDetail`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("order-001")
        coVerify { repository.loadOrderDetail("order-001") }
    }

    @Test
    fun `selectOrder with different id updates selectedOrderId`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("order-001")
        viewModel.selectOrder("order-002")
        assertEquals("order-002", viewModel.selectedOrderId.value)
    }

    // MARK: - Clear Selection

    @Test
    fun `clearSelection clears selectedOrderId`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("order-001")
        viewModel.clearSelection()
        assertNull(viewModel.selectedOrderId.value)
    }

    @Test
    fun `clearSelection calls repository clearSelectedOrder`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("order-001")
        viewModel.clearSelection()
        verify { repository.clearSelectedOrder() }
    }

    // MARK: - Load More

    @Test
    fun `loadMore does nothing when hasMore is false`() = runTest {
        every { repository.hasMore } returns MutableStateFlow(false)
        every { repository.currentPage } returns MutableStateFlow(1)
        every { repository.isLoadingMore } returns MutableStateFlow(false)

        val viewModel = createViewModel()
        viewModel.loadMore()

        coVerify(exactly = 0) {
            repository.loadOrders(page = 2, search = any(), status = any(), append = true)
        }
    }

    @Test
    fun `loadMore calls repository with next page when hasMore is true`() = runTest {
        every { repository.hasMore } returns MutableStateFlow(true)
        every { repository.currentPage } returns MutableStateFlow(1)
        every { repository.isLoadingMore } returns MutableStateFlow(false)

        val viewModel = createViewModel()
        viewModel.loadMore()

        coVerify {
            repository.loadOrders(page = 2, search = null, status = null, append = true)
        }
    }

    @Test
    fun `loadMore does nothing when already loading more`() = runTest {
        every { repository.hasMore } returns MutableStateFlow(true)
        every { repository.currentPage } returns MutableStateFlow(1)
        every { repository.isLoadingMore } returns MutableStateFlow(true)

        val viewModel = createViewModel()
        viewModel.loadMore()

        coVerify(exactly = 0) {
            repository.loadOrders(page = 2, search = any(), status = any(), append = true)
        }
    }

    // MARK: - Group Orders by Date

    @Test
    fun `groupOrdersByDate groups orders correctly`() {
        val viewModel = createViewModel()
        val ordersWithSameDate = listOf(
            OrderSummary(id = "1", createdAt = "2024-01-15T10:00:00.000Z"),
            OrderSummary(id = "2", createdAt = "2024-01-15T12:00:00.000Z"),
            OrderSummary(id = "3", createdAt = "2024-01-16T09:00:00.000Z"),
        )

        val grouped = viewModel.groupOrdersByDate(ordersWithSameDate)

        // Should have 2 groups: one for Jan 15, one for Jan 16
        assertEquals(2, grouped.size)
    }

    @Test
    fun `groupOrdersByDate returns empty list for empty input`() {
        val viewModel = createViewModel()
        val grouped = viewModel.groupOrdersByDate(emptyList())
        assertEquals(0, grouped.size)
    }

    @Test
    fun `groupOrdersByDate single order returns single group`() {
        val viewModel = createViewModel()
        val orders = listOf(OrderSummary(id = "1", createdAt = "2024-01-15T10:00:00.000Z"))
        val grouped = viewModel.groupOrdersByDate(orders)
        assertEquals(1, grouped.size)
        assertEquals(1, grouped.first().second.size)
    }

    @Test
    fun `groupOrdersByDate groups same-day orders together`() {
        val viewModel = createViewModel()
        val orders = listOf(
            OrderSummary(id = "1", createdAt = "2024-03-10T08:00:00.000Z"),
            OrderSummary(id = "2", createdAt = "2024-03-10T14:30:00.000Z"),
            OrderSummary(id = "3", createdAt = "2024-03-10T20:00:00.000Z"),
        )

        val grouped = viewModel.groupOrdersByDate(orders)
        assertEquals(1, grouped.size)
        assertEquals(3, grouped.first().second.size)
    }

    @Test
    fun `groupOrdersByDate result is sorted descending by date`() {
        val viewModel = createViewModel()
        val orders = listOf(
            OrderSummary(id = "1", createdAt = "2024-01-10T10:00:00.000Z"),
            OrderSummary(id = "2", createdAt = "2024-01-12T10:00:00.000Z"),
            OrderSummary(id = "3", createdAt = "2024-01-11T10:00:00.000Z"),
        )

        val grouped = viewModel.groupOrdersByDate(orders)
        // Most recent date should come first
        val firstGroupOrder = grouped.first().second.first()
        assertEquals("2024-01-12T10:00:00.000Z", firstGroupOrder.createdAt)
    }

    // MARK: - Refresh

    @Test
    fun `refresh loads page 1 with current search and filter`() = runTest {
        val viewModel = createViewModel()
        viewModel.updateSearch("taco")
        viewModel.setStatusFilter("PENDING")
        viewModel.refresh()

        coVerify(atLeast = 1) {
            repository.loadOrders(page = 1, search = "taco", status = "PENDING", append = false)
        }
    }
}
