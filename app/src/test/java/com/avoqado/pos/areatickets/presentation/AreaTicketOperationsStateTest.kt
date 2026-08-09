package com.avoqado.pos.areatickets.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketScanData
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.DeliveryOrderSummary
import com.avoqado.pos.areatickets.data.DeliveryResolutionData
import com.avoqado.pos.areatickets.data.AreaTicketFulfillmentResult
import com.avoqado.pos.areatickets.data.PendingFulfillmentData
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AreaTicketOperationsStateTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `area operations workspace enables issuing for an assigned terminal`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(defaultWorkspace = "AREA_OPERATIONS"),
        )

        assertTrue(state.issueWorkspace)
    }

    @Test
    fun `standard workspace keeps normal checkout even when terminal may issue`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(defaultWorkspace = "STANDARD_POS"),
        )

        assertFalse(state.issueWorkspace)
    }

    @Test
    fun `disabled module cannot activate issue workspace`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(
                defaultWorkspace = "AREA_OPERATIONS",
                enabled = false,
                canDeliver = true,
            ),
        )

        assertFalse(state.issueWorkspace)
        assertFalse(state.canConfirmDeliveryWithPaper)
        assertFalse(state.canScanDeliveryReceipt)
    }

    @Test
    fun `delivery requires an active assigned area`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(
                defaultWorkspace = "AREA_OPERATIONS",
                canDeliver = true,
                areaActive = false,
            ),
        )

        assertFalse(state.deliveryWorkspace)
        assertFalse(state.canConfirmDeliveryWithPaper)
        assertFalse(state.canScanDeliveryReceipt)
    }

    @Test
    fun `paper confirmation mode authorizes only paper delivery`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(
                defaultWorkspace = "AREA_OPERATIONS",
                canDeliver = true,
                deliveryVerificationMode = "PAPER_CONFIRMATION",
            ),
        )

        assertTrue(state.canConfirmDeliveryWithPaper)
        assertFalse(state.canScanDeliveryReceipt)
    }

    @Test
    fun `receipt scan mode authorizes only receipt scanning`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(
                defaultWorkspace = "AREA_OPERATIONS",
                canDeliver = true,
                deliveryVerificationMode = "RECEIPT_SCAN",
            ),
        )

        assertFalse(state.canConfirmDeliveryWithPaper)
        assertTrue(state.canScanDeliveryReceipt)
    }

    @Test
    fun `paper or scan mode authorizes both delivery methods`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(
                defaultWorkspace = "AREA_OPERATIONS",
                canDeliver = true,
                deliveryVerificationMode = "PAPER_OR_SCAN",
            ),
        )

        assertTrue(state.canConfirmDeliveryWithPaper)
        assertTrue(state.canScanDeliveryReceipt)
    }

    @Test
    fun `unknown delivery mode fails closed`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(
                defaultWorkspace = "AREA_OPERATIONS",
                canDeliver = true,
                deliveryVerificationMode = "SCAN_ONLY",
            ),
        )

        assertFalse(state.canConfirmDeliveryWithPaper)
        assertFalse(state.canScanDeliveryReceipt)
    }

    @Test
    fun `settings failure does not block normal checkout`() {
        val state = AreaTicketOperationsState(
            loading = false,
            error = "HTTP 409",
        )

        assertNull(state.checkoutBlockingError)
    }

    @Test
    fun `issue failure remains visible after settings loaded`() {
        val state = AreaTicketOperationsState(
            loading = false,
            settings = settings(defaultWorkspace = "AREA_OPERATIONS"),
            error = "No se pudo emitir el vale.",
        )

        assertEquals("No se pudo emitir el vale.", state.checkoutBlockingError)
    }

    @Test
    fun `issued ticket consumes cart even when no printer is configured`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        val printerService = mockk<PrinterService>()
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        coEvery { repository.settings() } returns settings(defaultWorkspace = "AREA_OPERATIONS")
        coEvery { repository.issue(any(), any()) } returns ticket()
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns null
        coEvery {
            repository.recordPrint(
                ticketId = "ticket-1",
                printed = false,
                reprint = false,
                reason = "No hay impresora de recibos configurada.",
                errorCode = "PRINTER_NOT_CONFIGURED",
            )
        } returns Unit
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = printerService,
            pdfGenerator = mockk(relaxed = true),
            secureStorage = secureStorage,
        )
        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "cart-line-1",
                    type = CartItemType.ProductItem("product-1"),
                    name = "Jamón",
                    unitPrice = 10_440,
                ),
            ),
        )
        var cartConsumed = false

        viewModel.issue(cart) { cartConsumed = true }

        assertTrue("El vale ya existe y el carrito no debe poder emitirse otra vez", cartConsumed)
        assertEquals("9340048086", viewModel.state.value.pendingReprintCode)
        assertTrue(viewModel.state.value.error?.contains("impresora", ignoreCase = true) == true)
        coVerify(exactly = 1) { repository.issue(any(), any()) }
    }

    @Test
    fun `printer failure does not reuse issued ticket key for the next cart`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        val printerService = mockk<PrinterService>()
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        val issuedKeys = mutableListOf<String>()
        coEvery { repository.settings() } returns settings(defaultWorkspace = "AREA_OPERATIONS")
        coEvery { repository.issue(any(), any()) } coAnswers {
            issuedKeys += secondArg<String>()
            ticket(code = if (issuedKeys.size == 1) "9340048086" else "9340048087")
        }
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns null
        coEvery { repository.recordPrint(any(), any(), any(), any(), any()) } returns Unit
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = printerService,
            pdfGenerator = mockk(relaxed = true),
            secureStorage = secureStorage,
        )
        val cart = CartState(
            items = listOf(
                CartItem(
                    id = "cart-line-1",
                    type = CartItemType.ProductItem("product-1"),
                    name = "Jamón",
                    unitPrice = 10_440,
                ),
            ),
        )

        viewModel.issue(cart) {}
        viewModel.dismissFeedback()
        viewModel.dismissPendingReprint()
        viewModel.issue(cart) {}

        assertEquals(2, issuedKeys.size)
        assertNotEquals(
            "Cada carrito nuevo necesita una llave nueva aunque el anterior no se imprimiera",
            issuedKeys[0],
            issuedKeys[1],
        )
    }

    @Test
    fun `checkout bootstrap does not request delivery queue`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
        )
        coEvery { repository.pendingDelivery(any()) } throws IllegalStateException("Sin permiso")

        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk<PrinterService>(relaxed = true),
            pdfGenerator = mockk<AreaTicketPdfGenerator>(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true),
        )

        assertTrue(viewModel.state.value.issueWorkspace)
        assertNull(viewModel.state.value.error)
        coVerify(exactly = 0) { repository.pendingDelivery(any()) }
    }

    @Test
    fun `latest delivery refresh wins when bootstrap finishes last`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        val bootstrapSettings = CompletableDeferred<AreaTicketSettingsData>()
        val deliverySettings = CompletableDeferred<AreaTicketSettingsData>()
        var settingsRequest = 0
        coEvery { repository.settings() } coAnswers {
            settingsRequest += 1
            if (settingsRequest == 1) bootstrapSettings.await() else deliverySettings.await()
        }
        coEvery { repository.pendingDelivery(any()) } returns PendingFulfillmentData(
            tickets = listOf(ticket(status = "PAID")),
        )
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.refresh(loadPendingDelivery = true)
        deliverySettings.complete(
            settings(defaultWorkspace = "AREA_OPERATIONS", canDeliver = true),
        )
        assertEquals(listOf("ticket-1"), viewModel.state.value.pending.map { it.id })

        bootstrapSettings.complete(
            settings(defaultWorkspace = "AREA_OPERATIONS", canDeliver = true),
        )

        assertEquals(listOf("ticket-1"), viewModel.state.value.pending.map { it.id })
    }

    @Test
    fun `dismissing pending reprint releases screen but keeps persisted recovery code`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.pendingAreaTicketPrintCode } returns "9340048086"
        coEvery { repository.settings() } returns settings(defaultWorkspace = "AREA_OPERATIONS")

        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk<PrinterService>(relaxed = true),
            pdfGenerator = mockk<AreaTicketPdfGenerator>(relaxed = true),
            secureStorage = secureStorage,
        )

        assertEquals("9340048086", viewModel.state.value.pendingReprintCode)

        viewModel.dismissPendingReprint()

        assertNull(viewModel.state.value.pendingReprintCode)
        verify(exactly = 0) { secureStorage.pendingAreaTicketPrintCode = null }
    }

    @Test
    fun `saving pending PDF records a successful digital output and clears recovery`() = runTest {
        val code = "9340048086"
        val repository = mockk<AreaTicketRepository>()
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        val pdfGenerator = mockk<AreaTicketPdfGenerator>()
        every { secureStorage.pendingAreaTicketPrintCode } returns code
        every { secureStorage.venueDisplayName } returns "Restaurante El Atole"
        coEvery { repository.settings() } returns settings(defaultWorkspace = "AREA_OPERATIONS")
        coEvery { repository.resolveCheckoutScan(code) } returns AreaTicketScanData(
            type = "AREA_TICKET",
            code = code,
            ticket = ticket(code),
        )
        every { pdfGenerator.generate(any(), any()) } returns "%PDF-test".encodeToByteArray()
        coEvery {
            repository.recordPrint(
                ticketId = "ticket-1",
                printed = true,
                reprint = false,
                reason = "Vale guardado como PDF por el operador.",
            )
        } returns Unit

        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk<PrinterService>(relaxed = true),
            pdfGenerator = pdfGenerator,
            secureStorage = secureStorage,
        )

        viewModel.preparePendingPdf()
        withTimeout(5_000) {
            while (viewModel.state.value.pdfExport == null) yield()
        }

        assertEquals("vale-area-$code.pdf", viewModel.state.value.pdfExport?.fileName)
        var issued = false
        viewModel.confirmPendingPdfSaved { issued = true }

        assertTrue(issued)
        assertNull(viewModel.state.value.pendingReprintCode)
        assertEquals("Vale $code guardado como PDF.", viewModel.state.value.message)
        verify { secureStorage.pendingAreaTicketPrintCode = null }
        coVerify {
            repository.recordPrint(
                ticketId = "ticket-1",
                printed = true,
                reprint = false,
                reason = "Vale guardado como PDF por el operador.",
            )
        }
    }

    @Test
    fun `successful recovery reprint clears the original cart through callback`() = runTest {
        val code = "9340048086"
        val repository = mockk<AreaTicketRepository>()
        val printerService = mockk<PrinterService>()
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.pendingAreaTicketPrintCode } returns code
        every { secureStorage.venueDisplayName } returns "Restaurante El Atole"
        coEvery { repository.settings() } returns settings(defaultWorkspace = "AREA_OPERATIONS")
        coEvery { repository.resolveCheckoutScan(code) } returns AreaTicketScanData(
            type = "AREA_TICKET",
            code = code,
            ticket = ticket(code),
        )
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns
            mockk<SavedPrinter>(relaxed = true)
        coEvery { printerService.printAreaTicket(any(), any(), any()) } returns Unit
        coEvery { repository.recordPrint(any(), any(), any(), any(), any()) } returns Unit

        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = printerService,
            pdfGenerator = mockk(relaxed = true),
            secureStorage = secureStorage,
        )

        var cartCleared = false
        viewModel.reprintPending { cartCleared = true }

        assertTrue(cartCleared)
        assertNull(viewModel.state.value.pendingReprintCode)
        assertEquals("Vale $code reimpreso correctamente.", viewModel.state.value.message)
        verify { secureStorage.pendingAreaTicketPrintCode = null }
    }

    @Test
    fun `paper delivery is rejected when receipt scanning is required`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "RECEIPT_SCAN",
        )
        coEvery { repository.fulfill(any(), any()) } returns AreaTicketFulfillmentResult(
            alreadyFulfilled = false,
        )
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverWithPaper("ticket-1")

        assertTrue(viewModel.state.value.error != null)
        assertFalse(viewModel.state.value.submitting)
        coVerify(exactly = 0) { repository.fulfill(any(), any()) }
    }

    @Test
    fun `paper delivery warns and refreshes when another terminal delivered first`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "PAPER_CONFIRMATION",
        )
        coEvery { repository.fulfill("ticket-1", scannedReceipt = false) } returns
            AreaTicketFulfillmentResult(alreadyFulfilled = true)
        coEvery { repository.pendingDelivery(any()) } returns PendingFulfillmentData()
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverWithPaper("ticket-1")

        assertNull(viewModel.state.value.message)
        assertTrue(viewModel.state.value.error?.contains("otra terminal") == true)
        coVerify(exactly = 1) { repository.pendingDelivery(any()) }
    }

    @Test
    fun `receipt scan is rejected when paper confirmation is required`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "PAPER_CONFIRMATION",
        )
        coEvery { repository.resolveDelivery(any()) } returns deliveryResolution(ticket(status = "PAID"))
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverByReceiptCode("8123456789")

        assertTrue(viewModel.state.value.error != null)
        assertFalse(viewModel.state.value.submitting)
        coVerify(exactly = 0) { repository.resolveDelivery(any()) }
    }

    @Test
    fun `receipt scan delivers and reports only paid tickets`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "RECEIPT_SCAN",
        )
        coEvery { repository.resolveDelivery("8123456789") } returns deliveryResolution(
            ticket(code = "paid-code", status = "PAID", id = "paid-ticket"),
            ticket(code = "issued-code", status = "ISSUED", id = "issued-ticket"),
        )
        coEvery { repository.fulfill("paid-ticket", scannedReceipt = true) } returns
            AreaTicketFulfillmentResult(alreadyFulfilled = false)
        coEvery { repository.pendingDelivery(any()) } returns PendingFulfillmentData()
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverByReceiptCode("8123456789")

        assertEquals("1 vale entregado.", viewModel.state.value.message)
        coVerify(exactly = 1) { repository.fulfill("paid-ticket", scannedReceipt = true) }
        coVerify(exactly = 0) { repository.fulfill("issued-ticket", any()) }
    }

    @Test
    fun `receipt scan counts only new deliveries and warns about stale tickets`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "RECEIPT_SCAN",
        )
        coEvery { repository.resolveDelivery("8123456789") } returns deliveryResolution(
            ticket(code = "new-code", status = "PAID", id = "new-ticket"),
            ticket(code = "stale-code", status = "PAID", id = "stale-ticket"),
        )
        coEvery { repository.fulfill("new-ticket", scannedReceipt = true) } returns
            AreaTicketFulfillmentResult(alreadyFulfilled = false)
        coEvery { repository.fulfill("stale-ticket", scannedReceipt = true) } returns
            AreaTicketFulfillmentResult(alreadyFulfilled = true)
        coEvery { repository.pendingDelivery(any()) } returns PendingFulfillmentData()
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverByReceiptCode("8123456789")

        assertNull(viewModel.state.value.message)
        assertTrue(viewModel.state.value.error?.contains("1 vale entregado") == true)
        assertTrue(viewModel.state.value.error?.contains("otra terminal") == true)
        coVerify(exactly = 1) { repository.pendingDelivery(any()) }
    }

    @Test
    fun `receipt scan refreshes pending and reports partial progress after failure`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "RECEIPT_SCAN",
        )
        coEvery { repository.resolveDelivery("8123456789") } returns deliveryResolution(
            ticket(code = "first-code", status = "PAID", id = "first-ticket"),
            ticket(code = "second-code", status = "PAID", id = "second-ticket"),
        )
        coEvery { repository.fulfill("first-ticket", scannedReceipt = true) } returns
            AreaTicketFulfillmentResult(alreadyFulfilled = false)
        coEvery { repository.fulfill("second-ticket", scannedReceipt = true) } throws
            IllegalStateException("Sin conexión")
        coEvery { repository.pendingDelivery(any()) } returns PendingFulfillmentData()
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverByReceiptCode("8123456789")

        assertNull(viewModel.state.value.message)
        assertTrue(viewModel.state.value.error?.contains("1 vale entregado") == true)
        assertTrue(viewModel.state.value.error?.contains("Sin conexión") == true)
        coVerify(exactly = 1) { repository.pendingDelivery(any()) }
    }

    @Test
    fun `partial delivery does not claim reconciliation when pending refresh fails`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "RECEIPT_SCAN",
        )
        coEvery { repository.resolveDelivery("8123456789") } returns deliveryResolution(
            ticket(code = "first-code", status = "PAID", id = "first-ticket"),
            ticket(code = "second-code", status = "PAID", id = "second-ticket"),
        )
        coEvery { repository.fulfill("first-ticket", scannedReceipt = true) } returns
            AreaTicketFulfillmentResult(alreadyFulfilled = false)
        coEvery { repository.fulfill("second-ticket", scannedReceipt = true) } throws
            IllegalStateException("Sin conexión al entregar")
        coEvery { repository.pendingDelivery(any()) } throws
            IllegalStateException("Sin conexión al actualizar")
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverByReceiptCode("8123456789")

        val error = viewModel.state.value.error.orEmpty()
        assertTrue(error.contains("1 vale entregado"))
        assertTrue(error.contains("Sin conexión al entregar"))
        assertTrue(error.contains("Sin conexión al actualizar"))
        assertFalse(error.contains("La lista fue actualizada"))
    }

    @Test
    fun `receipt scan without paid tickets reports an error`() = runTest {
        val repository = mockk<AreaTicketRepository>()
        coEvery { repository.settings() } returns settings(
            defaultWorkspace = "AREA_OPERATIONS",
            canDeliver = true,
            deliveryVerificationMode = "RECEIPT_SCAN",
        )
        coEvery { repository.resolveDelivery("8123456789") } returns deliveryResolution(
            ticket(code = "issued-code", status = "ISSUED", id = "issued-ticket"),
        )
        coEvery { repository.pendingDelivery(any()) } returns PendingFulfillmentData()
        val viewModel = AreaTicketOperationsViewModel(
            repository = repository,
            printerService = mockk(relaxed = true),
            pdfGenerator = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
        )

        viewModel.deliverByReceiptCode("8123456789")

        assertTrue(viewModel.state.value.error != null)
        assertNull(viewModel.state.value.message)
        coVerify(exactly = 0) { repository.fulfill(any(), any()) }
    }

    private fun ticket(
        code: String = "9340048086",
        status: String = "ISSUED",
        id: String = "ticket-1",
    ) = AreaTicket(
        id = id,
        code = code,
        status = status,
        fulfillmentArea = AreaTicketArea(
            id = "area-1",
            name = "Cremería",
            fulfillmentMode = "HOLD_UNTIL_PAID",
        ),
        subtotal = "104.40",
        total = "104.40",
        issuedAt = "2026-07-31T01:30:06.432Z",
        lines = listOf(
            AreaTicketLine(
                id = "line-1",
                clientLineId = "client-line-1",
                productId = "product-1",
                productNameSnapshot = "QA Jamón por kg",
                quantity = "1.000",
                weightKg = "0.435",
                unitPrice = "240.00",
                total = "104.40",
            ),
        ),
    )

    private fun deliveryResolution(vararg tickets: AreaTicket) = DeliveryResolutionData(
        order = DeliveryOrderSummary(
            id = "order-1",
            orderNumber = "A-1",
            paymentStatus = "PAID",
        ),
        fulfillmentArea = AreaTicketArea(
            id = "area-1",
            name = "Cremería",
            fulfillmentMode = "HOLD_UNTIL_PAID",
        ),
        tickets = tickets.toList(),
    )

    private fun settings(
        defaultWorkspace: String,
        enabled: Boolean = true,
        canDeliver: Boolean = false,
        areaActive: Boolean = true,
        deliveryVerificationMode: String = "PAPER_OR_SCAN",
    ) = AreaTicketSettingsData(
        venueId = "venue-1",
        areaTickets = AreaTicketModuleSettings(
            entitled = true,
            enabled = enabled,
            deliveryVerificationMode = deliveryVerificationMode,
        ),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-1",
            name = "Cremería",
            fulfillmentArea = AreaTicketArea(
                id = "area-1",
                name = "Cremería",
                fulfillmentMode = "HOLD_UNTIL_PAID",
                active = areaActive,
            ),
            canIssueAreaTickets = true,
            canDeliverAreaTickets = canDeliver,
            defaultWorkspace = defaultWorkspace,
        ),
        scaleIntegration = ScaleIntegrationSettings(
            entitled = false,
            enabled = false,
        ),
    )
}
