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
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            settings = settings(defaultWorkspace = "AREA_OPERATIONS", enabled = false),
        )

        assertFalse(state.issueWorkspace)
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
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns
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

    private fun ticket(code: String) = AreaTicket(
        id = "ticket-1",
        code = code,
        status = "ISSUED",
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

    private fun settings(
        defaultWorkspace: String,
        enabled: Boolean = true,
        canDeliver: Boolean = false,
        areaActive: Boolean = true,
    ) = AreaTicketSettingsData(
        venueId = "venue-1",
        areaTickets = AreaTicketModuleSettings(
            entitled = true,
            enabled = enabled,
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
