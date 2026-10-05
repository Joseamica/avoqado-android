package com.avoqado.pos.areatickets.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketException
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Task 12 (caja externa): al tocar «emitir vale», (1) el carrito que el vale no puede representar
 * se rechaza ANTES de llamar al servidor (y lo que sí representa, como el descuento por renglón, sigue
 * pasando), (2) un conflicto de llave rota la llave para que el área no quede trabada y avisa de un vale
 * que pudo quedar creado, mientras que un fallo de red la conserva para que el reintento se deduplique,
 * (3) sin red el mensaje depende de la ruta VIGENTE del área (la de los ajustes de la terminal), no de la
 * de un vale.
 */
class AreaTicketIssueGuardTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<AreaTicketRepository>()
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    private fun viewModel(settlementRoute: String = "AVOQADO"): AreaTicketOperationsViewModel {
        // Un negocio de verdad: el "" del mock relajado es «sin negocio» y el registro del papel no se guardaría.
        every { secureStorage.venueId } returns "venue-1"
        coEvery { repository.settings() } returns settings(settlementRoute)
        // Por default el servidor acepta y la impresión sale bien: cada prueba sólo sobreescribe lo que le toca.
        coEvery { repository.issue(any(), any()) } returns ticket()
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns
            mockk<SavedPrinter>(relaxed = true)
        coEvery { printerService.printAreaTicket(any(), any(), any()) } returns Unit
        return AreaTicketOperationsViewModel(
            repository = repository,
            printerService = printerService,
            pdfGenerator = mockk(relaxed = true),
            secureStorage = secureStorage,
            comandaDispatcher = mockk(relaxed = true),
        )
    }

    // -- 1. Lo que el vale no puede representar se rechaza antes de mandar --------------------

    @Test
    fun `P1 una cortesia no llega al servidor`() = runTest {
        assertRechazado(CartState(items = listOf(renglon(isCortesia = true))))
    }

    @Test
    fun `P1 un precio manual no llega al servidor`() = runTest {
        // Cero también es un precio manual (una cortesía disfrazada): cuenta cualquier valor, no sólo los positivos.
        assertRechazado(CartState(items = listOf(renglon(priceAdjustment = 0))))
    }

    @Test
    fun `P1 una promocion no llega al servidor`() = runTest {
        assertRechazado(CartState(items = listOf(renglon(promotionInstanceId = "promo-1"))))
    }

    @Test
    fun `P1 un descuento de cuenta no llega al servidor`() = runTest {
        assertRechazado(
            CartState(
                items = listOf(renglon()),
                orderDiscount = Discount(id = "d1", name = "10%", value = 10.0),
            ),
        )
    }

    @Test
    fun `P1 un premio de cartilla no llega al servidor`() = runTest {
        assertRechazado(
            CartState(
                items = listOf(renglon()),
                pendingStampReward = PremioPorAplicar(id = "rw1", etiqueta = "Café gratis", tipo = "FREE_PRODUCT", valor = null),
            ),
        )
    }

    @Test
    fun `P1 un impuesto agregado no llega al servidor`() = runTest {
        // «Agregar impuesto» del carrito entra al total y al botón «Emitir vale $63.80», pero el vale no lo
        // lleva: el servidor lo cotiza del catálogo y saldría de $55.00.
        assertRechazado(CartState(items = listOf(renglon()), orderTaxPercent = 16))
    }

    @Test
    fun `un producto normal si llega al servidor`() = runTest {
        val vm = viewModel()
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon()))) { carritoConsumido = true }

        coVerify(exactly = 1) { repository.issue(any(), any()) }
        assertTrue(carritoConsumido)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `P2 un descuento de catalogo por renglon si llega al servidor`() = runTest {
        // El descuento POR RENGLÓN sí lo representa el vale (viaja su id y el servidor lo recalcula): la guarda
        // no debe confundirlo con los descuentos de cuenta, que son los que se pierden.
        val lineasEnviadas = slot<List<IssueAreaTicketLineRequest>>()
        val vm = viewModel()
        coEvery { repository.issue(capture(lineasEnviadas), any()) } returns ticket()
        val conDescuento = renglon().copy(
            itemDiscountId = "desc-10",
            itemDiscountType = "PERCENTAGE",
            itemDiscountValue = 10.0,
            itemDiscountName = "10% empleados",
        )
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(conDescuento))) { carritoConsumido = true }

        coVerify(exactly = 1) { repository.issue(any(), any()) }
        assertEquals("desc-10", lineasEnviadas.captured.single().discountId)
        assertTrue(carritoConsumido)
        assertNull(vm.state.value.error)
    }

    // -- 2. El conflicto de llave rota la llave ------------------------------------------------

    @Test
    fun `P1 el conflicto de llave rota la llave`() = runTest {
        // El servidor ya usó "llave-del-vale-anterior" para OTRO carrito: contesta conflicto mientras se le
        // mande esa llave, y acepta cualquier otra. Sin rotarla, el área quedaría trabada para siempre.
        val llavesUsadas = mutableSetOf("llave-del-vale-anterior")
        val llavesEnviadas = mutableListOf<String>()
        every { secureStorage.pendingAreaTicketIssueKey } returns "llave-del-vale-anterior"
        val vm = viewModel()
        coEvery { repository.issue(any(), any()) } coAnswers {
            val llave = secondArg<String>()
            llavesEnviadas += llave
            if (!llavesUsadas.add(llave)) {
                throw AreaTicketException("AREA_TICKET_IDEMPOTENCY_CONFLICT", "La llave de idempotencia ya fue utilizada…", false)
            }
            ticket()
        }
        val carrito = CartState(items = listOf(renglon()))
        var carritoConsumido = false

        vm.issue(carrito) { carritoConsumido = true }

        assertEquals(MENSAJE_CONFLICTO_DE_LLAVE, vm.state.value.error)
        assertFalse(vm.state.value.submitting)
        assertFalse(carritoConsumido)

        vm.issue(carrito) { carritoConsumido = true }

        assertEquals(2, llavesEnviadas.size)
        assertEquals("llave-del-vale-anterior", llavesEnviadas[0])
        assertNotEquals("La llave del conflicto no se puede reusar", llavesEnviadas[0], llavesEnviadas[1])
        verify { secureStorage.pendingAreaTicketIssueKey = llavesEnviadas[1] }
        assertTrue("Con la llave nueva el vale sí sale", carritoConsumido)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `P2 sin red la llave no cambia y el reintento viaja con la misma`() = runTest {
        // Un fallo de red NO prueba que el servidor no creó el vale (la respuesta pudo perderse): el reintento
        // tiene que llevar la MISMA llave para que el servidor lo deduplique. Sólo un conflicto la rota.
        val llavesEnviadas = mutableListOf<String>()
        every { secureStorage.pendingAreaTicketIssueKey } returns "llave-guardada"
        val vm = viewModel()
        coEvery { repository.issue(any(), any()) } coAnswers {
            llavesEnviadas += secondArg<String>()
            throw SIN_RED
        }
        val carrito = CartState(items = listOf(renglon()))

        vm.issue(carrito) {}
        vm.issue(carrito) {}

        assertEquals(listOf("llave-guardada", "llave-guardada"), llavesEnviadas)
        verify(exactly = 0) { secureStorage.pendingAreaTicketIssueKey = any() }
    }

    // -- 3. Sin red: el mensaje depende de la ruta VIGENTE del área ----------------------------

    @Test
    fun `sin red en area externa el operador ve que hacer`() = runTest {
        val vm = viewModel(settlementRoute = "EXTERNAL")
        coEvery { repository.issue(any(), any()) } throws SIN_RED

        vm.issue(CartState(items = listOf(renglon()))) {}

        assertEquals(
            "Sin conexión con Avoqado no se puede emitir el vale. Reintenta cuando vuelva la conexión; mientras, la caja principal puede capturar los productos a mano.",
            vm.state.value.error,
        )
        assertFalse(vm.state.value.submitting)
    }

    @Test
    fun `sin red en area normal conserva el mensaje de siempre`() = runTest {
        val vm = viewModel(settlementRoute = "AVOQADO")
        coEvery { repository.issue(any(), any()) } throws SIN_RED

        vm.issue(CartState(items = listOf(renglon()))) {}

        assertEquals(SIN_RED.message, vm.state.value.error)
        assertFalse(vm.state.value.submitting)
    }

    // -- 4. Cambio de negocio mientras sale lo pendiente (Task 13) -----------------------------

    @Test
    fun `P2 si se cambia de negocio mientras sale lo pendiente, el vale no se emite`() = runTest {
        val vm = viewModel() // cola VACÍA: el init no manda nada
        var negocio = "venue-1"
        every { secureStorage.venueId } answers { negocio }
        every { secureStorage.pendingAreaTicketPrintRecords } returns
            """[{"venueId":"venue-1","ticketId":"t-viejo","code":"9340048000","reprint":false,"idempotencyKey":"k-viejo"}]"""
        // Mientras sale el registro pendiente, el cajero cambia de negocio.
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers { negocio = "venue-2" }
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon()))) { carritoConsumido = true }

        coVerify(exactly = 0) { repository.issue(any(), any()) }
        assertFalse(carritoConsumido)
        assertFalse(vm.state.value.submitting)
        assertEquals("Cambiaste de negocio mientras se emitía el vale. Vuelve a intentarlo.", vm.state.value.error)
    }

    // -- helpers --------------------------------------------------------------------------------

    private fun assertRechazado(carrito: CartState) {
        val vm = viewModel()
        var carritoConsumido = false

        vm.issue(carrito) { carritoConsumido = true }

        assertEquals(MENSAJE_DE_RECHAZO_PREVIO, vm.state.value.error)
        assertFalse(vm.state.value.submitting)
        assertFalse(carritoConsumido)
        coVerify(exactly = 0) { repository.issue(any(), any()) }
    }

    private fun renglon(
        isCortesia: Boolean = false,
        priceAdjustment: Int? = null,
        promotionInstanceId: String? = null,
    ) = CartItem(
        id = "cart-line-1",
        type = CartItemType.ProductItem("p1"),
        name = "Latte",
        unitPrice = 5500,
        isCortesia = isCortesia,
        priceAdjustment = priceAdjustment,
        promotionInstanceId = promotionInstanceId,
    )

    private fun ticket() = AreaTicket(
        id = "ticket-1",
        code = "9340048086",
        status = "ISSUED",
        fulfillmentArea = AreaTicketArea(id = "area-1", name = "Cremería", fulfillmentMode = "HOLD_UNTIL_PAID"),
        subtotal = "55.00",
        total = "55.00",
        issuedAt = "2026-07-31T01:30:06.432Z",
        lines = listOf(
            AreaTicketLine(
                id = "line-1",
                clientLineId = "cart-line-1",
                productId = "p1",
                productNameSnapshot = "Latte",
                quantity = "1",
                unitPrice = "55.00",
                total = "55.00",
            ),
        ),
    )

    private fun settings(settlementRoute: String) = AreaTicketSettingsData(
        venueId = "venue-1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-1",
            name = "Cremería",
            fulfillmentArea = AreaTicketArea(
                id = "area-1",
                name = "Cremería",
                fulfillmentMode = "HOLD_UNTIL_PAID",
                settlementRoute = settlementRoute,
            ),
            canIssueAreaTickets = true,
            defaultWorkspace = "AREA_OPERATIONS",
        ),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
    )

    private companion object {
        const val MENSAJE_DE_RECHAZO_PREVIO =
            "El vale sólo puede llevar productos del área a su precio. Quita cortesías, precios manuales, " +
                "promociones, descuentos de cuenta, impuestos agregados o premios; se aplican al cobrar."

        const val MENSAJE_CONFLICTO_DE_LLAVE =
            "Un vale anterior pudo quedar creado sin imprimirse. Revísalo en el dashboard antes de cobrarlo " +
                "otra vez; ya puedes emitir este de nuevo."

        // Lo mismo que lanza AreaTicketRepository.request ante un IOException.
        val SIN_RED = AreaTicketException(
            "AREA_TICKETS_REQUIRE_CONNECTION",
            "Los vales por área requieren conexión con Avoqado. El POS normal sigue disponible; reintenta el vale cuando vuelva el servidor.",
            true,
        )
    }
}
