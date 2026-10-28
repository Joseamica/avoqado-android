package com.avoqado.pos.areatickets.presentation

import android.content.SharedPreferences
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketException
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.IntentoDeVale
import com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.core.data.local.LlaveDeEmisionDeVales
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * 🔴 D5 (vales-ciclo, 27-oct): el vale se pidió, el SERVIDOR lo creó, su respuesta se perdió y la app se cerró. Antes, al
 * reabrir, el carrito salía vacío y el cajero tenía que vaciar y volver a armarlo (o, con llave nueva, salía un segundo vale y
 * el primero quedaba ISSUED para siempre). Ahora se guarda lo que se mandó junto con la llave y «Recuperar» lo pide IGUAL: el
 * SERVIDOR devuelve el mismo vale. Aquí el disco es real (`LlaveDeEmisionDeVales` sobre unas preferencias en memoria) y el
 * SERVIDOR recuerda qué contenido creó cada llave, como `issueAreaTicket`.
 */
class AreaTicketValeSinConfirmarTest {
    @get:Rule
    val main = MainDispatcherRule()

    /**
     * El disco del aparato (sobrevive a que la app se cierre) y la memoria del proceso. Como las preferencias de ANDROID, un
     * `commit()` cambia la memoria ANTES de escribir, aunque el disco falle (Codex r4 #3).
     */
    private val disco = mutableMapOf<String, Any>()
    private var memoria = mutableMapOf<String, Any>()
    private val prefs: SharedPreferences = mockk<SharedPreferences>().also { p ->
        every { p.getString(any(), any()) } answers { memoria[firstArg()] as String? ?: secondArg() }
        every { p.getBoolean(any(), any()) } answers { memoria[firstArg()] as Boolean? ?: secondArg() }
        every { p.edit() } answers {
            val cambios = mutableMapOf<String, Any?>()
            mockk<SharedPreferences.Editor>().also { e ->
                every { e.putString(any(), any()) } answers { cambios[firstArg()] = secondArg<String?>(); e }
                every { e.putBoolean(any(), any()) } answers { cambios[firstArg()] = secondArg<Boolean>(); e }
                every { e.remove(any()) } answers { cambios[firstArg()] = null; e }
                every { e.commit() } answers {
                    fun aplicar(m: MutableMap<String, Any>) = cambios.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
                    aplicar(memoria)
                    if (fallaElDisco) false else { aplicar(disco); true }
                }
            }
        }
    }

    private val repository = mockk<AreaTicketRepository>()
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val comandaDispatcher = mockk<ComandaDispatcher>()
    private val comanda = slot<List<RoutableItem>>()

    /** El SERVIDOR: el contenido y el vale de cada llave (`venueId_idempotencyKey`). */
    private val valesPorLlave = mutableMapOf<String, Pair<List<IssueAreaTicketLineRequest>, AreaTicket>>()
    private var hayRed = true
    private var pierdeLaRespuesta = false
    private var estadoDelVale = "ISSUED"
    private var papelDelVale = "NOT_PRINTED"
    private var venceEn: String? = null
    /** El disco no deja escribir (WINDOWS con el archivo bloqueado). */
    private var fallaElDisco = false
    private val papelPendiente: String? get() = memoria["papel"] as String?
    private var pedidos = 0

