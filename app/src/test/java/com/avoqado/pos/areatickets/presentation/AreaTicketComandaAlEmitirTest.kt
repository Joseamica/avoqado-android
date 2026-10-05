package com.avoqado.pos.areatickets.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketException
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketScanData
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.printing.ComandaMoment
import com.avoqado.pos.core.domain.printing.FulfillmentMode
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.RoutableItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * La Galeterie (5-oct): al emitir el vale de su Cafetería tienen que salir también las comandas de Cocina y Bebidas,
 * sin código de barras. El área prepara ANTES de que el cliente pague en la otra caja, así que la comanda sale al
 * EMITIR (tabla de [com.avoqado.pos.core.domain.printing.AreaComandaPolicy]). La pieza ya existía
 * ([ComandaDispatcher.dispatchAreaComanda]); nadie la llamaba.
 */
class AreaTicketComandaAlEmitirTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<AreaTicketRepository>()
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val comandaDispatcher = mockk<ComandaDispatcher>()
    private val lineas = slot<List<RoutableItem>>()

    private fun viewModel(): AreaTicketOperationsViewModel {
        every { secureStorage.venueId } returns "venue-1"
        coEvery { repository.settings() } returns settings()
        coEvery { repository.issue(any(), any()) } returns ticket()
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns
            mockk<SavedPrinter>(relaxed = true)
        coEvery { printerService.printAreaTicket(any(), any(), any()) } returns Unit
        coEvery {
            comandaDispatcher.dispatchAreaComanda(any(), capture(lineas), any(), any(), any(), any(), any(), any())
        } returns true
        return AreaTicketOperationsViewModel(
            repository = repository,
            printerService = printerService,
            pdfGenerator = mockk(relaxed = true),
            secureStorage = secureStorage,
            comandaDispatcher = comandaDispatcher,
        )
    }

    @Test
    fun `P1 emitir el vale manda la comanda con los renglones del carrito`() = runTest {
        val vm = viewModel()

        vm.issue(CartState(items = listOf(capuccino(), sandwich()))) {}

        coVerify(exactly = 1) {
            comandaDispatcher.dispatchAreaComanda(
                venueId = "venue-1",
                lines = any(),
                areaTicketCode = "9340048086",
                areaName = "Cafetería",
                mode = FulfillmentMode.HOLD_UNTIL_PAID,
                moment = ComandaMoment.AREA_TICKET_ISSUED,
                serverName = any(),
                alCambiarEstado = any(),
            )
        }
        assertEquals(
            listOf(
                RoutableItem(
                    orderItemId = "l-cap",
                    productId = "p-cap",
                    categoryId = "cat-bebidas",
                    productName = "CAPUCCINO 500 ML",
                    quantity = 1,
                    modifiers = listOf("LECHE DESLACTOSADA"),
                ),
                RoutableItem(
                    orderItemId = "l-sand",
                    productId = "p-sand",
                    categoryId = "cat-alimentos",
                    productName = "SANDWICH MAWI",
                    quantity = 2,
                    notes = "sin cebolla",
                ),
            ),
            lineas.captured,
        )
    }

    @Test
    fun `P1 la comanda sale aunque el papel del vale falle`() = runTest {
        // El vale ya existe en el servidor: la cocina tiene que enterarse aunque la impresora de la barra no conteste.
        val vm = viewModel()
        coEvery { printerService.printAreaTicket(any(), any(), any()) } throws IllegalStateException("Sin papel")

        vm.issue(CartState(items = listOf(capuccino()))) {}

        coVerify(exactly = 1) { comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si el servidor no crea el vale no sale comanda`() = runTest {
        val vm = viewModel()
        coEvery { repository.issue(any(), any()) } throws
            AreaTicketException("AREA_TICKETS_REQUIRE_CONNECTION", "Sin conexión", true)

        vm.issue(CartState(items = listOf(capuccino()))) {}

        coVerify(exactly = 0) { comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P2 reimprimir el vale no repite la comanda`() = runTest {
        // El papel del vale falla al emitir (queda pendiente de reimprimir) y la comanda ya salió UNA vez.
        val vm = viewModel()
        coEvery { printerService.printAreaTicket(any(), any(), any()) } throws IllegalStateException("Sin papel")
        vm.issue(CartState(items = listOf(capuccino()))) {}
        coEvery { printerService.printAreaTicket(any(), any(), any()) } returns Unit
        coEvery { repository.resolveCheckoutScan("9340048086") } returns
            AreaTicketScanData(type = "AREA_TICKET", code = "9340048086", ticket = ticket())

        vm.reprintPending()

        coVerify(exactly = 1) { comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P2 una comanda que no salio se le dice al cajero`() = runTest {
        val vm = viewModel()
        val aviso = slot<(EstadoDeComanda) -> Unit>()
        coEvery {
            comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), capture(aviso))
        } answers {
            aviso.captured(EstadoDeComanda.NoSalio(listOf("Cocina"), "No hay ninguna impresora de cocina configurada.", "9340048086"))
            true
        }

        vm.issue(CartState(items = listOf(sandwich()))) {}

        val error = vm.state.value.error.orEmpty()
        assertTrue(error, error.contains("No salió la comanda de Cocina"))
        assertTrue(error, error.contains("No hay ninguna impresora de cocina configurada."))
    }

    @Test
    fun `P1 el toast del vale al cerrarse solo no se lleva el aviso de la comanda`() = runTest {
        // Codex (5-oct): el toast de éxito llama a dismiss a los 1.6 s y borraba también el aviso de cocina.
        val vm = viewModel()
        val aviso = slot<(EstadoDeComanda) -> Unit>()
        coEvery {
            comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), capture(aviso))
        } answers {
            aviso.captured(EstadoDeComanda.NoSalio(listOf("Bebidas"), "La impresora no respondió.", "9340048086"))
            true
        }

        vm.issue(CartState(items = listOf(capuccino()))) {}
        vm.dismissMessage()

        assertTrue(vm.state.value.error.orEmpty().contains("No salió la comanda de Bebidas"))
    }

    @Test
    fun `P1 cerrar la pantalla de vales no cancela la comanda que sigue reintentando`() = runTest {
        // Codex (5-oct): la comanda corría en el viewModelScope; cerrar la pantalla a media impresión la mataba.
        val vm = viewModel()
        val salida = kotlinx.coroutines.CompletableDeferred<Unit>()
        var termino = false
        coEvery {
            comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            salida.await()
            termino = true
            true
        }

        vm.issue(CartState(items = listOf(sandwich()))) {}
        androidx.lifecycle.ViewModelStore().apply { put("vales", vm) }.clear()
        salida.complete(Unit)

        assertTrue("La comanda tiene que terminar aunque la pantalla se haya cerrado", termino)
    }

    private fun capuccino() = CartItem(
        id = "l-cap",
        type = CartItemType.ProductItem("p-cap"),
        name = "CAPUCCINO 500 ML",
        unitPrice = 5000,
        categoryId = "cat-bebidas",
        selectedModifiers = listOf(SelectedModifier("g-extras", "EXTRAS", "m-desl", "LECHE DESLACTOSADA", 500)),
    )

    private fun sandwich() = CartItem(
        id = "l-sand",
        type = CartItemType.ProductItem("p-sand"),
        name = "SANDWICH MAWI",
        unitPrice = 8900,
        quantity = 2,
        categoryId = "cat-alimentos",
        itemNote = "sin cebolla",
    )

    private fun ticket() = AreaTicket(
        id = "ticket-1",
        code = "9340048086",
        status = "ISSUED",
        fulfillmentArea = AreaTicketArea(id = "area-1", name = "Cafetería", fulfillmentMode = "HOLD_UNTIL_PAID"),
        subtotal = "50.00",
        total = "50.00",
        issuedAt = "2026-10-05T21:30:06.432Z",
        lines = listOf(
            AreaTicketLine(
                id = "line-1",
                clientLineId = "l-cap",
                productId = "p-cap",
                productNameSnapshot = "CAPUCCINO 500 ML",
                quantity = "1",
                unitPrice = "50.00",
                total = "50.00",
            ),
        ),
    )

    private fun settings() = AreaTicketSettingsData(
        venueId = "venue-1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-1",
            name = "Avoqado Windows 10",
            fulfillmentArea = AreaTicketArea(
                id = "area-1",
                name = "Cafetería",
                fulfillmentMode = "HOLD_UNTIL_PAID",
                settlementRoute = "EXTERNAL",
            ),
            canIssueAreaTickets = true,
            defaultWorkspace = "AREA_OPERATIONS",
        ),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
    )
}
