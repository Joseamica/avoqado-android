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
import com.avoqado.pos.areatickets.data.PendingAreaTicketPrintRecord
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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D12 (Task 13): el papel (o el PDF) SALIÓ pero avisarle a Avoqado falló. El registro no se pierde: va a una lista en
 * disco escrita con `commit()` ANTES de la red, en el mismo commit que suelta el «pendiente de reimpresión», y la
 * pantalla no ofrece «Reimprimir» (sería un segundo papel). Se reintenta con SU llave hasta que el servidor lo tenga.
 * Más el P1 de duplicado de la Task 12: el registro de un intento FALLIDO nunca tapa la falla de impresión.
 */
class AreaTicketPendingPrintRecordTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<AreaTicketRepository>()
    private val printerService = mockk<PrinterService>()
    private val pdfGenerator = mockk<AreaTicketPdfGenerator>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    // El disco, respaldado con variables: el "" del mock relajado no es ni una lista ni un negocio.
    private var negocio: String? = "v1"
    private var guardado: String? = null
    private var codigoPendiente: String? = null
    private var negocioDelPendiente: String? = null
    private var discoAcepta = true
    /** Lo que se le pidió al disco cuando NO lo aceptó. */
    private var rechazado: String? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val lista = ListSerializer(PendingAreaTicketPrintRecord.serializer())

    @Before
    fun disco() {
        every { secureStorage.venueId } answers { negocio }
        every { secureStorage.venueDisplayName } returns "La Galeterie"
        every { secureStorage.pendingAreaTicketPrintRecords } answers { guardado }
        every { secureStorage.commitAreaTicketPrintRecords(any(), any()) } answers {
            if (!discoAcepta) {
                rechazado = firstArg()
                false
            } else {
                guardado = firstArg()
                if (secondArg()) {
                    codigoPendiente = null
                    negocioDelPendiente = null
                }
                true
            }
        }
        every { secureStorage.pendingAreaTicketPrintCode } answers { codigoPendiente }
        every { secureStorage.pendingAreaTicketPrintCode = any() } answers { codigoPendiente = firstArg() }
        every { secureStorage.pendingAreaTicketPrintVenueId } answers { negocioDelPendiente }
        every { secureStorage.pendingAreaTicketPrintVenueId = any() } answers { negocioDelPendiente = firstArg() }

        // Por default todo sale bien: cada prueba sólo sobreescribe lo que le toca.
        coEvery { repository.settings() } returns settings("AVOQADO")
        coEvery { repository.issue(any(), any()) } returns vale()
        coEvery { repository.resolveCheckoutScan(CODIGO) } returns
            AreaTicketScanData(type = "AREA_TICKET", code = CODIGO, ticket = vale())
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns
            mockk<SavedPrinter>(relaxed = true)
        coEvery { printerService.printAreaTicket(any(), any(), any()) } returns Unit
        every { pdfGenerator.generate(any(), any()) } returns "%PDF-test".encodeToByteArray()
    }

    // -- 1-2. El papel salió y el registro falló ---------------------------------------------------

    @Test
    fun `P1 el papel salio y el registro fallo - no se ofrece reimprimir y queda pendiente`() = runTest {
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } throws SIN_RED
        val vm = viewModel()

        vm.issue(carrito()) {}

        assertNull("El papel ya salió: reimprimir sería un segundo vale", vm.state.value.pendingReprintCode)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.message.orEmpty().contains(CODIGO))
        assertEquals(listOf(TICKET), pendientes().map { it.ticketId })
        assertNull("Al reabrir la app tampoco se ofrece reimprimir", codigoPendiente)
    }

    @Test
    fun `P1 la salida se guarda en disco ANTES de la red`() = runTest {
        val vm = viewModel()

        vm.issue(carrito()) {}

        coVerifyOrder {
            secureStorage.commitAreaTicketPrintRecords(any(), true)
            repository.recordPrint(any(), true, any(), any(), any(), any())
        }
    }

    // -- 3-4. El reintento y qué lo borra --------------------------------------------------------

    @Test
    fun `P1 el pendiente se reintenta con SU llave y se limpia`() = runTest {
        val vm = viewModel() // cola VACÍA: el init no manda nada
        guardar(registro(TICKET, "k-estable"))

        vm.retryPendingPrintRecords()

        coVerify(exactly = 1) { repository.recordPrint(TICKET, true, false, any(), any(), "k-estable") }
        assertTrue(pendientes().none { it.idempotencyKey == "k-estable" })
    }

    @Test
    fun `P1 al abrir la app el pendiente que dejo un cierre abrupto se manda solo`() = runTest {
        // La app murió entre el commit y el POST: lo guardado se manda al volver, sin que nadie toque nada.
        guardar(registro(TICKET, "k-del-cierre"))

        viewModel()

        coVerify(exactly = 1) { repository.recordPrint(TICKET, true, false, any(), any(), "k-del-cierre") }
        assertTrue(pendientes().isEmpty())
    }

    @Test
    fun `P1 una sesion vencida NO borra el pendiente y un vale que ya no existe si`() = runTest {
        val vm = viewModel()
        guardar(registro(TICKET, "k1"))
        // Sesión vencida, permisos, 5xx, red o un error desconocido: el papel salió y el servidor todavía no lo sabe.
        val seConservan = listOf(
            AreaTicketException("HTTP_401", "La sesión venció. Inicia sesión de nuevo.", false),
            AreaTicketException("TERMINAL_AREA_MISMATCH", "Esta terminal no puede imprimir vales de área.", false),
            AreaTicketException("HTTP_503", "No se pudo completar la operación de vales.", true),
            SIN_RED,
            IllegalStateException("desconocido"),
        )
        for (error in seConservan) {
            coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } throws error
            vm.retryPendingPrintRecords()
            assertEquals("${error.message} no debe borrar", listOf("k1"), pendientes().map { it.idempotencyKey })
        }

        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } throws
            AreaTicketException("AREA_TICKET_NOT_FOUND", "No encontramos ese vale en este local.", false)
        vm.retryPendingPrintRecords()

        assertTrue("El vale ya no existe: no hay nada que registrar", pendientes().isEmpty())
    }

    // -- 5. Doble toque ---------------------------------------------------------------------------

    @Test
    fun `P1 doble toque mientras se reintenta un pendiente no emite dos veces`() = runTest {
        val vm = viewModel()
        guardar(registro("t-anterior", "k-anterior"))
        val red = CompletableDeferred<Unit>()
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers { red.await() }

        vm.issue(carrito()) {}
        vm.issue(carrito()) {}
        red.complete(Unit)

        coVerify(exactly = 1) { repository.issue(any(), any()) }
    }

    // -- 6. PDF ------------------------------------------------------------------------------------

    @Test
    fun `P1 el PDF guardado con registro fallido no deja reimpresion pendiente`() = runTest {
        codigoPendiente = CODIGO
        negocioDelPendiente = "v1"
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } throws SIN_RED
        val vm = viewModel()
        assertEquals(CODIGO, vm.state.value.pendingReprintCode)

        vm.preparePendingPdf()
        withTimeout(5_000) {
            while (vm.state.value.pdfExport == null) yield()
        }
        vm.confirmPendingPdfSaved {}

        assertNull(vm.state.value.pendingReprintCode)
        assertNull(vm.state.value.pdfExport)
        assertEquals("Vale guardado como PDF por el operador.", pendientes().single().reason)
        assertNull(codigoPendiente)
    }

    // -- 7. Cambio de negocio a media cola -----------------------------------------------------------

    @Test
    fun `P1 cambiar de negocio a media cola no manda ni borra pendientes ajenos`() = runTest {
        val vm = viewModel()
        guardar(registro("t1", "k1"), registro("t2", "k2"))
        val primero = CompletableDeferred<Unit>()
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers { primero.await() }

        vm.retryPendingPrintRecords()
        negocio = "v2"
        primero.complete(Unit)

        coVerify(exactly = 1) { repository.recordPrint(any(), any(), any(), any(), any(), any()) }
        assertEquals("El primero sí llegó; el segundo espera a su negocio", listOf("k2"), pendientes().map { it.idempotencyKey })
    }

    @Test
    fun `P1 un vale no encontrado despues de cambiar de negocio no borra el registro`() = runTest {
        val vm = viewModel()
        guardar(registro("t1", "k1"))
        val respuesta = CompletableDeferred<Unit>()
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers { respuesta.await() }

        vm.retryPendingPrintRecords()
        negocio = "v2"
        respuesta.completeExceptionally(
            AreaTicketException("AREA_TICKET_NOT_FOUND", "No encontramos ese vale en este local.", false),
        )

        assertEquals(listOf("k1"), pendientes().map { it.idempotencyKey })
    }

    // -- 8. El disco no lo guardó: se avisa y se manda igual, por los tres caminos ---------------------

    @Test
    fun `si el disco no guarda, avisa y manda el registro igual - al emitir`() = runTest {
        discoAcepta = false
        val vm = viewModel()

        vm.issue(carrito()) {}

        val llave = llaveRechazada()
        coVerify(exactly = 1) { repository.recordPrint(TICKET, true, false, any(), any(), llave) }
        assertNull(vm.state.value.pendingReprintCode)
        assertNull(vm.state.value.error)
        assertEquals(AVISO_DISCO, vm.state.value.message)
    }

    @Test
    fun `si el disco no guarda, avisa y manda el registro igual - al reimprimir`() = runTest {
        discoAcepta = false
        codigoPendiente = CODIGO
        negocioDelPendiente = "v1"
        val vm = viewModel()

        vm.reprintPending {}

        val llave = llaveRechazada()
        coVerify(exactly = 1) { repository.recordPrint(TICKET, true, true, any(), any(), llave) }
        assertNull(vm.state.value.pendingReprintCode)
        assertNull(vm.state.value.error)
        assertEquals(AVISO_DISCO, vm.state.value.message)
    }

    @Test
    fun `si el disco no guarda, avisa y manda el registro igual - al guardar el PDF`() = runTest {
        discoAcepta = false
        codigoPendiente = CODIGO
        negocioDelPendiente = "v1"
        val vm = viewModel()

        vm.preparePendingPdf()
        withTimeout(5_000) {
            while (vm.state.value.pdfExport == null) yield()
        }
        vm.confirmPendingPdfSaved {}

        val llave = llaveRechazada()
        coVerify(exactly = 1) { repository.recordPrint(TICKET, true, false, any(), any(), llave) }
        assertNull(vm.state.value.pendingReprintCode)
        assertNull(vm.state.value.error)
        assertEquals(AVISO_DISCO, vm.state.value.message)
    }

    // -- A. El registro de un intento FALLIDO nunca tapa la falla de impresión (P1 de duplicado) -------

    @Test
    fun `P1 impresora caida y sin red - el vale emitido no se confunde con uno sin emitir`() = runTest {
        // Área externa: si se colara el error de red, la pantalla diría «no se puede emitir el vale» de un vale YA
        // emitido, y como la llave ya rotó, volver a emitir crearía un SEGUNDO vale.
        coEvery { repository.settings() } returns settings("EXTERNAL")
        coEvery { printerService.printAreaTicket(any(), any(), any()) } throws IllegalStateException("Sin papel")
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } throws SIN_RED
        val vm = viewModel()

        vm.issue(carrito()) {}

        assertEquals(areaTicketPrintFailureMessage(CODIGO, IllegalStateException()), vm.state.value.error)
        assertEquals(CODIGO, vm.state.value.pendingReprintCode)
        assertEquals("El papel no salió: la reimpresión sigue a salvo en disco", CODIGO, codigoPendiente)
        assertNull("Un intento fallido no va a la lista de salidas", guardado)
    }

    @Test
    fun `P1 sin impresora configurada y sin red - el vale emitido no se confunde con uno sin emitir`() = runTest {
        coEvery { repository.settings() } returns settings("EXTERNAL")
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns null
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } throws SIN_RED
        val vm = viewModel()

        vm.issue(carrito()) {}

        assertEquals(areaTicketPrintFailureMessage(CODIGO, IllegalStateException()), vm.state.value.error)
        assertEquals(CODIGO, vm.state.value.pendingReprintCode)
        assertEquals(CODIGO, codigoPendiente)
        assertNull(guardado)
    }

    // -- Revisión 1: lo que espera el cajero tiene tope, y el registro lleva el negocio del INICIO ------

    @Test
    fun `P1 un pendiente atorado no detiene el vale - a los 5 segundos se emite y el registro sigue guardado`() = runTest {
        val vm = viewModel()
        guardar(registro("t-atorado", "k-atorado"))
        // WiFi sin salida: el envío nunca contesta (en el aparato, 30 s de OkHttp por intento).
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }

        vm.issue(carrito()) {}
        advanceTimeBy(4_999)
        coVerify(exactly = 0) { repository.issue(any(), any()) }

        advanceTimeBy(2)
        coVerify(exactly = 1) { repository.issue(any(), any()) }

        // El papel ya salió: tampoco el registro nuevo, atorado detrás del viejo, detiene el mensaje.
        advanceTimeBy(5_001)
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.pendingReprintCode)
        assertEquals("Vale $CODIGO emitido correctamente.", vm.state.value.message)
        assertEquals("Cortar no borra nada", listOf("t-atorado", TICKET), pendientes().map { it.ticketId })
    }

    @Test
    fun `P1 la sesion que se cae entre el vale y el papel no deja el registro sin negocio`() = runTest {
        val vm = viewModel()
        // El servidor aceptó el vale y, antes del papel, el refresco del token cerró la sesión (desde el hilo de OkHttp).
        coEvery { repository.issue(any(), any()) } coAnswers {
            negocio = null
            vale()
        }

        vm.issue(carrito()) {}

        assertEquals("El registro lleva el negocio del vale", listOf("v1"), pendientes().map { it.venueId })
        coVerify(exactly = 0) { repository.recordPrint(any(), true, any(), any(), any(), any()) }

        negocio = "v1" // vuelve la sesión
        vm.retryPendingPrintRecords()

        coVerify(exactly = 1) { repository.recordPrint(TICKET, true, false, any(), any(), any()) }
        assertTrue(pendientes().isEmpty())
    }

    @Test
    fun `P2 sin negocio desde el inicio no se guarda un registro huerfano y la reimpresion sigue ofrecida`() = runTest {
        // Inalcanzable en el aparato (sin negocio el servidor no emite), pero si pasara: nada sin negocio entra a la
        // cola —nadie lo mandaría— y queda lo de antes de la Task 13: el error de «sin local» y «Reimprimir».
        for (sinNegocio in listOf<String?>(null, "")) {
            negocio = sinNegocio
            guardado = null
            codigoPendiente = null
            negocioDelPendiente = null
            val vm = viewModel()

            vm.issue(carrito()) {}

            assertNull("negocio=«$sinNegocio»", guardado)
            assertEquals(CODIGO, vm.state.value.pendingReprintCode)
            assertEquals(CODIGO, codigoPendiente)
            assertEquals("Selecciona un local antes de continuar.", vm.state.value.error)
        }
    }

    // -- Codex final #2: el tope de 5 s suelta la ESPERA, no el envío ---------------------------------

    @Test
    fun `P3 una cabeza lenta no atora a los de atras - siguen saliendo despues del tope sin reintentar`() = runTest {
        val vm = viewModel() // cola VACÍA: el init no manda nada
        guardar(registro("t-lento", "k-lento"), registro("t-segundo", "k-segundo"))
        // Red lenta pero viva: la cabeza contesta a los 6 s; los demás, al instante.
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } coAnswers {
            if (firstArg<String>() == "t-lento") delay(6_000)
        }

        vm.issue(carrito()) {}
        advanceTimeBy(5_001)
        coVerify(exactly = 1) { repository.issue(any(), any()) }

        advanceTimeBy(2_000)
        coVerify(exactly = 1) { repository.recordPrint("t-segundo", true, false, any(), any(), "k-segundo") }
        assertTrue("El segundo salió del disco", pendientes().none { it.idempotencyKey == "k-segundo" })
    }

    // -- Task 13: reimprimir y el PDF van al negocio del INICIO aunque la sesión cambie a media operación -----

    @Test
    fun `P2 reimprimir registra la salida en el negocio del inicio aunque cambie a media operacion`() = runTest {
        codigoPendiente = CODIGO
        negocioDelPendiente = "v1"
        val vm = viewModel()
        // Mientras se busca el vale, el cajero cambia de negocio.
        coEvery { repository.resolveCheckoutScan(CODIGO) } coAnswers {
            negocio = "v2"
            AreaTicketScanData(type = "AREA_TICKET", code = CODIGO, ticket = vale())
        }

        vm.reprintPending {}

        assertEquals(listOf("v1"), pendientes().map { it.venueId })
        assertTrue(pendientes().single().reprint)
        coVerify(exactly = 0) { repository.recordPrint(any(), true, any(), any(), any(), any()) }
    }

    @Test
    fun `P2 el PDF registra la salida en el negocio del inicio aunque cambie a media operacion`() = runTest {
        codigoPendiente = CODIGO
        negocioDelPendiente = "v1"
        val vm = viewModel()
        coEvery { repository.resolveCheckoutScan(CODIGO) } coAnswers {
            negocio = "v2"
            AreaTicketScanData(type = "AREA_TICKET", code = CODIGO, ticket = vale())
        }

        vm.preparePendingPdf()
        withTimeout(5_000) {
            while (vm.state.value.pdfExport == null) yield()
        }
        assertEquals("v1", vm.state.value.pdfExport?.venueId)
        vm.confirmPendingPdfSaved {}

        assertEquals(listOf("v1"), pendientes().map { it.venueId })
        coVerify(exactly = 0) { repository.recordPrint(any(), true, any(), any(), any(), any()) }
    }

    // -- helpers ------------------------------------------------------------------------------------

    private fun viewModel() = AreaTicketOperationsViewModel(
        repository = repository,
        printerService = printerService,
        pdfGenerator = pdfGenerator,
        secureStorage = secureStorage,
    )

    private fun pendientes(): List<PendingAreaTicketPrintRecord> =
        guardado?.let { json.decodeFromString(lista, it) }.orEmpty()

    private fun guardar(vararg registros: PendingAreaTicketPrintRecord) {
        guardado = json.encodeToString(lista, registros.toList())
    }

    private fun llaveRechazada(): String {
        val pedido = rechazado
        assertNotNull("La salida se intentó guardar en disco antes de la red", pedido)
        return json.decodeFromString(lista, pedido!!).single().idempotencyKey
    }

    private fun registro(ticketId: String, llave: String) = PendingAreaTicketPrintRecord(
        venueId = "v1",
        ticketId = ticketId,
        code = CODIGO,
        reprint = false,
        idempotencyKey = llave,
    )

    private fun carrito() = CartState(
        items = listOf(
            CartItem(id = "cart-line-1", type = CartItemType.ProductItem("p1"), name = "Latte", unitPrice = 5500),
        ),
    )

    private fun vale() = AreaTicket(
        id = TICKET,
        code = CODIGO,
        status = "ISSUED",
        fulfillmentArea = AreaTicketArea(id = "a1", name = "Cafetería", fulfillmentMode = "IMMEDIATE"),
        subtotal = "55.00",
        total = "55.00",
        issuedAt = "2026-09-30T20:32:00.000Z",
        lines = listOf(
            AreaTicketLine(
                id = "l1",
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
        venueId = "v1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-1",
            name = "Cafetería",
            fulfillmentArea = AreaTicketArea(
                id = "a1",
                name = "Cafetería",
                fulfillmentMode = "IMMEDIATE",
                settlementRoute = settlementRoute,
            ),
            canIssueAreaTickets = true,
            defaultWorkspace = "AREA_OPERATIONS",
        ),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
    )

    private companion object {
        const val TICKET = "t1"
        const val CODIGO = "9470000015"
        const val AVISO_DISCO = "El vale salió, pero este aparato no pudo guardar su registro. No lo reimprimas."

        // Lo mismo que lanza AreaTicketRepository.request ante un IOException.
        val SIN_RED = AreaTicketException(
            "AREA_TICKETS_REQUIRE_CONNECTION",
            "Los vales por área requieren conexión con Avoqado. El POS normal sigue disponible; reintenta el vale cuando vuelva el servidor.",
            true,
        )
    }
}
