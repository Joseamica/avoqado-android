package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.ReservationApiException
import com.avoqado.pos.reservations.data.model.ClassSessionLite
import com.avoqado.pos.reservations.data.model.CustomerLite
import com.avoqado.pos.reservations.data.model.NoShowCorrection
import com.avoqado.pos.reservations.data.model.NoShowCorrectionRef
import com.avoqado.pos.reservations.data.model.ProductLite
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationServiceLite
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.UndoNoShowPlan
import com.avoqado.pos.reservations.data.model.UndoNoShowResult
import com.avoqado.pos.reservations.domain.UndoNoShowTexts
import com.avoqado.pos.reservations.presentation.undo.UndoNoShowFlow
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private var canUpdate = true
    private val capProvider: Provider<ReservationsCapability> = Provider { ReservationsCapability(true, true, canUpdate, true) }

    private fun connectivity(online: Boolean = true): ConnectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns MutableStateFlow(online)
        every { it.isServerReachable } returns MutableStateFlow(true)
        every { it.isFullyConnected } returns online
    }

    // PRINT_STATIONS — new deps default to "no active stations", so the pre-existing tests
    // below (which never fire CHECK_IN) keep exercising exactly the same paths as before this
    // change; only the new CHECK_IN-focused tests below override these.
    private fun defaultSecureStorage(venueId: String? = "v"): SecureStorage {
        val s = mockk<SecureStorage>(relaxed = true)
        every { s.venueId } returns venueId
        every { s.venueTimezone } returns "America/Mexico_City"
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
        connectivity: ConnectivityMonitor = connectivity(),
    ) = ReservationDetailViewModel(
        repository = repo,
        capabilityProvider = capProvider,
        secureStorage = secureStorage,
        printConfigRepository = printConfigRepository,
        comandaPrinter = comandaPrinter,
        productsRepository = productsRepository,
        savedStateHandle = SavedStateHandle(mapOf("reservationId" to "r1")),
        connectivity = connectivity,
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
    fun `reschedule over capacity asks for confirmation and retries with consent`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub())
        val first = ReservationRepository.ActionPayload.Reschedule(
            startsAt = "2026-04-30T15:00:00.000Z",
            endsAt = "2026-04-30T16:00:00.000Z",
        )
        coEvery { repo.runAction("r1", ReservationAction.RESCHEDULE, first) } returns Result.failure(
            ReservationApiException(
                status = 409,
                message = "El horario está lleno",
                code = "OVER_CAPACITY_CONFIRMATION_REQUIRED",
                preview = "2 de 1",
            ),
        )
        coEvery {
            repo.runAction(
                "r1",
                ReservationAction.RESCHEDULE,
                first.copy(allowOverCapacity = true),
            )
        } returns Result.success(stub())

        val vm = buildVm(repo)
        advanceUntilIdle()
        vm.runAction(ReservationAction.RESCHEDULE, first)
        advanceUntilIdle()

        assertEquals("El horario está lleno\nOcupación: 2 de 1.", vm.overCapacityConfirmation.value)
        assertNull(vm.state.value.error)

        vm.confirmOverCapacityReschedule()
        advanceUntilIdle()

        assertNull(vm.overCapacityConfirmation.value)
        assertEquals(ReservationAction.RESCHEDULE, vm.state.value.justCompletedAction)
        coVerify(exactly = 1) {
            repo.runAction("r1", ReservationAction.RESCHEDULE, first.copy(allowOverCapacity = true))
        }
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
        coEvery { comandaPrinter.printComandas(capture(plansSlot), config, any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
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
        coEvery { comandaPrinter.printComandas(capture(plansSlot), config, any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        val vm = buildVm(repo, printConfigRepository = printConfigRepository, comandaPrinter = comandaPrinter)
        advanceUntilIdle()
        vm.runAction(ReservationAction.CHECK_IN)
        advanceUntilIdle()

        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), config, any(), any(), any()) }
        val printedNames = plansSlot.captured.flatMap { it.lines }.map { it.productName }
        assertEquals(listOf("Corte de cabello"), printedNames)
    }

    // MARK: - D4 · «Sí llegó»

    private fun karla(
        status: ReservationStatus = ReservationStatus.NO_SHOW,
        correction: NoShowCorrection? = null,
    ) = stub(status).copy(
        customer = CustomerLite(id = "c1", firstName = "Karla", lastName = "Rodríguez"),
        classSession = ClassSessionLite(id = "s1", productName = "Yoga", capacity = 10),
        noShowCorrection = correction,
    )

    private fun repoCon(r: Reservation): ReservationRepository {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(r)
        return repo
    }

    private fun resultado(outcome: String = "UNDONE", pending: List<String> = emptyList()) = UndoNoShowResult(
        outcome = outcome,
        reservation = karla(ReservationStatus.CHECKED_IN),
        plan = UndoNoShowPlan(pending = pending),
        correction = NoShowCorrectionRef(id = "c1", pending = pending),
    )

    @Test
    fun `Si llego solo con No se presento y permiso de editar reservaciones`() = runTest(dispatcher) {
        val vm = buildVm(repoCon(karla()))
        advanceUntilIdle()
        assertTrue(vm.state.value.canUndoNoShow)

        val confirmada = buildVm(repoCon(karla(ReservationStatus.CONFIRMED)))
        advanceUntilIdle()
        assertFalse(confirmada.state.value.canUndoNoShow)

        canUpdate = false
        val sinPermiso = buildVm(repoCon(karla()))
        advanceUntilIdle()
        assertFalse(sinPermiso.state.value.canUndoNoShow)
    }

    @Test
    fun `la confirmacion dice a quien, a que clase y a que hora, y solo los renglones que aplican`() = runTest(dispatcher) {
        val repo = repoCon(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.success(
            UndoNoShowPlan(creditToReconsume = 1, clearFee = false, restoreDeposit = true, depositAmount = 100.0, packName = "5 clases de yoga"),
        )
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow()
        advanceUntilIdle()

        val prompt = vm.undo.ui.value.prompt!!
        // 10:00 UTC = 04:00 en la Ciudad de México
        assertEquals("¿Karla R. sí llegó a Yoga de las 04:00?", prompt.title)
        assertEquals(
            listOf(
                "Clase: se le vuelve a descontar 1 de «5 clases de yoga».",
                "Depósito: sus $100.00 vuelven a ser parte de su visita.",
                "Queda en la bitácora quién lo deshizo y a qué hora.",
            ),
            prompt.rows,
        )
        assertTrue(prompt.pending.isEmpty())
        coVerify(exactly = 0) { repo.undoNoShow(any(), any()) }
    }

    @Test
    fun `reserva de un pase - el mensaje del pase en vez de la confirmacion`() = runTest(dispatcher) {
        val repo = repoCon(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.failure(
            ReservationApiException(409, "x", code = "NO_SHOW_UNDO_PASS_BOOKING"),
        )
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow()
        advanceUntilIdle()

        assertNull(vm.undo.ui.value.prompt)
        assertEquals("Esta reserva vino de un pase (TotalPass/Wellhub); corrígela desde su app", vm.undo.ui.value.blocked)
    }

    @Test
    fun `resultado con pendiente - lo ensena y la reserva queda con su franja abierta`() = runTest(dispatcher) {
        val repo = repoCon(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan(pending = listOf("CREDIT_NOT_RECONSUMED")))
        coEvery { repo.undoNoShow("r1", null) } returns Result.success(resultado(pending = listOf("CREDIT_NOT_RECONSUMED")))
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow(); advanceUntilIdle()
        assertEquals(listOf("No había clase que descontar: cóbrala o regálala"), vm.undo.ui.value.prompt!!.pending)
        vm.undo.confirm(); advanceUntilIdle()

        val done = vm.undo.ui.value.done!!
        assertEquals("Llegada corregida", done.message)
        assertEquals(listOf("No había clase que descontar: cóbrala o regálala"), done.pending)
        assertNull(vm.undo.ui.value.prompt)
        assertEquals(ReservationStatus.CHECKED_IN, vm.state.value.reservation?.status)
        assertEquals(listOf("No había clase que descontar: cóbrala o regálala"), vm.state.value.openPendingTexts)
    }

    @Test
    fun `ya estaba corregida lo dice asi`() = runTest(dispatcher) {
        val repo = repoCon(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan())
        coEvery { repo.undoNoShow("r1", null) } returns Result.success(resultado(outcome = "ALREADY_UNDONE"))
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow(); advanceUntilIdle()
        vm.undo.confirm(); advanceUntilIdle()

        assertEquals("Ya estaba corregida", vm.undo.ui.value.done?.message)
    }

    @Test
    fun `dos toques en Si, llego mandan un solo POST`() = runTest(dispatcher) {
        val repo = repoCon(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan())
        coEvery { repo.undoNoShow("r1", null) } returns Result.success(resultado())
        val vm = buildVm(repo)
        advanceUntilIdle()
        vm.undoNoShow(); advanceUntilIdle()

        vm.undo.confirm()
        vm.undo.confirm()
        vm.undoNoShow()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.undoNoShow(any(), any()) }
        coVerify(exactly = 1) { repo.previewUndoNoShow(any()) }
    }

    @Test
    fun `sin red el boton queda en gris y si se toca no pasa de la vista previa`() = runTest(dispatcher) {
        val repo = repoCon(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.failure(
            ReservationRepository.OfflineRequiredException(UndoNoShowTexts.OFFLINE),
        )
        val vm = buildVm(repo, connectivity = connectivity(online = false))
        advanceUntilIdle()

        assertFalse(vm.isOnline.value)
        vm.undoNoShow(); advanceUntilIdle()

        assertNull(vm.undo.ui.value.prompt)
        assertEquals(
            UndoNoShowFlow.Notice("Sin conexión", "Necesita conexión: deshacer un “No se presentó” cambia el paquete y el depósito", warning = true),
            vm.undo.ui.value.notice,
        )
        coVerify(exactly = 0) { repo.undoNoShow(any(), any()) }
    }

    @Test
    fun `pendiente sin resolver - franja con Marcar resuelto, que la cierra`() = runTest(dispatcher) {
        val abierta = NoShowCorrection(pending = listOf("CLASS_UNPAID"), resolvedAt = null, source = "KIOSK", createdAt = "2026-04-29T10:30:00.000Z")
        val repo = repoCon(karla(ReservationStatus.CHECKED_IN, abierta))
        coEvery { repo.resolveNoShowCorrection("r1", null) } returns Result.success("2026-04-29T11:00:00.000Z")
        val vm = buildVm(repo)
        advanceUntilIdle()

        assertEquals(listOf("Esta clase no estaba pagada: cóbrala en caja"), vm.state.value.openPendingTexts)
        vm.resolvePending()
        vm.resolvePending()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.resolveNoShowCorrection("r1", null) }
        assertTrue(vm.state.value.openPendingTexts.isEmpty())
        assertEquals("Pendiente resuelto", vm.state.value.notice)
    }

    @Test
    fun `Marcar resuelto con la red cortada - aviso en ambar y la franja sigue`() = runTest(dispatcher) {
        val abierta = NoShowCorrection(pending = listOf("CLASS_UNPAID"))
        val repo = repoCon(karla(ReservationStatus.CHECKED_IN, abierta))
        coEvery { repo.resolveNoShowCorrection("r1", null) } returns Result.failure(java.net.SocketTimeoutException("timeout"))
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.resolvePending(); advanceUntilIdle()

        assertEquals(UndoNoShowFlow.Notice("Sin conexión", "Necesita conexión para marcarlo resuelto", warning = true), vm.state.value.resolveNotice)
        assertNull("nunca el texto crudo", vm.state.value.error)
        assertEquals(listOf("Esta clase no estaba pagada: cóbrala en caja"), vm.state.value.openPendingTexts)
        assertFalse(vm.state.value.resolvingPending)
    }

    @Test
    fun `Marcar resuelto rechazado - titulo y texto humanizado, no el crudo`() = runTest(dispatcher) {
        val abierta = NoShowCorrection(pending = listOf("CLASS_UNPAID"))
        val repo = repoCon(karla(ReservationStatus.CHECKED_IN, abierta))
        coEvery { repo.resolveNoShowCorrection("r1", null) } returns Result.failure(ReservationApiException(403, "Forbidden"))
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.resolvePending(); advanceUntilIdle()

        assertEquals(UndoNoShowFlow.Notice("No se pudo completar la acción", "No tienes permiso para hacer esto."), vm.state.value.resolveNotice)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `una correccion limpia no es pendiente`() = runTest(dispatcher) {
        val limpia = NoShowCorrection(pending = emptyList(), resolvedAt = null, source = "POS_ANDROID")
        val vm = buildVm(repoCon(karla(ReservationStatus.CHECKED_IN, limpia)))
        advanceUntilIdle()

        assertTrue(vm.state.value.openPendingTexts.isEmpty())
    }

    @Test
    fun `la nota de la correccion dice quien y a que hora - o el kiosco`() {
        val porAna = NoShowCorrection(pending = emptyList(), source = "POS_ANDROID", createdAt = "x", actorName = "Ana")
        val porKiosco = porAna.copy(source = "KIOSK", actorName = null)
        assertEquals("Llegó · corregido 09:40 por Ana", UndoNoShowTexts.correctedNote(porAna, "09:40"))
        assertEquals("Llegó · corregido 09:40 por el kiosco", UndoNoShowTexts.correctedNote(porKiosco, "09:40"))
        assertEquals("Llegó · corregido 09:40", UndoNoShowTexts.correctedNote(porKiosco.copy(source = "DASHBOARD"), "09:40"))
        assertNull("sin hora no hay nota (como iOS)", UndoNoShowTexts.correctedNote(porAna, null))
    }

    // MARK: - Antes de decir «falló» se vuelve a leer la reserva (respuesta perdida, u otro aparato o el kiosco ya la corrigió)

    private val corregida = karla(
        ReservationStatus.CHECKED_IN,
        NoShowCorrection(pending = listOf("CLASS_UNPAID"), source = "KIOSK", createdAt = "2026-04-29T10:30:00.000Z"),
    )

    @Test
    fun `respuesta perdida con la correccion ya hecha - Ya estaba corregida con su pendiente`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returnsMany listOf(Result.success(karla()), Result.success(corregida))
        coEvery { repo.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan())
        coEvery { repo.undoNoShow("r1", null) } returns Result.failure(java.io.IOException("unexpected end of stream"))
        every { repo.notifyChanged() } returns Unit
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow(); advanceUntilIdle()
        vm.undo.confirm(); advanceUntilIdle()

        assertEquals(
            UndoNoShowFlow.Done("Ya estaba corregida", listOf("Esta clase no estaba pagada: cóbrala en caja")),
            vm.undo.ui.value.done,
        )
        assertNull(vm.undo.ui.value.notice)
        assertEquals(ReservationStatus.CHECKED_IN, vm.state.value.reservation?.status)
        assertEquals("KIOSK", vm.state.value.reservation?.noShowCorrection?.source)
        io.mockk.verify(exactly = 1) { repo.notifyChanged() } // el calendario y las listas se vuelven a leer
    }

    @Test
    fun `respuesta perdida sin correccion - se dice que se corto y al reintentar Ya estaba corregida`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returns Result.success(karla())
        coEvery { repo.previewUndoNoShow("r1") } returns Result.success(UndoNoShowPlan())
        coEvery { repo.undoNoShow("r1", null) } returnsMany listOf(
            Result.failure(java.io.IOException("timeout")),
            Result.success(resultado(outcome = "ALREADY_UNDONE")),
        )
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow(); advanceUntilIdle()
        vm.undo.confirm(); advanceUntilIdle()
        assertEquals(
            UndoNoShowFlow.Notice(
                "No se pudo completar la acción",
                "Se cortó la conexión. Vuelve a tocar «Sí llegó»: si ya se había corregido, no se repite.",
                warning = true,
            ),
            vm.undo.ui.value.notice,
        )

        vm.undo.dismiss()
        vm.undoNoShow(); advanceUntilIdle()
        vm.undo.confirm(); advanceUntilIdle()

        assertEquals("Ya estaba corregida", vm.undo.ui.value.done?.message)
    }

    @Test
    fun `la vista previa dice que ya no aplica porque el kiosco la corrigio - se vuelve a leer, sin el texto crudo`() = runTest(dispatcher) {
        val repo: ReservationRepository = mockk()
        every { repo.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        coEvery { repo.fetchOne("r1") } returnsMany listOf(Result.success(karla()), Result.success(corregida))
        coEvery { repo.previewUndoNoShow("r1") } returns Result.failure(
            ReservationApiException(409, "La reservacion esta en estado CHECKED_IN: «Sí llegó» solo corrige un «No asistió»", code = "NO_SHOW_UNDO_NOT_APPLICABLE"),
        )
        every { repo.notifyChanged() } returns Unit
        val vm = buildVm(repo)
        advanceUntilIdle()

        vm.undoNoShow(); advanceUntilIdle()

        assertNull(vm.undo.ui.value.prompt)
        assertNull(vm.undo.ui.value.notice)
        assertEquals("Ya estaba corregida", vm.undo.ui.value.done?.message)
        assertEquals(ReservationStatus.CHECKED_IN, vm.state.value.reservation?.status)
        coVerify(exactly = 0) { repo.undoNoShow(any(), any()) }
    }
}