    @Before
    fun aparatoYServidor() {
        ValesRevisadosEnEsteProceso.olvidar()
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.venueTimezone } returns "America/Mexico_City"
        every { secureStorage.pendingAreaTicketPrintCode } answers { papelPendiente }
        every { secureStorage.pendingAreaTicketPrintCode = any() } answers {
            firstArg<String?>()?.let { memoria["papel"] = it; disco["papel"] = it } ?: run { memoria.remove("papel"); disco.remove("papel") }
        }
        // El registro del papel (D12) sí queda en disco: si no, el mensaje sería el aviso de disco.
        every { secureStorage.pendingAreaTicketPrintRecords } returns null
        every { secureStorage.commitAreaTicketPrintRecords(any(), any()) } answers {
            if (secondArg<Boolean>()) { memoria.remove("papel"); disco.remove("papel") }
            true
        }
        every { secureStorage.valeRecuperado(any(), any(), any()) } answers {
            LlaveDeEmisionDeVales.recuperado(prefs, firstArg(), secondArg(), thirdArg(), "papel", "papelNegocio")
        }
        every { secureStorage.llaveDeEmisionDeVales(any()) } answers { LlaveDeEmisionDeVales.leer(prefs, firstArg()) }
        every { secureStorage.guardarIntentoDeVale(any(), any(), any()) } answers {
            LlaveDeEmisionDeVales.guardar(prefs, firstArg(), secondArg(), thirdArg())
        }
        every { secureStorage.soltarLlaveDeEmisionDeVales(any(), any()) } answers {
            LlaveDeEmisionDeVales.soltar(prefs, firstArg(), secondArg())
        }
        every { secureStorage.revisarValeAMedias(any()) } answers { LlaveDeEmisionDeVales.alArrancar(prefs, firstArg()) }
        every { secureStorage.valeSinConfirmar(any()) } answers { LlaveDeEmisionDeVales.valeSinConfirmar(prefs, firstArg()) }
        every { secureStorage.olvidarValeSinConfirmar(any(), any()) } answers {
            LlaveDeEmisionDeVales.olvidarSinConfirmar(prefs, firstArg(), secondArg())
        }
        coEvery { repository.settings() } returns settings()
        coEvery { repository.recordPrint(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { printerService.getDefaultPrinterWithHardwareFallback(PrinterRole.RECEIPT) } returns mockk<SavedPrinter>(relaxed = true)
        coEvery { printerService.printAreaTicket(any(), any(), any()) } returns Unit
        coEvery { comandaDispatcher.dispatchAreaComanda(any(), capture(comanda), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { repository.issue(any(), any()) } coAnswers {
            pedidos++
            if (!hayRed) throw sinConexion()
            val lineas = firstArg<List<IssueAreaTicketLineRequest>>()
            val existente = valesPorLlave[secondArg()]
            val vale = when {
                existente == null -> ticket("V${valesPorLlave.size + 1}").also { valesPorLlave[secondArg()] = lineas to it }
                existente.first == lineas -> existente.second
                else -> throw AreaTicketException("AREA_TICKET_IDEMPOTENCY_CONFLICT", "La llave ya se usó con otro contenido.", false)
            }
            if (pierdeLaRespuesta) throw sinConexion() // el vale SÍ quedó creado
            vale.copy(status = estadoDelVale, expiresAt = venceEn, printStatus = papelDelVale)
        }
    }

    /** La app (otra vez): un proceso nuevo con el mismo disco. */
    private fun abrirLaApp(): AreaTicketOperationsViewModel {
        ValesRevisadosEnEsteProceso.olvidar()
        memoria = disco.toMutableMap()
        return AreaTicketOperationsViewModel(
            repository = repository,
            printerService = printerService,
            pdfGenerator = mockk(relaxed = true),
            secureStorage = secureStorage,
            comandaDispatcher = comandaDispatcher,
        )
    }

    @Test
    fun `D5 respuesta perdida y app cerrada - Recuperar trae el MISMO vale, lo imprime y no queda nada a medias`() = runTest {
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        assertEquals("El SERVIDOR sí lo creó", 1, valesPorLlave.size)

        pierdeLaRespuesta = false
        val vm = abrirLaApp()
        assertNotNull("Al abrir, el aviso", vm.state.value.valeSinConfirmar)
        assertTrue(vm.state.value.mostrarValeSinConfirmar)
        assertEquals(
            "Un vale quedó sin confirmar (1 producto): no se sabe si salió. «Recuperar» lo trae sin duplicarlo; si nunca llegó, lo crea ahora.",
            avisoDeValeSinConfirmar(vm.state.value.valeSinConfirmar!!, hora = null),
        )

        // Armar otra vez lo mismo y emitir NO saca un segundo vale: abre el aviso.
        vm.ocultarValeSinConfirmar()
        val antes = pedidos
        vm.issue(carrito()) { error("no debía emitir") }
        assertEquals("Ni lo pidió", antes, pedidos)
        assertTrue(vm.state.value.mostrarValeSinConfirmar)

        var copiaRetirada: String? = "sin llamar"
        vm.recuperarValeSinConfirmar { copiaRetirada = it.carritoGuardadoId; true }

        assertEquals("Sigue habiendo UN vale", 1, valesPorLlave.size)
        assertEquals("Vale V1 recuperado.", vm.state.value.message)
        coVerify(exactly = 1) { printerService.printAreaTicket(any(), any(), any()) }
        assertNull(copiaRetirada)
        assertNull(vm.state.value.valeSinConfirmar)
        assertNull("Nada a medias en el disco", LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"))
        assertNull(papelPendiente)
        // La comanda sale con su categoría guardada (Codex r2 #3), sin carrito vivo.
        assertEquals("cat-helados", comanda.captured.single().categoryId)

        // Y el siguiente vale sale con llave nueva: es otro.
        vm.issue(carrito()) {}
        assertEquals(2, valesPorLlave.size)
    }

    @Test
    fun `D5 si el pedido nunca llego, Recuperar lo crea ahora`() = runTest {
        hayRed = false
        abrirLaApp().issue(carrito()) {}
        assertTrue(valesPorLlave.isEmpty())

        hayRed = true
        val vm = abrirLaApp()
        vm.recuperarValeSinConfirmar()

        assertEquals(1, valesPorLlave.size)
        assertEquals("Vale V1 recuperado.", vm.state.value.message)
    }

    @Test
    fun `D5 el vale lo creo el primer intento - el segundo da 409 y se recupera el primero con su copia`() = runTest {
        // Codex r1 #3 y r2 #1: se pidió con S1, se perdió la respuesta, se restauró S2 y se reintentó (409 del D2).
        pierdeLaRespuesta = true
        val primera = abrirLaApp()
        primera.issue(carrito(copia = "S1")) {}
        pierdeLaRespuesta = false
        primera.issue(carrito(copia = "S2", cantidad = 2)) {}
        assertEquals(1, valesPorLlave.size)

        var copiaRetirada: String? = null
        abrirLaApp().recuperarValeSinConfirmar { copiaRetirada = it.carritoGuardadoId; true }

        assertEquals("Sigue habiendo UN vale", 1, valesPorLlave.size)
        assertEquals("Se retira la copia del intento que respondió", "S1", copiaRetirada)
    }

    @Test
    fun `D5 sin red Recuperar lo dice y el aviso se queda`() = runTest {
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false
        hayRed = false

        val vm = abrirLaApp()
        vm.recuperarValeSinConfirmar()

        assertEquals(sinConexion().message, vm.state.value.error)
        assertNotNull(vm.state.value.valeSinConfirmar)
        assertNotNull(LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"))

        hayRed = true
        vm.dismissFeedback()
        vm.recuperarValeSinConfirmar()
        assertEquals("Vale V1 recuperado.", vm.state.value.message)
        assertEquals(1, valesPorLlave.size)
    }

    @Test
    fun `D5 un vale cancelado no se imprime como nuevo`() = runTest {
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false
        estadoDelVale = "CANCELLED"

        val vm = abrirLaApp()
        vm.recuperarValeSinConfirmar()

        assertEquals("El vale V1 ya está cancelado: no se imprime.", vm.state.value.error)
        coVerify(exactly = 0) { printerService.printAreaTicket(any(), any(), any()) }
        assertNull(LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"))
    }

    @Test
    fun `D5 Descartar lo olvida y lo siguiente sale como vale nuevo`() = runTest {
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false

        val vm = abrirLaApp()
        vm.descartarValeSinConfirmar()
        assertNull(vm.state.value.valeSinConfirmar)
        assertFalse(vm.state.value.mostrarValeSinConfirmar)

        var emitido = false
        vm.issue(carrito()) { emitido = true }
        assertTrue(emitido)
        assertEquals("Llave nueva: otro vale", 2, valesPorLlave.size)
    }

    @Test
    fun `D5 en la misma sesion un vale vencido que devuelve la misma llave no se imprime`() = runTest {
        venceEn = "2026-01-01T00:00:00Z"
        val vm = abrirLaApp()
        var emitido = false
        vm.issue(carrito()) { emitido = true }

        assertEquals("El vale V1 ya está vencido: no se imprime.", vm.state.value.error)
        assertFalse("El carrito se queda", emitido)
        coVerify(exactly = 0) { printerService.printAreaTicket(any(), any(), any()) }
        assertNull("La llave ya se usó: se suelta", LlaveDeEmisionDeVales.leer(prefs, "venue-1"))
    }

    @Test
    fun `D5 la copia guardada se retira ANTES de olvidar el vale sin confirmar`() = runTest {
        // Codex r3 #2: si la app muere entre los dos pasos, el registro sigue y «Recuperar» la vuelve a retirar.
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito(copia = "S1")) {}
        pierdeLaRespuesta = false
        var registroAlRetirar: Any? = null
        abrirLaApp().recuperarValeSinConfirmar { registroAlRetirar = LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"); true }
        assertNotNull("al retirar la copia, el registro todavía existe", registroAlRetirar)
    }

    @Test
    fun `D5 si el disco no guarda la recuperacion no se imprime y el vale sigue sin confirmar`() = runTest {
        // Codex r3 #1: el papel pendiente y olvidar el registro van en UN commit; si no quedó, no sale papel.
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false
        val vm = abrirLaApp()

        fallaElDisco = true
        vm.recuperarValeSinConfirmar()
        assertEquals("No se pudo guardar en este aparato. Intenta de nuevo.", vm.state.value.error)
        coVerify(exactly = 0) { printerService.printAreaTicket(any(), any(), any()) }
        assertNotNull("en memoria sigue (r4 #3)", LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"))
        assertFalse(vm.state.value.submitting)
        assertTrue(vm.state.value.valeSinConfirmarVisible)
        // Y «Emitir vale» sigue frenado: no sale otro con llave nueva.
        val antes = pedidos
        vm.issue(carrito()) { error("no debía emitir") }
        assertEquals(antes, pedidos)

        fallaElDisco = false
        vm.recuperarValeSinConfirmar()
        assertEquals("Vale V1 recuperado.", vm.state.value.message)
        coVerify(exactly = 1) { printerService.printAreaTicket(any(), any(), any()) }
        assertEquals("Sigue habiendo UN vale", 1, valesPorLlave.size)
    }

    @Test
    fun `D5 un vale cuyo papel ya salio no se imprime otra vez como original`() = runTest {
        // Codex r4 #4: en WINDOWS falló soltar la llave tras imprimir; al abrir quedó «sin confirmar». El SERVIDOR dice PRINTED.
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false
        papelDelVale = "PRINTED"
        val vm = abrirLaApp()

        vm.recuperarValeSinConfirmar()

        coVerify(exactly = 0) { printerService.printAreaTicket(any(), any(), any()) }
        coVerify(exactly = 0) { comandaDispatcher.dispatchAreaComanda(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("Queda su reimpresión", "V1", vm.state.value.pendingReprintCode)
        assertNull(LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"))
    }

    @Test
    fun `D5 si no se puede retirar la copia el vale sigue sin confirmar`() = runTest {
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito(copia = "S1")) {}
        pierdeLaRespuesta = false
        val vm = abrirLaApp()

        vm.recuperarValeSinConfirmar { false }

        assertEquals("No se pudo retirar la copia guardada de este vale. Intenta de nuevo.", vm.state.value.error)
        coVerify(exactly = 0) { printerService.printAreaTicket(any(), any(), any()) }
        assertNotNull(LlaveDeEmisionDeVales.valeSinConfirmar(prefs, "venue-1"))
    }

    @Test
    fun `D5 Vaciar con un vale en duda no lo tira - queda sin confirmar`() = runTest {
        // Antes «Vaciar» soltaba la llave y el vale que sí salió quedaba ISSUED para siempre.
        pierdeLaRespuesta = true
        val vm = abrirLaApp()
        vm.issue(carrito()) {}
        pierdeLaRespuesta = false
        LlaveDeEmisionDeVales.soltar(prefs, "venue-1") // lo que hace CartViewModel.vaciar()

        vm.issue(carrito()) { error("no debía emitir") }
        assertTrue(vm.state.value.valeSinConfirmarVisible)
        vm.recuperarValeSinConfirmar()
        assertEquals("Vale V1 recuperado.", vm.state.value.message)
        assertEquals(1, valesPorLlave.size)
    }

    @Test
    fun `D5 sin configuracion de vales el aviso se ve y lleva adentro el error`() = runTest {
        // Codex r3 #3: sin red y sin la configuración guardada, el aviso se escondía junto con su error.
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false
        hayRed = false
        coEvery { repository.settings() } throws sinConexion()

        val vm = abrirLaApp()
        assertNull(vm.state.value.settings)
        assertTrue(vm.state.value.valeSinConfirmarVisible)

        vm.recuperarValeSinConfirmar()
        assertTrue("el aviso sigue, con el error adentro", vm.state.value.valeSinConfirmarVisible)
        assertEquals(sinConexion().message, vm.state.value.error)
        assertNull("no sale otro diálogo encima", vm.state.value.checkoutBlockingError)
    }

    @Test
    fun `D5 que ya no sirve se ve aunque no haya configuracion de vales`() = runTest {
        pierdeLaRespuesta = true
        abrirLaApp().issue(carrito()) {}
        pierdeLaRespuesta = false
        estadoDelVale = "PAID"
        coEvery { repository.settings() } throws sinConexion()

        val vm = abrirLaApp()
        vm.recuperarValeSinConfirmar()

        assertFalse(vm.state.value.valeSinConfirmarVisible)
        assertEquals("El vale V1 ya está cobrado: no se imprime.", vm.state.value.checkoutBlockingError)
    }

    @Test
    fun `D5 que ya no sirve - por estado y por vencimiento`() {
        val ahora = Instant.parse("2026-10-27T21:00:00Z")
        assertNull(valeQueYaNoSirve(ticket("V1"), ahora))
        assertNull(valeQueYaNoSirve(ticket("V1").copy(expiresAt = "2026-10-27T22:00:00Z"), ahora))
        assertEquals("El vale V1 ya está vencido: no se imprime.", valeQueYaNoSirve(ticket("V1").copy(expiresAt = "2026-10-27T21:00:00Z"), ahora))
        assertEquals("El vale V1 ya está cobrado: no se imprime.", valeQueYaNoSirve(ticket("V1").copy(status = "PAID"), ahora))
        assertNull("Un estado nuevo no se frena", valeQueYaNoSirve(ticket("V1").copy(status = "OTRO"), ahora))
    }

    @Test
    fun `D5 lo que se manda es lo que se guarda`() {
        val intento: IntentoDeVale = intentoDeVale(carrito(copia = "S1"), ahora = 7)
        assertEquals(listOf(IssueAreaTicketLineRequest("linea-1", "p-helado", "1", weightKg = "0.435")), intento.lineas)
        assertEquals("S1", intento.carritoGuardadoId)
        assertEquals(7L, intento.hora)
    }

    private fun sinConexion() = AreaTicketException(
        "AREA_TICKETS_REQUIRE_CONNECTION",
        "Los vales por área requieren conexión con Avoqado. El POS normal sigue disponible; reintenta el vale cuando vuelva el servidor.",
        true,
    )

    private fun carrito(copia: String? = null, cantidad: Int = 1) = CartState(
        items = listOf(
            CartItem(
                id = "linea-1",
                type = CartItemType.ProductItem("p-helado"),
                name = "Helado",
                unitPrice = 5000,
                quantity = cantidad,
                weightKg = 0.435,
                categoryId = "cat-helados",
            ),
        ),
        carritoGuardadoId = copia,
    )

    private fun ticket(code: String) = AreaTicket(
        id = "ticket-$code",
        code = code,
        status = "ISSUED",
        fulfillmentArea = AreaTicketArea(id = "area-1", name = "Cremería", fulfillmentMode = "HOLD_UNTIL_PAID"),
        subtotal = "21.75",
        total = "21.75",
        issuedAt = "2026-10-27T19:42:00.000Z",
    )

    private fun settings() = AreaTicketSettingsData(
        venueId = "venue-1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-1",
            name = "Cremería",
            fulfillmentArea = AreaTicketArea(id = "area-1", name = "Cremería", fulfillmentMode = "HOLD_UNTIL_PAID"),
            canIssueAreaTickets = true,
            defaultWorkspace = "AREA_OPERATIONS",
        ),
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
    )
}
