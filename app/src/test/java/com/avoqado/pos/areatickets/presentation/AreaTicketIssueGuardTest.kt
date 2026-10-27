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
 * pasando), (2) un conflicto de llave NO rota la llave (D2: con llave nueva el carrito cambiado era un segundo vale):
 * avisa que se deje el carrito como estaba o se vacíe, y un fallo de red también la conserva para que el reintento se deduplique,
 * (3) sin red el mensaje depende de la ruta VIGENTE del área (la de los ajustes de la terminal), no de la
 * de un vale.
 */
class AreaTicketIssueGuardTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<AreaTicketRepository>()
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true) { every { guardarLlaveDeEmisionDeVales(any(), any()) } returns true } // D2: el disco guarda la llave

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

    // D1 (vales-ciclo, Codex r4): la copia guardada se marca sólo si el vale de verdad se pide. Un carrito que la guarda rechaza
    // (impuesto agregado) no deja la marca, que al restaurar diría «ya se mandó a cobrar» sin que nada se haya mandado.
    @Test
    fun `D1 la copia guardada se marca solo cuando el vale se pide`() = runTest {
        val vm = viewModel()
        var marcas = 0

        vm.issue(CartState(items = listOf(renglon()), orderTaxPercent = 16), antesDeEmitir = { marcas++; true }) {}
        assertEquals(0, marcas)
        coVerify(exactly = 0) { repository.issue(any(), any()) }

        vm.issue(CartState(items = listOf(renglon())), antesDeEmitir = { marcas++; false }) {}
        assertEquals(1, marcas)
        coVerify(exactly = 0) { repository.issue(any(), any()) }
        assertEquals("No se pudo preparar el vale del carrito guardado. Intenta de nuevo.", vm.state.value.error)

        vm.issue(CartState(items = listOf(renglon())), antesDeEmitir = { marcas++; true }) {}
        assertEquals(2, marcas)
        coVerify(exactly = 1) { repository.issue(any(), any()) }
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

    // -- 2. D2 (vales-ciclo): el conflicto de llave NO rota la llave ----------------------------

    /**
     * El disco del aparato, con una llave POR NEGOCIO, y un servidor que recuerda qué carrito creó cada llave en cada negocio
     * (deduplica por negocio + llave y compara el contenido). `discoGuarda = false`: el disco de WINDOWS que falla deja lo de antes.
     */
    private var negocio = "venue-1"
    private val llavesGuardadas = mutableMapOf<String, String?>()
    private var discoGuarda = true
    private val carritoPorLlave = mutableMapOf<String, String>()

    /** Después de `viewModel()`, que pisa el negocio y el servidor. */
    private fun aparatoYServidor() {
        every { secureStorage.venueId } answers { negocio }
        every { secureStorage.llaveDeEmisionDeVales(any()) } answers { llavesGuardadas[firstArg()] }
        every { secureStorage.guardarLlaveDeEmisionDeVales(any(), any()) } answers {
            if (discoGuarda) llavesGuardadas[firstArg()] = secondArg()
            discoGuarda
        }
        every { secureStorage.soltarLlaveDeEmisionDeVales(any(), any()) } answers {
            val siEs = secondArg<String?>()
            if (siEs == null || llavesGuardadas[firstArg()] == siEs) llavesGuardadas.remove(firstArg<String>())
        }
        coEvery { repository.issue(any(), any()) } coAnswers {
            val contenido = firstArg<List<IssueAreaTicketLineRequest>>().joinToString { "${it.clientLineId}×${it.quantity}" }
            when (carritoPorLlave.putIfAbsent("$negocio|${secondArg<String>()}", contenido)) {
                null, contenido -> ticket() // llave nueva: vale nuevo · mismo contenido: el MISMO vale
                else -> throw AreaTicketException("AREA_TICKET_IDEMPOTENCY_CONFLICT", "La llave de idempotencia ya fue utilizada…", false)
            }
        }
    }

    /** El vale que A creó con [llave] y cuya respuesta se perdió. */
    private fun valePerdido(llave: String = "llave-del-vale-perdido", venue: String = "venue-1") {
        llavesGuardadas[venue] = llave
        carritoPorLlave["$venue|$llave"] = "cart-line-1×1"
    }

    @Test
    fun `D2 el conflicto de llave no rota la llave - el carrito cambiado no crea otro vale y el de antes lo recupera`() = runTest {
        // El vale salió con 1 Latte, su respuesta se perdió y el cajero subió a 2. Antes el 409 rotaba la llave y el
        // siguiente toque creaba un SEGUNDO vale con todo (mercancía repetida).
        val vm = viewModel()
        aparatoYServidor()
        valePerdido()
        val cambiado = CartState(items = listOf(renglon().copy(quantity = 2)))
        var carritoConsumido = false

        vm.issue(cambiado) { carritoConsumido = true }
        vm.issue(cambiado) { carritoConsumido = true }

        assertEquals(MENSAJE_CONFLICTO_DE_LLAVE, vm.state.value.error)
        assertFalse(vm.state.value.submitting)
        assertFalse(carritoConsumido)
        assertEquals("Ningún vale nuevo", 1, carritoPorLlave.size)
        assertEquals("llave-del-vale-perdido", llavesGuardadas["venue-1"])

        // Deja el carrito como estaba: el servidor devuelve el MISMO vale, y entonces la llave se suelta para el siguiente.
        vm.issue(CartState(items = listOf(renglon()))) { carritoConsumido = true }

        assertTrue(carritoConsumido)
        assertNull(vm.state.value.error)
        assertEquals("Sigue habiendo un solo vale", 1, carritoPorLlave.size)
        assertNull(llavesGuardadas["venue-1"])
    }

    @Test
    fun `D2 al vaciar el carrito la llave se lee del aparato - el vale nuevo sale al primer toque`() = runTest {
        // Vaciar a mano suelta la llave en el aparato (CartViewModel.vaciar). La pantalla de vales no puede seguir
        // con una copia en memoria de la llave vieja: chocaría con 409 sin salida.
        valePerdido()
        val vm = viewModel()
        aparatoYServidor()
        llavesGuardadas.remove("venue-1") // el cajero vació el carrito
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon().copy(quantity = 2)))) { carritoConsumido = true }

        assertNull(vm.state.value.error)
        assertTrue(carritoConsumido)
        assertEquals("Un vale nuevo, con llave nueva", 2, carritoPorLlave.size)
    }

    @Test
    fun `D2 si el disco no guarda la llave nueva, el vale no se pide`() = runTest {
        // Codex r6 #1 (WINDOWS): el disco que falla deja lo de antes (null). Si el vale se pedía con esa llave y su respuesta
        // se perdía, el reintento fabricaba OTRA llave: segundo vale.
        val vm = viewModel()
        aparatoYServidor()
        discoGuarda = false
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon()))) { carritoConsumido = true }

        coVerify(exactly = 0) { repository.issue(any(), any()) }
        assertEquals("No se pudo preparar el vale. Intenta de nuevo.", vm.state.value.error)
        assertFalse(vm.state.value.submitting)
        assertFalse(carritoConsumido)
    }

    @Test
    fun `D2 con la llave en memoria pero el disco fallando, el vale tampoco se pide`() = runTest {
        // Codex r8 #2 (ANDROID): tras un commit fallido la llave queda en la memoria del proceso pero no en disco. Si el siguiente
        // toque la usaba sin volver a guardarla, al morir el proceso tras una respuesta perdida el reintento salía con otra.
        val vm = viewModel()
        aparatoYServidor()
        llavesGuardadas["venue-1"] = "llave-solo-en-memoria"
        discoGuarda = false
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon()))) { carritoConsumido = true }

        coVerify(exactly = 0) { repository.issue(any(), any()) }
        assertEquals("No se pudo preparar el vale. Intenta de nuevo.", vm.state.value.error)
        assertFalse(carritoConsumido)
    }

    @Test
    fun `D2 la llave se toma al tocar - si otra pantalla la suelta mientras sale lo pendiente, el reintento usa la misma`() = runTest {
        // Codex r10 #1: la llave se tomaba DESPUÉS de esperar los registros pendientes. Si mientras tanto otra pantalla recuperaba
        // el vale y soltaba la llave, este reintento (con el carrito cambiado) salía con una nueva: segundo vale.
        val vm = viewModel() // cola vacía al nacer
        aparatoYServidor()
        valePerdido()
        every { secureStorage.pendingAreaTicketPrintRecords } returns
            """[{"venueId":"venue-1","ticketId":"t-viejo","code":"9340048000","reprint":false,"idempotencyKey":"k-viejo"}]"""
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers {
            llavesGuardadas.remove("venue-1") // la otra pantalla recuperó el vale y soltó la llave
        }
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon().copy(quantity = 2)))) { carritoConsumido = true }

        assertEquals("Ningún vale nuevo", 1, carritoPorLlave.size)
        assertFalse(carritoConsumido)
        assertEquals(MENSAJE_CONFLICTO_DE_LLAVE, vm.state.value.error)
    }

    @Test
    fun `D2 confirmar un vale en otro negocio no suelta la llave del vale perdido`() = runTest {
        // Codex r6 #2: el servidor deduplica por negocio + llave. Con UNA llave en el aparato, el vale de B (con la llave de A)
        // salía, la llave se soltaba y el reintento de A creaba un SEGUNDO vale.
        val vm = viewModel()
        aparatoYServidor()
        valePerdido(venue = "venue-1")
        negocio = "venue-2"
        var deB = false
        vm.issue(CartState(items = listOf(renglon()))) { deB = true }
        assertTrue(deB)

        negocio = "venue-1"
        var deA = false
        vm.issue(CartState(items = listOf(renglon()))) { deA = true }

        assertTrue("A recupera SU vale", deA)
        assertEquals("Un vale en A y uno en B, ninguno repetido", 2, carritoPorLlave.size)
        assertEquals(1, carritoPorLlave.keys.count { it.startsWith("venue-1|") })
    }

    @Test
    fun `D2 una confirmacion que llega tarde no suelta la llave de otra emision`() = runTest {
        // Codex r7 #4: otra pantalla ya soltó la llave de este vale y pidió otro con la suya, cuya respuesta se perdió. Si esta
        // confirmación borraba «la llave del negocio», el reintento de la otra salía con llave nueva: segundo vale.
        val vm = viewModel()
        aparatoYServidor()
        llavesGuardadas["venue-1"] = "llave-de-esta-pantalla"
        coEvery { repository.issue(any(), any()) } coAnswers {
            llavesGuardadas["venue-1"] = "llave-de-la-otra-pantalla" // mientras tanto, en la otra pantalla
            ticket()
        }
        var carritoConsumido = false

        vm.issue(CartState(items = listOf(renglon()))) { carritoConsumido = true }

        assertTrue(carritoConsumido)
        assertEquals("llave-de-la-otra-pantalla", llavesGuardadas["venue-1"])
    }

    @Test
    fun `P2 sin red la llave no cambia y el reintento viaja con la misma`() = runTest {
        // Un fallo de red NO prueba que el servidor no creó el vale (la respuesta pudo perderse): el reintento
        // tiene que llevar la MISMA llave para que el servidor lo deduplique. Ni la red ni un conflicto la tocan.
        val llavesEnviadas = mutableListOf<String>()
        val vm = viewModel()
        aparatoYServidor()
        llavesGuardadas["venue-1"] = "llave-guardada"
        coEvery { repository.issue(any(), any()) } coAnswers {
            llavesEnviadas += secondArg<String>()
            throw SIN_RED
        }
        val carrito = CartState(items = listOf(renglon()))

        vm.issue(carrito) {}
        vm.issue(carrito) {}

        assertEquals(listOf("llave-guardada", "llave-guardada"), llavesEnviadas)
        verify(exactly = 0) { secureStorage.soltarLlaveDeEmisionDeVales(any(), any()) }
        assertEquals("llave-guardada", llavesGuardadas["venue-1"])
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

        // El mismo texto que iOS (`areaTicketBusinessErrorMessage`).
        const val MENSAJE_CONFLICTO_DE_LLAVE =
            "Este vale ya se creó con otro contenido. Deja el carrito como estaba para recuperarlo, o vacíalo."

        // Lo mismo que lanza AreaTicketRepository.request ante un IOException.
        val SIN_RED = AreaTicketException(
            "AREA_TICKETS_REQUIRE_CONNECTION",
            "Los vales por área requieren conexión con Avoqado. El POS normal sigue disponible; reintenta el vale cuando vuelva el servidor.",
            true,
        )
    }
}
