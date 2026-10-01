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
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Caja externa (spec D14, carry de la Task 10): la REIMPRESIÓN sale con «*** COPIA ***» y el original no. Fija el
 * cableado del ViewModel sobre un vale EXTERNAL: emitir imprime el original, reimprimir el pendiente imprime la copia
 * (el aparato no sabe si el primer papel salió) y el PDF —que sale cuando el papel no salió— no es copia.
 */
class AreaTicketReprintWiringTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<AreaTicketRepository>()
    private val printerService = mockk<PrinterService>()
    private val pdfGenerator = mockk<AreaTicketPdfGenerator>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val impreso = slot<AreaTicketData>()

    @Before
    fun setUp() {
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.pendingAreaTicketPrintCode } returns null
        every { secureStorage.pendingAreaTicketPrintVenueId } returns null
        every { secureStorage.commitAreaTicketPrintRecords(any(), any()) } returns true
        coEvery { repository.settings() } returns settings()
        coEvery { repository.issue(any(), any()) } returns VALE_EXTERNO
        coEvery { repository.resolveCheckoutScan(CODIGO) } returns
            AreaTicketScanData(type = "AREA_TICKET", code = CODIGO, ticket = VALE_EXTERNO)
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns
            mockk<SavedPrinter>(relaxed = true)
        coEvery { printerService.printAreaTicket(capture(impreso), any(), any()) } returns Unit
    }

    @Test
    fun `P2 emitir imprime el original sin COPIA`() = runTest {
        val vm = viewModel()

        vm.issue(CartState(items = listOf(CartItem(id = "c1", type = CartItemType.ProductItem("p1"), name = "Latte", unitPrice = 5500)))) {}

        assertTrue(impreso.captured.externalRoute)
        assertFalse(impreso.captured.isReprint)
    }

    @Test
    fun `P2 reimprimir el pendiente sale con COPIA`() = runTest {
        every { secureStorage.pendingAreaTicketPrintCode } returns CODIGO
        val vm = viewModel()

        vm.reprintPending {}

        assertTrue(impreso.captured.externalRoute)
        assertTrue(impreso.captured.isReprint)
    }

    @Test
    fun `P2 el PDF del pendiente sale sin COPIA`() = runTest {
        every { secureStorage.pendingAreaTicketPrintCode } returns CODIGO
        val enPdf = slot<AreaTicketData>()
        every { pdfGenerator.generate(capture(enPdf), any()) } returns "%PDF-test".encodeToByteArray()
        val vm = viewModel()

        vm.preparePendingPdf()
        withTimeout(5_000) {
            while (vm.state.value.pdfExport == null) yield()
        }

        assertTrue(enPdf.captured.externalRoute)
        assertFalse(enPdf.captured.isReprint)
    }

    private fun viewModel() = AreaTicketOperationsViewModel(
        repository = repository,
        printerService = printerService,
        pdfGenerator = pdfGenerator,
        secureStorage = secureStorage,
    )

    private fun settings() = AreaTicketSettingsData(
        venueId = "v1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-1",
            name = "Cafetería",
            fulfillmentArea = AreaTicketArea(
                id = "a1",
                name = "Cafetería",
                fulfillmentMode = "IMMEDIATE",
                settlementRoute = "EXTERNAL",
            ),
            canIssueAreaTickets = true,
            defaultWorkspace = "AREA_OPERATIONS",
        ),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
    )

    private companion object {
        const val CODIGO = "9470000015"

        val VALE_EXTERNO = AreaTicket(
            id = "t1",
            code = CODIGO,
            status = "ISSUED",
            fulfillmentArea = AreaTicketArea(id = "a1", name = "Cafetería", fulfillmentMode = "IMMEDIATE"),
            subtotal = "55.00",
            total = "55.00",
            issuedAt = "2026-09-30T20:32:00.000Z",
            lines = listOf(
                AreaTicketLine(
                    id = "l1",
                    clientLineId = "c1",
                    productId = "p1",
                    productNameSnapshot = "Latte",
                    skuSnapshot = "P000500",
                    quantity = "1",
                    unitPrice = "55.00",
                    total = "55.00",
                ),
            ),
            settlementRoute = "EXTERNAL",
        )
    }
}
