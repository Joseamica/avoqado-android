package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.ProductLite
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationServiceLite
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun s() { Dispatchers.setMain(dispatcher) }
    @After fun t() { Dispatchers.resetMain() }

    private fun stub(
        status: ReservationStatus = ReservationStatus.CONFIRMED,
        productId: String? = null,
        product: ProductLite? = null,
        services: List<ReservationServiceLite>? = null,
    ) = Reservation(
        id = "r1", venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "...",
        productId = productId, product = product, services = services,
    )

    private val capProvider: Provider<ReservationsCapability> = Provider { ReservationsCapability(true, true, true, true) }

    // PRINT_STATIONS — new deps default to "no active stations", so the pre-existing tests
    // below (which never fire CHECK_IN) keep exercising exactly the same paths as before this
    // change; only the new CHECK_IN-focused tests below override these.
    private fun defaultSecureStorage(venueId: String? = "v"): SecureStorage {
        val s = mockk<SecureStorage>(relaxed = true)
        every { s.venueId } returns venueId
        return s
    }

    private fun defaultPrintConfigRepository(config: PrintConfig = PrintConfig()): PrintConfigRepository {
        val r = mockk<PrintConfigRepository>(relaxed = true)
        coEvery { r.refresh(any()) } returns Unit
        every { r.getCurrentConfig() } returns config
        return r
    }

    private fun buildVm(
        repo: ReservationRepository,
        secureStorage: SecureStorage = defaultSecureStorage(),
        printConfigRepository: PrintConfigRepository = defaultPrintConfigRepository(),
        comandaPrinter: ComandaPrinter = mockk(relaxed = true),
        productsRepository: ProductsRepository = mockk(relaxed = true),
    ) = ReservationDetailViewModel(
        repository = repo,
        capabilityProvider = capProvider,
        secureStorage = secureStorage,
        printConfigRepository = printConfigRepository,
        comandaPrinter = comandaPrinter,
        productsRepository = productsRepository,
        savedStateHandle = SavedStateHandle(mapOf("reservationId" to "r1")),
    )

    @Test
    fun `loads reservation on init`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub())

        val vm = buildVm(repo)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("r1", s.reservation?.id)
        assertEquals(false, s.isLoading)
    }

    @Test
    fun `runAction confirm transitions optimistically`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.PENDING))
        coEvery { repo.runAction("r1", ReservationAction.CONFIRM, null) } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val vm = buildVm(repo)
        advanceUntilIdle()
        vm.runAction(ReservationAction.CONFIRM)
        advanceUntilIdle()

        val s = vm.state.value
        assertNull(s.pendingAction)
        assertEquals(ReservationStatus.CONFIRMED, s.reservation?.status)
        assertEquals(ReservationAction.CONFIRM, s.justCompletedAction)
    }

    @Test
    fun `runAction failure rolls back`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.PENDING))
        coEvery { repo.runAction("r1", ReservationAction.NO_SHOW, null) } returns Result.failure(RuntimeException("HTTP 409"))

        val vm = buildVm(repo)
        advanceUntilIdle()
        vm.runAction(ReservationAction.NO_SHOW)
        advanceUntilIdle()

        val s = vm.state.value
        assertNull(s.pendingAction)
        assertEquals(ReservationStatus.PENDING, s.reservation?.status)
        assertTrue(s.error!!.contains("409"))
    }

    @Test
    fun `check-in with active stations prints a comanda for every booked service`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val checkedIn = stub(
            status = ReservationStatus.CHECKED_IN,
            services = listOf(
                ReservationServiceLite(id = "svc-1", name = "Corte de cabello"),
                ReservationServiceLite(id = "svc-2", name = "Manicure"),
            ),
        )
        coEvery { repo.runAction("r1", ReservationAction.CHECK_IN, null) } returns Result.success(checkedIn)

        val station = StationInfo(id = "st1", name = "Estación 1", printerId = "pr1", active = true)
        val config = PrintConfig(stations = listOf(station), defaultStationId = "st1")
        val printConfigRepository = defaultPrintConfigRepository(config)
        val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
        val plansSlot = slot<List<TicketPlan>>()
        coEvery { comandaPrinter.printComandas(capture(plansSlot), config, any(), any(), any()) } returns Unit
        val productsRepository = mockk<ProductsRepository>(relaxed = true)

        val vm = buildVm(
            repo,
            printConfigRepository = printConfigRepository,
            comandaPrinter = comandaPrinter,
            productsRepository = productsRepository,
        )
        advanceUntilIdle()
        vm.runAction(ReservationAction.CHECK_IN)
        advanceUntilIdle()

        assertEquals(ReservationStatus.CHECKED_IN, vm.state.value.reservation?.status)
        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), config, any(), any(), any()) }
        val printedNames = plansSlot.captured.flatMap { it.lines }.map { it.productName }
        assertEquals(listOf("Corte de cabello", "Manicure"), printedNames)
    }

    @Test
    fun `check-in with no active stations does not print and still succeeds`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val checkedIn = stub(
            status = ReservationStatus.CHECKED_IN,
            services = listOf(ReservationServiceLite(id = "svc-1", name = "Corte de cabello")),
        )
        coEvery { repo.runAction("r1", ReservationAction.CHECK_IN, null) } returns Result.success(checkedIn)

        // Default printConfigRepository() => PrintConfig() with no stations.
        val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
        val vm = buildVm(repo, comandaPrinter = comandaPrinter)
        advanceUntilIdle()
        vm.runAction(ReservationAction.CHECK_IN)
        advanceUntilIdle()

        assertEquals(ReservationStatus.CHECKED_IN, vm.state.value.reservation?.status)
        assertEquals(ReservationAction.CHECK_IN, vm.state.value.justCompletedAction)
        assertNull(vm.state.value.error)
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `check-in still succeeds when the print side effect throws`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val checkedIn = stub(
            status = ReservationStatus.CHECKED_IN,
            services = listOf(ReservationServiceLite(id = "svc-1", name = "Corte de cabello")),
        )
        coEvery { repo.runAction("r1", ReservationAction.CHECK_IN, null) } returns Result.success(checkedIn)

        val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
        coEvery { printConfigRepository.refresh(any()) } throws RuntimeException("network down")

        val vm = buildVm(repo, printConfigRepository = printConfigRepository)
        advanceUntilIdle()
        vm.runAction(ReservationAction.CHECK_IN)
        advanceUntilIdle()

        // SAFETY RULE #1: the print side effect blew up, but the check-in itself must still
        // report success — it was already committed before the print attempt ran.
        val s = vm.state.value
        assertEquals(ReservationStatus.CHECKED_IN, s.reservation?.status)
        assertEquals(ReservationAction.CHECK_IN, s.justCompletedAction)
        assertNull(s.error)
    }

    @Test
    fun `check-in without a services list falls back to the reservation's single product`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val checkedIn = stub(
            status = ReservationStatus.CHECKED_IN,
            productId = "prod-1",
            product = ProductLite(id = "prod-1", name = "Corte de cabello"),
            services = null,
        )
        coEvery { repo.runAction("r1", ReservationAction.CHECK_IN, null) } returns Result.success(checkedIn)

        val station = StationInfo(id = "st1", name = "Estación 1", printerId = "pr1", active = true)
        val config = PrintConfig(stations = listOf(station), defaultStationId = "st1")
        val printConfigRepository = defaultPrintConfigRepository(config)
        val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
        val plansSlot = slot<List<TicketPlan>>()
        coEvery { comandaPrinter.printComandas(capture(plansSlot), config, any(), any(), any()) } returns Unit

        val vm = buildVm(repo, printConfigRepository = printConfigRepository, comandaPrinter = comandaPrinter)
        advanceUntilIdle()
        vm.runAction(ReservationAction.CHECK_IN)
        advanceUntilIdle()

        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), config, any(), any(), any()) }
        val printedNames = plansSlot.captured.flatMap { it.lines }.map { it.productName }
        assertEquals(listOf("Corte de cabello"), printedNames)
    }
}
