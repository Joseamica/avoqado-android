package com.avoqado.pos.reservations.presentation.create

import androidx.lifecycle.SavedStateHandle
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customers.data.CustomersRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.WaitlistRepository
import com.avoqado.pos.reservations.data.model.ProductStaffContract
import com.avoqado.pos.reservations.data.model.ProductStaffMemberContract
import com.avoqado.pos.reservations.data.model.ReservationSchedulingContract
import com.avoqado.pos.reservations.data.model.ReservationSettingsContract
import com.avoqado.pos.tables.data.TablesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateReservationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `staff-aware slot failure exposes retryable error and retry clears it`() = runTest(dispatcher) {
        val repository: ReservationRepository = mockk(relaxed = true)
        val customersRepository: CustomersRepository = mockk(relaxed = true)
        val productsRepository: ProductsRepository = mockk(relaxed = true)
        val tablesRepository: TablesRepository = mockk(relaxed = true)
        val staffRepository: StaffRepository = mockk(relaxed = true)
        val waitlistRepository: WaitlistRepository = mockk(relaxed = true)
        val secureStorage: SecureStorage = mockk(relaxed = true)

        every { productsRepository.products } returns MutableStateFlow(emptyList())
        every { secureStorage.venueTimezone } returns "America/Mexico_City"
        coEvery { customersRepository.fetchCustomers() } returns Result.success(emptyList())
        coEvery { tablesRepository.fetchTables() } returns Result.success(emptyList())
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
        coEvery { productsRepository.fetchProducts(any()) } returns Unit
        coEvery { repository.reservationSettings() } returns Result.success(
            ReservationSettingsContract(
                scheduling = ReservationSchedulingContract(capacityMode = "per_staff"),
            ),
        )
        coEvery { repository.productStaff("service-1") } returns Result.success(
            ProductStaffContract(
                productId = "service-1",
                staffVenueIds = listOf("staff-venue-1"),
                staff = listOf(ProductStaffMemberContract("staff-venue-1", "staff-1")),
                explicit = true,
            ),
        )
        coEvery {
            repository.availableSlots(any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            // Initial legacy load before a service is selected.
            Result.success(emptyList()),
            Result.failure(IllegalStateException("offline")),
            Result.success(emptyList()),
        )

        val viewModel = CreateReservationViewModel(
            repository,
            customersRepository,
            productsRepository,
            tablesRepository,
            staffRepository,
            waitlistRepository,
            secureStorage,
            SavedStateHandle(),
        )
        advanceUntilIdle()

        viewModel.selectProduct(
            Product(
                id = "service-1",
                name = "Corte",
                type = "APPOINTMENTS_SERVICE",
                duration = 60,
            ),
        )
        advanceUntilIdle()

        assertEquals("No se pudieron cargar los horarios. Reintenta.", viewModel.slotLoadError.value)

        viewModel.loadSlots()
        advanceUntilIdle()

        assertNull(viewModel.slotLoadError.value)
        assertEquals(emptyList<Any>(), viewModel.availableSlots.value)
    }
}
